package com.github.guoyyit.imagelayerunpackerjava.tests;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.guoyyit.imagelayerunpackerjava.config.ServiceProperties;
import com.github.guoyyit.imagelayerunpackerjava.layer.LayerManager;
import com.github.guoyyit.imagelayerunpackerjava.merge.MergeService;
import com.github.guoyyit.imagelayerunpackerjava.model.LayerRecord;
import com.github.guoyyit.imagelayerunpackerjava.storage.StorageLayout;
import com.github.guoyyit.imagelayerunpackerjava.unpack.UnpackService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MergeSemanticsTests {

    @TempDir
    Path tempDir;

    private LayerManager layerManager;
    private MergeService mergeService;

    @BeforeEach
    void setUp() {
        ServiceProperties properties = new ServiceProperties();
        properties.setStorageDir(tempDir.resolve("store"));
        properties.setMaxLayerBytes(1024 * 1024);
        properties.setMaxTotalBytes(8L * 1024 * 1024);
        StorageLayout layout = new StorageLayout(properties);
        UnpackService unpackService = new UnpackService(properties);
        layerManager = new LayerManager(layout, unpackService, properties);
        layerManager.initialize();
        mergeService = new MergeService(layerManager);
    }

    @Test
    void overlaysFilesMergesDirectoriesAndAppliesWhiteouts() throws Exception {
        LayerRecord base = layerManager.submit("base", new java.io.ByteArrayInputStream(
                TarBuilder.create()
                        .directory("etc")
                        .file("etc/shared.txt", "base")
                        .file("etc/remove.txt", "gone")
                        .file("keep.txt", "base")
                        .build()));
        LayerRecord upper = layerManager.submit("upper", new java.io.ByteArrayInputStream(
                TarBuilder.create()
                        .directory("etc")
                        .file("etc/shared.txt", "upper")
                        .file("etc/new.txt", "new")
                        .whiteout("etc", "remove.txt")
                        .build()));

        assertEquals("upper", new String(mergeService.readFile("etc/shared.txt"),
                StandardCharsets.UTF_8));
        assertEquals("new", new String(mergeService.readFile("etc/new.txt"),
                StandardCharsets.UTF_8));
        assertThrows(com.github.guoyyit.imagelayerunpackerjava.error.ApiException.NotFound.class,
                () -> mergeService.readFile("etc/remove.txt"));
        assertEquals("base", new String(mergeService.readFile("keep.txt"),
                StandardCharsets.UTF_8));

        List<String> names = mergeService.listDirectory("/etc").stream()
                .map(MergeService.MergeNode::getPath)
                .toList();
        assertTrue(names.contains("/etc/shared.txt"));
        assertTrue(names.contains("/etc/new.txt"));
        assertTrue(names.contains("/etc/keep.txt") == false);
        assertFalse(names.contains("/etc/remove.txt"));
    }

    @Test
    void opaqueDirectoryHidesLowerSiblings() throws Exception {
        layerManager.submit("base", new java.io.ByteArrayInputStream(
                TarBuilder.create()
                        .directory("var/log")
                        .file("var/log/old.log", "old")
                        .file("var/log/keep.log", "keep-base")
                        .build()));
        layerManager.submit("upper", new java.io.ByteArrayInputStream(
                TarBuilder.create()
                        .directory("var/log")
                        .opaque("var/log")
                        .file("var/log/new.log", "new")
                        .build()));

        List<String> names = mergeService.listDirectory("/var/log").stream()
                .map(MergeService.MergeNode::getPath)
                .toList();
        assertTrue(names.contains("/var/log/new.log"));
        assertFalse(names.contains("/var/log/old.log"));
        assertFalse(names.contains("/var/log/keep.log"));
    }

    @Test
    void deletingLayerExposesLowerContentAgain() throws Exception {
        layerManager.submit("base", new java.io.ByteArrayInputStream(
                TarBuilder.create()
                        .file("a.txt", "base")
                        .build()));
        layerManager.submit("upper", new java.io.ByteArrayInputStream(
                TarBuilder.create().whiteout("", "a.txt").build()));

        assertThrows(com.github.guoyyit.imagelayerunpackerjava.error.ApiException.NotFound.class,
                () -> mergeService.readFile("a.txt"));
        layerManager.delete("upper");
        assertEquals("base", new String(mergeService.readFile("a.txt"),
                StandardCharsets.UTF_8));
    }

    @Test
    void listsVirtualDirectoriesFromFilePaths() throws Exception {
        layerManager.submit("base", new java.io.ByteArrayInputStream(
                TarBuilder.create()
                        .file("deep/nested/file.txt", "x")
                        .build()));
        List<String> root = mergeService.listDirectory("/").stream()
                .map(MergeService.MergeNode::getPath)
                .toList();
        assertTrue(root.contains("/deep"));
        List<String> nested = mergeService.listDirectory("/deep").stream()
                .map(MergeService.MergeNode::getPath)
                .toList();
        assertTrue(nested.contains("/deep/nested"));
    }

    @Test
    void threeLayerWhiteoutAndRecreationIsCoherent() throws Exception {
        layerManager.submit("base", new java.io.ByteArrayInputStream(
                TarBuilder.create()
                        .directory("dir")
                        .file("dir/lower.txt", "lower")
                        .build()));
        layerManager.submit("middle", new java.io.ByteArrayInputStream(
                TarBuilder.create().whiteout("dir", "lower.txt").build()));
        layerManager.submit("upper", new java.io.ByteArrayInputStream(
                TarBuilder.create()
                        .directory("dir")
                        .file("dir/upper.txt", "upper")
                        .build()));

        assertThrows(com.github.guoyyit.imagelayerunpackerjava.error.ApiException.NotFound.class,
                () -> mergeService.readFile("dir/lower.txt"));
        assertEquals("upper", new String(mergeService.readFile("dir/upper.txt"),
                StandardCharsets.UTF_8));

        layerManager.delete("upper");
        assertThrows(com.github.guoyyit.imagelayerunpackerjava.error.ApiException.NotFound.class,
                () -> mergeService.readFile("dir/lower.txt"));
    }
}
