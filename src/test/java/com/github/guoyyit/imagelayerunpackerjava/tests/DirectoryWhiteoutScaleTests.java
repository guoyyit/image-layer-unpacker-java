package com.github.guoyyit.imagelayerunpackerjava.tests;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.guoyyit.imagelayerunpackerjava.config.ServiceProperties;
import com.github.guoyyit.imagelayerunpackerjava.error.ApiException;
import com.github.guoyyit.imagelayerunpackerjava.layer.LayerManager;
import com.github.guoyyit.imagelayerunpackerjava.merge.MergeService;
import com.github.guoyyit.imagelayerunpackerjava.storage.StorageLayout;
import com.github.guoyyit.imagelayerunpackerjava.unpack.UnpackService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Directory-level deletion must stay correct and responsive with tens of layers
 * and thousands of paths per layer.
 */
class DirectoryWhiteoutScaleTests {

    private static final int LAYERS = 30;
    private static final int GROUPS = 20;
    private static final int FILES_PER_GROUP = 50;

    @TempDir
    Path tempDir;

    private LayerManager layerManager;
    private MergeService mergeService;

    @BeforeEach
    void setUp() {
        ServiceProperties properties = new ServiceProperties();
        properties.setStorageDir(tempDir.resolve("store"));
        properties.setMaxLayerBytes(8L * 1024 * 1024);
        properties.setMaxTotalBytes(256L * 1024 * 1024);
        StorageLayout layout = new StorageLayout(properties);
        UnpackService unpackService = new UnpackService(properties);
        layerManager = new LayerManager(layout, unpackService, properties);
        layerManager.initialize();
        mergeService = new MergeService(layerManager);
    }

    @Test
    void tensOfLayersWithThousandsOfPathsStayCorrectAndResponsive() throws Exception {
        for (int i = 0; i < LAYERS; i++) {
            TarBuilder builder = TarBuilder.create();
            for (int g = 0; g < GROUPS; g++) {
                for (int k = 0; k < FILES_PER_GROUP; k++) {
                    builder.file("grp" + g + "/n" + i + "/f" + k + ".txt",
                            "content-" + g + "-" + i + "-" + k);
                }
            }
            layerManager.submit("layer-" + i, new ByteArrayInputStream(builder.build()));
        }
        layerManager.submit("wiper", new ByteArrayInputStream(TarBuilder.create()
                .whiteout("", "grp3")
                .whiteout("", "grp7")
                .whiteout("grp0/n5", "f0.txt")
                .build()));

        long started = System.nanoTime();

        List<String> root = mergeService.listDirectory("/").stream()
                .map(MergeService.MergeNode::getPath)
                .toList();
        assertEquals(GROUPS - 2, root.size());
        assertTrue(root.contains("/grp0"));
        assertTrue(!root.contains("/grp3"));
        assertTrue(!root.contains("/grp7"));

        assertThrows(ApiException.NotFound.class, () -> mergeService.stat("/grp3"));
        assertThrows(ApiException.NotFound.class,
                () -> mergeService.readFile("grp3/n10/f25.txt"));
        assertThrows(ApiException.NotFound.class, () -> mergeService.listDirectory("/grp3/n10"));
        assertThrows(ApiException.NotFound.class,
                () -> mergeService.stat("/grp7/n29/f49.txt"));

        List<String> grp0 = mergeService.listDirectory("/grp0").stream()
                .map(MergeService.MergeNode::getPath)
                .toList();
        assertEquals(LAYERS, grp0.size());
        List<String> n5 = mergeService.listDirectory("/grp0/n5").stream()
                .map(MergeService.MergeNode::getPath)
                .toList();
        assertEquals(FILES_PER_GROUP - 1, n5.size());
        assertTrue(!n5.contains("/grp0/n5/f0.txt"));

        String content = new String(mergeService.readFile("grp12/n17/f33.txt"),
                StandardCharsets.UTF_8);
        assertEquals("content-12-17-33", content);
        assertEquals(MergeService.TYPE_FILE,
                mergeService.stat("/grp12/n17/f33.txt").getType());

        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        assertTrue(elapsedMillis < 30_000,
                "merged view operations took too long: " + elapsedMillis + "ms");
    }
}
