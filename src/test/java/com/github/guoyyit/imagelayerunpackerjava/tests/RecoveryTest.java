package com.github.guoyyit.imagelayerunpackerjava.tests;

import com.github.guoyyit.imagelayerunpackerjava.UnpackerProperties;
import com.github.guoyyit.imagelayerunpackerjava.layer.LayerManager;
import com.github.guoyyit.imagelayerunpackerjava.layer.LayerMeta;
import com.github.guoyyit.imagelayerunpackerjava.layer.LayerStatus;
import com.github.guoyyit.imagelayerunpackerjava.layer.LayerStore;
import com.github.guoyyit.imagelayerunpackerjava.view.MergedView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static com.github.guoyyit.imagelayerunpackerjava.tests.TarBuilder.tar;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Crash recovery: committed layers are adopted without re-unpacking,
 * interrupted layers are re-unpacked from the staged tar or marked FAILED,
 * and orphaned files are cleaned up.
 */
class RecoveryTest {

    @TempDir
    Path tempDir;

    private LayerManager newManager() throws Exception {
        UnpackerProperties properties = new UnpackerProperties();
        properties.setRootDir(tempDir.toString());
        LayerManager manager = new LayerManager(properties);
        manager.recover();
        return manager;
    }

    private static String readString(MergedView view, String path) throws Exception {
        try (var in = view.openFile(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Simulate a crash mid-unpack: staged tar present, meta stuck at UNPACKING. */
    private void simulateInterruptedLayer(String id, int seq, byte[] tarBytes) throws Exception {
        LayerStore store = new LayerStore(tempDir);
        store.initDirectories();
        Files.write(store.stagingTar(id), tarBytes);
        LayerMeta meta = new LayerMeta();
        meta.setId(id);
        meta.setStatus(LayerStatus.UNPACKING);
        meta.setSeq(seq);
        meta.setCreatedAt(Instant.now());
        meta.setUpdatedAt(Instant.now());
        store.save(meta);
        // Half-written rootfs garbage that recovery must wipe before re-unpacking.
        Files.createDirectories(store.rootfsDir(id).resolve("partial"));
        Files.writeString(store.rootfsDir(id).resolve("partial/junk.txt"), "junk");
    }

    @Test
    void committedLayersSurviveRestartWithoutReunpack() throws Exception {
        LayerManager manager = newManager();
        manager.unpackLayer("l1", new ByteArrayInputStream(tar().file("a.txt", "A").build()));
        manager.unpackLayer("l2", new ByteArrayInputStream(tar().file("b.txt", "B").build()));
        // Marker proving the rootfs is not re-extracted on recovery.
        Path marker = tempDir.resolve("layers/l1/rootfs/marker.txt");
        Files.writeString(marker, "do-not-reunpack");

        LayerManager restarted = newManager();
        List<LayerMeta> layers = restarted.listLayers();
        assertEquals(2, layers.size());
        assertEquals(LayerStatus.COMMITTED, layers.get(0).getStatus());
        assertEquals(LayerStatus.COMMITTED, layers.get(1).getStatus());
        assertTrue(Files.exists(marker), "committed layer must not be re-unpacked on restart");
        try (MergedView view = restarted.openView()) {
            assertEquals("A", readString(view, "/a.txt"));
            assertEquals("B", readString(view, "/b.txt"));
        }
    }

    @Test
    void interruptedLayerIsReunpackedFromStagedTar() throws Exception {
        LayerManager manager = newManager();
        manager.unpackLayer("l1", new ByteArrayInputStream(tar().file("base.txt", "base").build()));
        simulateInterruptedLayer("l2", 1, tar().file("top.txt", "top").build());

        LayerManager restarted = newManager();
        List<LayerMeta> layers = restarted.listLayers();
        assertEquals(2, layers.size());
        LayerMeta l2 = layers.get(1);
        assertEquals("l2", l2.getId());
        assertEquals(LayerStatus.COMMITTED, l2.getStatus());
        try (MergedView view = restarted.openView()) {
            assertEquals("base", readString(view, "/base.txt"));
            assertEquals("top", readString(view, "/top.txt"));
            assertNull(view.stat("/partial/junk.txt"), "half-written garbage must be wiped");
        }
        assertFalse(Files.exists(tempDir.resolve("staging/l2.tar")), "staged tar consumed after recovery");
    }

    @Test
    void interruptedLayerWithoutStagedTarBecomesFailed() throws Exception {
        simulateInterruptedLayer("ghost", 0, tar().file("x", "y").build());
        Files.delete(tempDir.resolve("staging/ghost.tar"));

        LayerManager restarted = newManager();
        assertEquals(1, restarted.listLayers().size());
        LayerMeta meta = restarted.listLayers().get(0);
        assertEquals(LayerStatus.FAILED, meta.getStatus());
        org.junit.jupiter.api.Assertions.assertNotNull(meta.getError());
        try (MergedView view = restarted.openView()) {
            assertNull(view.stat("/x"));
        }
    }

    @Test
    void failedLayerStaysFailedAfterRestartAndCanBeRetried() throws Exception {
        LayerManager manager = newManager();
        byte[] bad = tar().file("../evil", "e").build();
        try {
            manager.unpackLayer("bad", new ByteArrayInputStream(bad));
        } catch (Exception ignored) {
            // expected
        }
        LayerManager restarted = newManager();
        assertEquals(1, restarted.listLayers().size());
        assertEquals(LayerStatus.FAILED, restarted.listLayers().get(0).getStatus());

        restarted.unpackLayer("bad", new ByteArrayInputStream(tar().file("good.txt", "g").build()));
        assertEquals(LayerStatus.COMMITTED, restarted.listLayers().get(0).getStatus());
        try (MergedView view = restarted.openView()) {
            assertEquals("g", readString(view, "/good.txt"));
        }
    }

    @Test
    void orphanFilesAreCleanedOnRecovery() throws Exception {
        LayerManager manager = newManager();
        manager.unpackLayer("l1", new ByteArrayInputStream(tar().file("a", "1").build()));
        // Orphans: layer dir without meta, staging tar without layer.
        Path orphanDir = tempDir.resolve("layers/orphan/rootfs");
        Files.createDirectories(orphanDir);
        Files.writeString(orphanDir.resolve("junk"), "j");
        Path orphanTar = tempDir.resolve("staging/orphan.tar");
        Files.write(orphanTar, tar().file("z", "z").build());

        LayerManager restarted = newManager();
        assertEquals(1, restarted.listLayers().size());
        assertFalse(Files.exists(tempDir.resolve("layers/orphan")));
        assertFalse(Files.exists(orphanTar));
    }

    @Test
    void deletedLayerStaysDeletedAfterRestart() throws Exception {
        LayerManager manager = newManager();
        manager.unpackLayer("l1", new ByteArrayInputStream(tar().file("a", "1").build()));
        manager.unpackLayer("l2", new ByteArrayInputStream(tar().file("b", "2").build()));
        manager.deleteLayer("l1");

        LayerManager restarted = newManager();
        assertEquals(1, restarted.listLayers().size());
        assertEquals("l2", restarted.listLayers().get(0).getId());
        try (MergedView view = restarted.openView()) {
            assertNull(view.stat("/a"));
            assertEquals("2", new String(view.openFile("/b").readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
