package com.github.guoyyit.imagelayerunpackerjava.tests;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LayerManagementTests {

    @TempDir
    Path tempDir;

    private ServiceProperties properties;
    private StorageLayout layout;
    private LayerManager layerManager;
    private MergeService mergeService;

    @BeforeEach
    void setUp() throws Exception {
        properties = new ServiceProperties();
        properties.setStorageDir(tempDir.resolve("store"));
        properties.setMaxLayerBytes(8192);
        properties.setMaxTotalBytes(5000);
        layout = new StorageLayout(properties);
        UnpackService unpackService = new UnpackService(properties);
        layerManager = new LayerManager(layout, unpackService, properties);
        layerManager.initialize();
        layerManager.recover();
        mergeService = new MergeService(layerManager);
    }

    @Test
    void rejectsLayerOverSingleQuotaAndKeepsState() throws Exception {
        byte[] big = TarBuilder.create().file("big.txt", "x".repeat(9000)).build();
        assertThrows(ApiException.QuotaExceeded.class,
                () -> layerManager.submit("big", new ByteArrayInputStream(big)));
        assertEquals(0, layerManager.list().size());
        assertFalse(java.nio.file.Files.exists(layout.layerDir("big")));
    }

    @Test
    void rejectsWhenTotalQuotaExceededButKeepsExistingLayers() throws Exception {
        byte[] first = TarBuilder.create().file("a.txt", "a".repeat(3000)).build();
        layerManager.submit("first", new ByteArrayInputStream(first));
        byte[] second = TarBuilder.create().file("b.txt", "b".repeat(3000)).build();
        assertThrows(ApiException.QuotaExceeded.class,
                () -> layerManager.submit("second", new ByteArrayInputStream(second)));
        assertEquals(1, layerManager.list().size());
        assertFalse(java.nio.file.Files.exists(layout.layerDir("second")));
        assertEquals("a".repeat(3000), new String(
                mergeService.readFile("a.txt"), StandardCharsets.UTF_8));
    }

    @Test
    void failedLayerDoesNotAffectPriorLayersAndLeavesNoResidue() throws Exception {
        layerManager.submit("good", new ByteArrayInputStream(
                TarBuilder.create().file("keep.txt", "keep").build()));
        byte[] evil = TarBuilder.create().file("../escape.txt", "x").build();
        assertThrows(ApiException.UnsafeArchive.class,
                () -> layerManager.submit("evil", new ByteArrayInputStream(evil)));

        assertEquals(1, layerManager.list().size());
        assertEquals("keep", new String(
                mergeService.readFile("keep.txt"), StandardCharsets.UTF_8));
        assertFalse(java.nio.file.Files.exists(layout.layerDir("evil")));
    }

    @Test
    void sameLayerRetryIsDeterministic() throws Exception {
        byte[] tar = TarBuilder.create().file("data.txt", "data").build();
        var first = layerManager.submit("same", new ByteArrayInputStream(tar));
        var second = layerManager.submit("same", new ByteArrayInputStream(tar));
        assertEquals(1, layerManager.list().size());
        assertEquals(first.getSequence(), second.getSequence());
        assertEquals(first.getSha256(), second.getSha256());
        assertEquals("data", new String(
                mergeService.readFile("data.txt"), StandardCharsets.UTF_8));
    }

    @Test
    void retryAfterFailureSucceedsWithCleanState() throws Exception {
        byte[] bad = TarBuilder.create().file("../escape.txt", "x").build();
        assertThrows(ApiException.class,
                () -> layerManager.submit("retry", new ByteArrayInputStream(bad)));
        byte[] good = TarBuilder.create().file("retry.txt", "ok").build();
        layerManager.submit("retry", new ByteArrayInputStream(good));
        assertEquals(1, layerManager.list().size());
        assertEquals("ok", new String(
                mergeService.readFile("retry.txt"), StandardCharsets.UTF_8));
    }

    @Test
    void deleteReclaimsSpaceAndAllowsNewLayer() throws Exception {
        byte[] first = TarBuilder.create().file("a.txt", "a".repeat(3000)).build();
        layerManager.submit("first", new ByteArrayInputStream(first));
        assertThrows(ApiException.QuotaExceeded.class,
                () -> layerManager.submit("second", new ByteArrayInputStream(
                        TarBuilder.create().file("b.txt", "b".repeat(3000)).build())));
        layerManager.delete("first");
        assertEquals(0, layerManager.list().size());
        assertFalse(java.nio.file.Files.exists(layout.layerDir("first")));
        layerManager.submit("second", new ByteArrayInputStream(
                TarBuilder.create().file("b.txt", "b".repeat(3000)).build()));
        assertEquals(1, layerManager.list().size());
    }

    @Test
    void rejectsInvalidLayerId() throws Exception {
        byte[] tar = TarBuilder.create().file("a.txt", "a").build();
        assertThrows(ApiException.BadRequest.class,
                () -> layerManager.submit("../evil", new ByteArrayInputStream(tar)));
    }

    @Test
    void rejectsCorruptArchive() throws Exception {
        byte[] truncated = TarBuilder.create().file("a.txt", "hello").truncated(300);
        assertThrows(ApiException.CorruptArchive.class,
                () -> layerManager.submit("corrupt", new ByteArrayInputStream(truncated)));
        assertEquals(0, layerManager.list().size());
    }
}
