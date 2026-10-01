package com.github.guoyyit.imagelayerunpackerjava.tests;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static com.github.guoyyit.imagelayerunpackerjava.tests.TarBuilder.tar;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test of the HTTP API against a live server on a random port.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class HttpApiTest {

    private static Path rootDir;

    @BeforeAll
    static void setUp() throws IOException {
        rootDir = Files.createTempDirectory("unpacker-http-test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("unpacker.root-dir", () -> rootDir.toString());
    }

    @Autowired
    private TestRestTemplate rest;

    private static ResponseEntity<Map> postLayer(String id, byte[] tarBytes, TestRestTemplate rest) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        return rest.exchange("/api/layers/" + id, HttpMethod.POST,
                new HttpEntity<>(tarBytes, headers), Map.class);
    }

    @Test
    void fullLayerLifecycleOverHttp() throws Exception {
        byte[] l1 = tar().file("app/config.txt", "v1").file("app/old.txt", "old").build();
        byte[] l2 = tar().file("app/config.txt", "v2").whiteout("app/old.txt").build();

        ResponseEntity<Map> r1 = postLayer("http-l1", l1, rest);
        assertEquals(HttpStatus.OK, r1.getStatusCode());
        assertEquals("COMMITTED", r1.getBody().get("status"));

        ResponseEntity<Map> r2 = postLayer("http-l2", l2, rest);
        assertEquals(HttpStatus.OK, r2.getStatusCode());

        ResponseEntity<List> layers = rest.getForEntity("/api/layers", List.class);
        assertEquals(HttpStatus.OK, layers.getStatusCode());
        assertTrue(layers.getBody().size() >= 2);

        ResponseEntity<List> tree = rest.getForEntity("/api/view/tree?path=/app", List.class);
        assertEquals(HttpStatus.OK, tree.getStatusCode());
        @SuppressWarnings("unchecked")
        List<String> names = ((List<Map<String, Object>>) tree.getBody()).stream()
                .map(e -> (String) e.get("name"))
                .sorted(Comparator.naturalOrder())
                .toList();
        assertEquals(List.of("config.txt"), names, "whiteout must hide old.txt via HTTP too");

        ResponseEntity<String> file = rest.getForEntity("/api/view/file?path=/app/config.txt", String.class);
        assertEquals(HttpStatus.OK, file.getStatusCode());
        assertEquals("v2", file.getBody());

        ResponseEntity<String> gone = rest.getForEntity("/api/view/file?path=/app/old.txt", String.class);
        assertEquals(HttpStatus.NOT_FOUND, gone.getStatusCode());

        ResponseEntity<Void> del = rest.exchange("/api/layers/http-l2", HttpMethod.DELETE, null, Void.class);
        assertEquals(HttpStatus.NO_CONTENT, del.getStatusCode());

        ResponseEntity<String> afterDelete = rest.getForEntity("/api/view/file?path=/app/config.txt", String.class);
        assertEquals("v1", afterDelete.getBody(), "after deleting l2 the lower content is visible again");
    }

    @Test
    void maliciousArchiveReturns400WithErrorBody() throws Exception {
        byte[] evil = tar().file("../escape", "e").build();
        ResponseEntity<Map> response = postLayer("http-evil", evil, rest);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals("UNPACK_FAILED", response.getBody().get("error"));
        assertTrue(String.valueOf(response.getBody().get("message")).contains("traversal"));
    }

    @Test
    void unknownPathsReturn404() {
        ResponseEntity<Map> tree = rest.getForEntity("/api/view/tree?path=/no/such/dir", Map.class);
        assertEquals(HttpStatus.NOT_FOUND, tree.getStatusCode());
        ResponseEntity<Map> del = rest.exchange("/api/layers/never-existed", HttpMethod.DELETE, null, Map.class);
        assertEquals(HttpStatus.NOT_FOUND, del.getStatusCode());
    }

    @Test
    void invalidLayerIdReturns400() throws Exception {
        byte[] archive = tar().file("a", "b").build();
        ResponseEntity<Map> response = postLayer("bad id with spaces", archive, rest);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertFalse(response.getBody().isEmpty());
    }
}
