package com.github.guoyyit.imagelayerunpackerjava.tests;

import com.github.guoyyit.imagelayerunpackerjava.UnpackerProperties;
import com.github.guoyyit.imagelayerunpackerjava.layer.LayerManager;
import com.github.guoyyit.imagelayerunpackerjava.layer.LayerStatus;
import com.github.guoyyit.imagelayerunpackerjava.layer.UnpackException;
import com.github.guoyyit.imagelayerunpackerjava.view.MergedView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

import static com.github.guoyyit.imagelayerunpackerjava.tests.TarBuilder.tar;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Quota enforcement and safe deletion: over-quota layers are rejected without
 * disturbing existing state; deleting a layer reclaims its budget and files.
 */
class QuotaAndDeleteTest {

    @TempDir
    Path tempDir;

    private LayerManager newManager(long maxLayerBytes, long maxTotalBytes) throws Exception {
        UnpackerProperties properties = new UnpackerProperties();
        properties.setRootDir(tempDir.toString());
        properties.setMaxLayerBytes(maxLayerBytes);
        properties.setMaxTotalBytes(maxTotalBytes);
        LayerManager manager = new LayerManager(properties);
        manager.recover();
        return manager;
    }

    @Test
    void totalQuotaExceededRejectsNewLayerAndKeepsCurrentState() throws Exception {
        LayerManager manager = newManager(10_000, 1_000);
        manager.unpackLayer("l1", new ByteArrayInputStream(tar().file("a", new byte[600]).build()));
        byte[] tooBig = tar().file("b", new byte[600]).build();
        UnpackException e = assertThrows(UnpackException.class,
                () -> manager.unpackLayer("l2", new ByteArrayInputStream(tooBig)));
        assertTrue(e.getMessage().contains("quota"));
        // State unchanged: only l1, still readable, no l2 leftovers on disk.
        assertEquals(1, manager.listLayers().size());
        assertFalse(Files.exists(tempDir.resolve("layers/l2")));
        try (MergedView view = manager.openView()) {
            assertEquals(600, view.stat("/a").size());
        }
    }

    @Test
    void deletingLayerFreesQuotaForNewLayer() throws Exception {
        LayerManager manager = newManager(10_000, 1_000);
        manager.unpackLayer("l1", new ByteArrayInputStream(tar().file("a", new byte[600]).build()));
        manager.deleteLayer("l1");
        manager.unpackLayer("l2", new ByteArrayInputStream(tar().file("b", new byte[600]).build()));
        assertEquals(1, manager.listLayers().size());
        assertEquals("l2", manager.listLayers().get(0).getId());
    }

    @Test
    void deleteRemovesFilesAndMetadata() throws Exception {
        LayerManager manager = newManager(10_000, 100_000);
        manager.unpackLayer("l1", new ByteArrayInputStream(tar().file("a", "1").build()));
        assertTrue(Files.exists(tempDir.resolve("layers/l1/meta.json")));
        manager.deleteLayer("l1");
        assertFalse(Files.exists(tempDir.resolve("layers/l1")));
        assertEquals(0, manager.listLayers().size());
    }

    @Test
    void deleteUnknownLayerFails() throws Exception {
        LayerManager manager = newManager(10_000, 100_000);
        assertThrows(NoSuchFileException.class, () -> manager.deleteLayer("nope"));
    }

    @Test
    void failedLayerCanBeDeleted() throws Exception {
        LayerManager manager = newManager(10_000, 100_000);
        try {
            manager.unpackLayer("bad", new ByteArrayInputStream(tar().file("../x", "e").build()));
        } catch (UnpackException ignored) {
            // expected
        }
        assertEquals(LayerStatus.FAILED, manager.listLayers().get(0).getStatus());
        manager.deleteLayer("bad");
        assertEquals(0, manager.listLayers().size());
        assertFalse(Files.exists(tempDir.resolve("layers/bad")));
    }

    @Test
    void deletingMiddleLayerUpdatesMergedView() throws Exception {
        LayerManager manager = newManager(10_000, 100_000);
        manager.unpackLayer("l1", new ByteArrayInputStream(tar().file("f.txt", "bottom").build()));
        manager.unpackLayer("l2", new ByteArrayInputStream(tar().file("f.txt", "middle").build()));
        manager.unpackLayer("l3", new ByteArrayInputStream(tar().file("g.txt", "top").build()));
        manager.deleteLayer("l2");
        try (MergedView view = manager.openView()) {
            // l2 gone: its override of f.txt disappears, bottom value visible again.
            assertEquals("bottom", new String(view.openFile("/f.txt").readAllBytes(), StandardCharsets.UTF_8));
            assertEquals("top", new String(view.openFile("/g.txt").readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals(2, manager.listLayers().size());
    }
}
