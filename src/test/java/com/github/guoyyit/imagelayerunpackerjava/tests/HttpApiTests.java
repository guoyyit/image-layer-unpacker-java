package com.github.guoyyit.imagelayerunpackerjava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "imagelayer.storage-dir=${java.io.tmpdir}/ilu-http-tests",
        "imagelayer.max-layer-bytes=1048576",
        "imagelayer.max-total-bytes=8388608"
})
class HttpApiTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void uploadListReadAndDeleteLayer() throws Exception {
        byte[] tar = TarBuilder.create()
                .directory("etc")
                .file("etc/a.txt", "hello")
                .build();

        mockMvc.perform(post("/api/layers/layer-a")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(tar))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("layer-a"))
                .andExpect(jsonPath("$.sequence").isNumber());

        mockMvc.perform(get("/api/layers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("layer-a"));

        mockMvc.perform(get("/api/view/list").param("path", "/etc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].path").value("/etc/a.txt"))
                .andExpect(jsonPath("$[0].type").value("file"));

        mockMvc.perform(get("/api/view/read").param("path", "/etc/a.txt"))
                .andExpect(status().isOk())
                .andExpect(content().bytes("hello".getBytes()));

        mockMvc.perform(get("/api/view/stat").param("path", "/etc/a.txt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("file"));

        mockMvc.perform(delete("/api/layers/layer-a"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/view/read").param("path", "/etc/a.txt"))
                .andExpect(status().isNotFound());
    }

    @Test
    void unsafeArchiveReturnsBadRequest() throws Exception {
        byte[] tar = TarBuilder.create().file("../evil", "x").build();
        mockMvc.perform(post("/api/layers/bad")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(tar))
                .andExpect(status().isBadRequest());
    }

    @Test
    void corruptArchiveReturnsUnprocessableEntity() throws Exception {
        byte[] tar = TarBuilder.create().file("a.txt", "hello").truncated(200);
        mockMvc.perform(post("/api/layers/corrupt")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(tar))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void missingLayerReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/layers/nope"))
                .andExpect(status().isNotFound());
    }

    @Test
    void directoryWhiteoutHidesSubtreeOverHttp() throws Exception {
        byte[] base = TarBuilder.create()
                .directory("web")
                .directory("web/assets")
                .file("web/assets/app.js", "js-content")
                .file("web/index.html", "index")
                .build();
        byte[] upper = TarBuilder.create().whiteout("web", "assets").build();
        try {
            mockMvc.perform(post("/api/layers/http-wh-base")
                            .contentType(MediaType.APPLICATION_OCTET_STREAM)
                            .content(base))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/api/layers/http-wh-upper")
                            .contentType(MediaType.APPLICATION_OCTET_STREAM)
                            .content(upper))
                    .andExpect(status().isOk());

            mockMvc.perform(get("/api/view/stat").param("path", "/web/assets"))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get("/api/view/stat").param("path", "/web/assets/app.js"))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get("/api/view/read").param("path", "/web/assets/app.js"))
                    .andExpect(status().isNotFound());
            mockMvc.perform(get("/api/view/list").param("path", "/web/assets"))
                    .andExpect(status().isNotFound());

            mockMvc.perform(get("/api/view/stat").param("path", "/web"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.type").value("directory"));
            mockMvc.perform(get("/api/view/list").param("path", "/web"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[0].path").value("/web/index.html"))
                    .andExpect(jsonPath("$[1]").doesNotExist());
            mockMvc.perform(get("/api/view/read").param("path", "/web/index.html"))
                    .andExpect(status().isOk())
                    .andExpect(content().bytes("index".getBytes()));
        } finally {
            mockMvc.perform(delete("/api/layers/http-wh-upper"));
            mockMvc.perform(delete("/api/layers/http-wh-base"));
        }

        mockMvc.perform(get("/api/view/stat").param("path", "/web"))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidIdReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/layers/..%2Fevil")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content(new byte[] {1, 2}))
                .andExpect(status().is4xxClientError());
    }
}
