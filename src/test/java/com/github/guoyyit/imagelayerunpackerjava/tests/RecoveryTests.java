package com.github.guoyyit.imagelayerunpackerjava.tests;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.guoyyit.imagelayerunpackerjava.config.ServiceProperties;
import com.github.guoyyit.imagelayerunpackerjava.layer.LayerManager;
import com.github.guoyyit.imagelayerunpackerjava.merge.MergeService;
import com.github.guoyyit.imagelayerunpackerjava.storage.StorageLayout;
import com.github.guoyyit.imagelayerunpackerjava.unpack.UnpackService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryTests {

    @TempDir
    Path tempDir;

    private LayerManager newManager() {
        ServiceProperties properties = new ServiceProperties();
        properties.setStorageDir(tempDir.resolve("store"));
        properties.setMaxLayerBytes(1024 * 1024);
        properties.setMaxTotalBytes(8L * 1024 * 1024);
        StorageLayout layout = new StorageLayout(properties);
        UnpackService unpackService = new UnpackService(properties);
        LayerManager manager = new LayerManager(layout, unpackService, properties);
        manager.initialize();
        manager.recover();
        return manager;
    }

    @Test
    void committedLayersSurviveRestartWithoutReunpack() throws Exception {
        LayerManager first = newManager();
        first.submit("layer-1", new ByteArrayInputStream(
                TarBuilder.create().file("a.txt", "first").build()));

        LayerManager second = newManager();
        assertEquals(1, second.list().size());
        assertEquals("layer-1", second.list().get(0).getId());

        MergeService mergeService = new MergeService(second);
        assertEquals("first", new String(
                mergeService.readFile("a.txt"), java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void incompleteLayerIsReunpackedOnRestart() throws Exception {
        LayerManager first = newManager();
        first.submit("done", new ByteArrayInputStream(
                TarBuilder.create().file("done.txt", "done").build()));

        byte[] pendingTar = TarBuilder.create()
                .file("pending.txt", "pending")
                .build();
        StorageLayout layout = layoutFor();
        Path layerDir = layout.layerDir("pending");
        Files.createDirectories(layerDir);
        Files.write(layout.layerBlob("pending"), pendingTar);
        assertFalse(Files.exists(layout.layerRoot("pending")));

        LayerManager second = newManager();
        assertEquals(2, second.list().size());
        MergeService mergeService = new MergeService(second);
        assertEquals("pending", new String(
                mergeService.readFile("pending.txt"), java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(Files.isDirectory(layout.layerRoot("pending")));
    }

    @Test
    void corruptIncompleteLayerIsDiscardedOnRestart() throws Exception {
        StorageLayout layout = layoutFor();
        newManager();
        Path layerDir = layout.layerDir("broken");
        Files.createDirectories(layerDir);
        Files.write(layout.layerBlob("broken"), new byte[] {1, 2, 3, 4});

        LayerManager restarted = newManager();
        assertEquals(0, restarted.list().size());
        assertFalse(Files.exists(layerDir));
    }

    @Test
    void stagingDirectoriesAreCleanedOnRestart() throws Exception {
        StorageLayout layout = layoutFor();
        newManager();
        Path staging = layout.stagingDir("ghost", "abc123");
        Files.createDirectories(staging);

        LayerManager restarted = newManager();
        assertEquals(0, restarted.list().size());
        assertFalse(Files.exists(staging));
    }

    private StorageLayout layoutFor() {
        StorageLayout layout = new StorageLayout(properties());
        layout.initialize();
        return layout;
    }

    private ServiceProperties properties() {
        ServiceProperties properties = new ServiceProperties();
        properties.setStorageDir(tempDir.resolve("store"));
        properties.setMaxLayerBytes(1024 * 1024);
        properties.setMaxTotalBytes(8L * 1024 * 1024);
        return properties;
    }
}
