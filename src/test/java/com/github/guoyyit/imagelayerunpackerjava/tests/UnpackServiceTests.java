package com.github.guoyyit.imagelayerunpackerjava.tests;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.guoyyit.imagelayerunpackerjava.config.ServiceProperties;
import com.github.guoyyit.imagelayerunpackerjava.error.ApiException;
import com.github.guoyyit.imagelayerunpackerjava.model.UnpackReport;
import com.github.guoyyit.imagelayerunpackerjava.unpack.UnpackService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnpackServiceTests {

    @TempDir
    Path tempDir;

    private UnpackService unpackService;
    private Path root;

    @BeforeEach
    void setUp() {
        ServiceProperties properties = new ServiceProperties();
        properties.setMaxLayerBytes(1024 * 1024);
        unpackService = new UnpackService(properties);
        root = tempDir.resolve("layer-root");
    }

    @Test
    void extractsFilesAndCollectsWhiteouts() throws Exception {
        byte[] tar = TarBuilder.create()
                .directory("etc")
                .file("etc/a.txt", "a")
                .whiteout("etc", "old.txt")
                .opaque("var/log")
                .build();
        Path blob = tempDir.resolve("blob.tar");
        Files.write(blob, tar);

        UnpackReport report = unpackService.extract(blob, root);

        assertTrue(Files.isRegularFile(root.resolve("etc/a.txt")));
        assertFalse(Files.exists(root.resolve("etc/.wh.old.txt")));
        assertTrue(report.getWhiteouts().contains("etc/old.txt"));
        assertTrue(report.getOpaqueDirs().contains("var/log"));
    }

    @Test
    void rejectsPathTraversal() throws Exception {
        byte[] tar = TarBuilder.create()
                .file("../evil.txt", "evil")
                .build();
        Path blob = tempDir.resolve("blob.tar");
        Files.write(blob, tar);

        assertThrows(ApiException.UnsafeArchive.class,
                () -> unpackService.extract(blob, root));
        assertFalse(Files.exists(tempDir.resolve("evil.txt")));
    }

    @Test
    void rejectsAbsoluteSymlink() throws Exception {
        byte[] tar = TarBuilder.create()
                .symlink("link", "/etc/passwd")
                .build();
        Path blob = tempDir.resolve("blob.tar");
        Files.write(blob, tar);

        assertThrows(ApiException.UnsafeArchive.class,
                () -> unpackService.extract(blob, root));
    }

    @Test
    void rejectsEscapingSymlink() throws Exception {
        byte[] tar = TarBuilder.create()
                .symlink("link", "../../outside")
                .build();
        Path blob = tempDir.resolve("blob.tar");
        Files.write(blob, tar);

        assertThrows(ApiException.UnsafeArchive.class,
                () -> unpackService.extract(blob, root));
    }

    @Test
    void rejectsHardLinkToMissingTarget() throws Exception {
        byte[] tar = TarBuilder.create()
                .hardlink("link", "missing.txt")
                .build();
        Path blob = tempDir.resolve("blob.tar");
        Files.write(blob, tar);

        assertThrows(ApiException.UnsafeArchive.class,
                () -> unpackService.extract(blob, root));
    }

    @Test
    void rejectsOverQuotaLayer() throws Exception {
        ServiceProperties properties = new ServiceProperties();
        properties.setMaxLayerBytes(4);
        UnpackService limited = new UnpackService(properties);
        byte[] tar = TarBuilder.create()
                .file("big.txt", "12345")
                .build();
        Path blob = tempDir.resolve("blob.tar");
        Files.write(blob, tar);

        assertThrows(ApiException.QuotaExceeded.class,
                () -> limited.extract(blob, root));
        assertFalse(Files.exists(root));
    }

    @Test
    void failedExtractionLeavesNoRoot() throws Exception {
        byte[] tar = TarBuilder.create()
                .file("ok.txt", "ok")
                .file("../escape.txt", "x")
                .build();
        Path blob = tempDir.resolve("blob.tar");
        Files.write(blob, tar);

        assertThrows(ApiException.class, () -> unpackService.extract(blob, root));
        assertFalse(Files.exists(root));
    }

    @Test
    void acceptsHardLinkWithinLayer() throws Exception {
        byte[] tar = TarBuilder.create()
                .file("original.txt", "data")
                .hardlink("copy.txt", "original.txt")
                .build();
        Path blob = tempDir.resolve("blob.tar");
        Files.write(blob, tar);

        unpackService.extract(blob, root);
        assertEquals("data", Files.readString(root.resolve("copy.txt")));
    }

    @Test
    void rejectsWhiteoutConflictingWithSameLayerEntry() throws Exception {
        byte[] tar = TarBuilder.create()
                .file("a.txt", "data")
                .whiteout("", "a.txt")
                .build();
        Path blob = tempDir.resolve("blob.tar");
        Files.write(blob, tar);

        assertThrows(ApiException.UnsafeArchive.class,
                () -> unpackService.extract(blob, root));
    }
}
