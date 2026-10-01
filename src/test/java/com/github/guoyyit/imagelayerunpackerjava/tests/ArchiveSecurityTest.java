package com.github.guoyyit.imagelayerunpackerjava.tests;

import com.github.guoyyit.imagelayerunpackerjava.UnpackerProperties;
import com.github.guoyyit.imagelayerunpackerjava.layer.LayerManager;
import com.github.guoyyit.imagelayerunpackerjava.layer.LayerStatus;
import com.github.guoyyit.imagelayerunpackerjava.layer.UnpackException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static com.github.guoyyit.imagelayerunpackerjava.tests.TarBuilder.tar;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hostile and broken archives must be rejected with a clear error and must
 * not leave partial state behind.
 */
class ArchiveSecurityTest {

    @TempDir
    Path tempDir;

    private LayerManager newManager() throws Exception {
        UnpackerProperties properties = new UnpackerProperties();
        properties.setRootDir(tempDir.toString());
        LayerManager manager = new LayerManager(properties);
        manager.recover();
        return manager;
    }

    private void assertRejected(byte[] archive) throws Exception {
        LayerManager manager = newManager();
        assertThrows(UnpackException.class,
                () -> manager.unpackLayer("bad", new ByteArrayInputStream(archive)));
        // The failed layer is recorded but inactive, and no rootfs content survives.
        assertEquals(LayerStatus.FAILED, manager.listLayers().get(0).getStatus());
        Path rootfs = tempDir.resolve("layers/bad/rootfs");
        assertFalse(Files.exists(rootfs), "failed unpack must not leave a rootfs behind");
    }

    @Test
    void rejectsPathTraversal() throws Exception {
        assertRejected(tar().file("../escape.txt", "evil").build());
        assertFalse(Files.exists(tempDir.getParent().resolve("escape.txt")));
    }

    @Test
    void rejectsDeepPathTraversal() throws Exception {
        assertRejected(tar().file("a/b/../../../../escape.txt", "evil").build());
    }

    @Test
    void rejectsAbsolutePath() throws Exception {
        assertRejected(tar().rawFile("/etc/pwned", "evil").build());
    }

    @Test
    void rejectsAbsoluteSymlinkTarget() throws Exception {
        assertRejected(tar().symlink("link", "/etc/passwd").build());
    }

    @Test
    void rejectsSymlinkEscapingLayer() throws Exception {
        assertRejected(tar().symlink("sub/link", "../../outside").build());
    }

    @Test
    void acceptsInLayerRelativeSymlink() throws Exception {
        LayerManager manager = newManager();
        manager.unpackLayer("ok", new ByteArrayInputStream(tar()
                .dir("lib")
                .file("lib/real.txt", "content")
                .symlink("lib/alias.txt", "real.txt")
                .build()));
        try (var view = manager.openView()) {
            assertEquals("content", new String(view.openFile("/lib/alias.txt").readAllBytes()));
        }
    }

    @Test
    void rejectsHardlinkToMissingTarget() throws Exception {
        assertRejected(tar().hardlink("hl", "no/such/file").build());
    }

    @Test
    void rejectsDeviceFiles() throws Exception {
        assertRejected(tar().device("dev/sda").build());
    }

    @Test
    void rejectsTruncatedArchive() throws Exception {
        byte[] valid = tar().file("a.txt", "hello").build();
        byte[] truncated = Arrays.copyOf(valid, valid.length - 512);
        assertRejected(truncated);
    }

    @Test
    void rejectsGarbageArchive() throws Exception {
        byte[] garbage = new byte[1024];
        Arrays.fill(garbage, (byte) 0x41);
        assertRejected(garbage);
    }

    @Test
    void rejectsArchiveCutMidFileData() throws Exception {
        byte[] valid = tar().file("big.bin", new byte[8192]).build();
        // Keep the header of big.bin but cut into its data blocks.
        byte[] cut = Arrays.copyOf(valid, 512 + 100);
        byte[] archive = Arrays.copyOf(cut, 2048); // pad with zeros to keep block alignment
        assertRejected(archive);
    }

    @Test
    void rejectsOversizedLayer() throws Exception {
        LayerManager manager = newManager();
        UnpackerProperties tiny = new UnpackerProperties();
        tiny.setRootDir(tempDir.resolve("tiny").toString());
        tiny.setMaxLayerBytes(64);
        LayerManager limited = new LayerManager(tiny);
        limited.recover();
        byte[] archive = tar().file("big", new byte[1024]).build();
        UnpackException e = assertThrows(UnpackException.class,
                () -> limited.unpackLayer("fat", new ByteArrayInputStream(archive)));
        assertTrue(e.getMessage().contains("size limit"));
    }

    @Test
    void rejectsInvalidLayerId() throws Exception {
        LayerManager manager = newManager();
        byte[] archive = tar().file("a", "b").build();
        assertThrows(IllegalArgumentException.class,
                () -> manager.unpackLayer("../evil", new ByteArrayInputStream(archive)));
        assertThrows(IllegalArgumentException.class,
                () -> manager.unpackLayer("a/b", new ByteArrayInputStream(archive)));
    }

    @Test
    void failedLayerDoesNotAffectCommittedLayers() throws Exception {
        LayerManager manager = newManager();
        manager.unpackLayer("good", new ByteArrayInputStream(tar().file("f.txt", "fine").build()));
        assertThrows(UnpackException.class, () ->
                manager.unpackLayer("bad", new ByteArrayInputStream(tar().file("../x", "e").build())));
        try (var view = manager.openView()) {
            assertEquals("fine", new String(view.openFile("/f.txt").readAllBytes()));
        }
        assertEquals(2, manager.listLayers().size());
    }

    @Test
    void retryAfterFailureIsDeterministic() throws Exception {
        LayerManager manager = newManager();
        byte[] bad = tar().file("../x", "e").build();
        assertThrows(UnpackException.class,
                () -> manager.unpackLayer("l", new ByteArrayInputStream(bad)));
        // Retry the same id with a valid archive: must succeed cleanly, no leftovers.
        manager.unpackLayer("l", new ByteArrayInputStream(tar().file("ok.txt", "ok").build()));
        try (var view = manager.openView()) {
            assertEquals("ok", new String(view.openFile("/ok.txt").readAllBytes()));
        }
        assertEquals(1, manager.listLayers().size());
        assertEquals(LayerStatus.COMMITTED, manager.listLayers().get(0).getStatus());
    }

    @Test
    void reuploadOfCommittedLayerIsIdempotent() throws Exception {
        LayerManager manager = newManager();
        byte[] archive = tar().file("f", "1").build();
        manager.unpackLayer("l", new ByteArrayInputStream(archive));
        var first = manager.listLayers().get(0);
        manager.unpackLayer("l", new ByteArrayInputStream(archive));
        manager.unpackLayer("l", new ByteArrayInputStream(archive));
        assertEquals(1, manager.listLayers().size());
        assertEquals(first.getCreatedAt(), manager.listLayers().get(0).getCreatedAt());
    }

    @Test
    void stagingTarIsRemovedAfterCommit() throws Exception {
        LayerManager manager = newManager();
        manager.unpackLayer("l", new ByteArrayInputStream(tar().file("f", "1").build()));
        assertFalse(Files.exists(tempDir.resolve("staging/l.tar")));
    }
}
