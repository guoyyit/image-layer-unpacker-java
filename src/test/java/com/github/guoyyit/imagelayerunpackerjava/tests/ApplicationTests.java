package com.github.guoyyit.imagelayerunpackerjava.tests;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Context smoke test; uses an isolated storage root so the repo stays clean.
 */
@SpringBootTest(properties = "unpacker.root-dir=${java.io.tmpdir}/image-layer-unpacker-context-test")
public class ApplicationTests {
    @Test
    public void contentLoad() {
    }
}
