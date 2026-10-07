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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DirectoryWhiteoutTests {

    @TempDir
    Path tempDir;

    private LayerManager layerManager;
    private MergeService mergeService;

    @BeforeEach
    void setUp() {
        ServiceProperties properties = new ServiceProperties();
        properties.setStorageDir(tempDir.resolve("store"));
        properties.setMaxLayerBytes(16L * 1024 * 1024);
        properties.setMaxTotalBytes(128L * 1024 * 1024);
        StorageLayout layout = new StorageLayout(properties);
        UnpackService unpackService = new UnpackService(properties);
        layerManager = new LayerManager(layout, unpackService, properties);
        layerManager.initialize();
        mergeService = new MergeService(layerManager);
    }

    private void submit(String id, TarBuilder builder) {
        layerManager.submit(id, new ByteArrayInputStream(builder.build()));
    }

    private List<String> names(String path) throws Exception {
        return mergeService.listDirectory(path).stream()
                .map(MergeService.MergeNode::getPath)
                .toList();
    }

    @Test
    void directoryWhiteoutHidesWholeSubtreeFromAllEntryPoints() throws Exception {
        submit("base", TarBuilder.create()
                .directory("app")
                .directory("app/config")
                .file("app/config/app.yml", "lower-config")
                .file("app/static/logo.png", "lower-logo")
                .file("top.txt", "top"));
        submit("upper", TarBuilder.create().whiteout("", "app"));

        assertThrows(ApiException.NotFound.class,
                () -> mergeService.readFile("app/config/app.yml"));
        assertThrows(ApiException.NotFound.class,
                () -> mergeService.readFile("app/static/logo.png"));
        assertThrows(ApiException.NotFound.class,
                () -> mergeService.stat("app"));
        assertThrows(ApiException.NotFound.class,
                () -> mergeService.stat("app/config"));
        assertThrows(ApiException.NotFound.class,
                () -> mergeService.stat("app/config/app.yml"));
        assertThrows(ApiException.NotFound.class,
                () -> mergeService.listDirectory("app"));
        assertThrows(ApiException.NotFound.class,
                () -> mergeService.listDirectory("app/config"));

        List<String> root = names("/");
        assertFalse(root.contains("/app"));
        assertTrue(root.contains("/top.txt"));
    }

    @Test
    void explicitAndInferredDirectoriesAreHiddenConsistently() throws Exception {
        submit("base", TarBuilder.create()
                .directory("explicit")
                .directory("explicit/sub")
                .file("explicit/sub/a.txt", "a")
                .file("inferred/sub/b.txt", "b"));
        submit("upper", TarBuilder.create()
                .whiteout("", "explicit")
                .whiteout("", "inferred"));

        for (String hidden : new String[] {
                "explicit", "explicit/sub", "explicit/sub/a.txt",
                "inferred", "inferred/sub", "inferred/sub/b.txt" }) {
            assertThrows(ApiException.NotFound.class, () -> mergeService.stat(hidden),
                    "stat should miss " + hidden);
            assertThrows(ApiException.NotFound.class, () -> mergeService.listDirectory(hidden),
                    "list should miss " + hidden);
        }
        assertThrows(ApiException.NotFound.class,
                () -> mergeService.readFile("explicit/sub/a.txt"));
        assertThrows(ApiException.NotFound.class,
                () -> mergeService.readFile("inferred/sub/b.txt"));
        assertTrue(names("/").isEmpty());
    }

    @Test
    void whiteoutCoversFilesSymlinksAndDirectoryEntriesConsistently() throws Exception {
        submit("base", TarBuilder.create()
                .directory("tree")
                .file("tree/regular.txt", "content")
                .symlink("tree/link", "regular.txt")
                .directory("tree/sub")
                .file("tree/sub/nested.txt", "nested")
                .symlink("toplink", "tree/regular.txt"));
        submit("upper", TarBuilder.create().whiteout("", "tree"));

        assertThrows(ApiException.NotFound.class, () -> mergeService.stat("tree/regular.txt"));
        assertThrows(ApiException.NotFound.class, () -> mergeService.stat("tree/link"));
        assertThrows(ApiException.NotFound.class, () -> mergeService.stat("tree/sub"));
        assertThrows(ApiException.NotFound.class, () -> mergeService.stat("tree/sub/nested.txt"));
        assertThrows(ApiException.NotFound.class, () -> mergeService.stat("tree"));

        // a symlink outside the hidden directory survives even though its
        // target is now hidden; the link itself stays a dangling symlink
        MergeService.MergeNode link = mergeService.stat("toplink");
        assertEquals(MergeService.TYPE_SYMLINK, link.getType());
        assertThrows(ApiException.NotFound.class,
                () -> mergeService.readFile("tree/regular.txt"));
    }

    @Test
    void whiteoutOnSymlinkItselfHidesIt() throws Exception {
        submit("base", TarBuilder.create()
                .file("target.txt", "content")
                .symlink("link", "target.txt"));
        submit("upper", TarBuilder.create().whiteout("", "link"));

        assertThrows(ApiException.NotFound.class, () -> mergeService.stat("link"));
        assertEquals("content", new String(mergeService.readFile("target.txt"),
                StandardCharsets.UTF_8));
    }

    @Test
    void higherLayerRecreationExposesNewContentWithoutLeakingLower() throws Exception {
        submit("base", TarBuilder.create()
                .directory("site")
                .file("site/index.html", "lower-index")
                .file("site/secret.txt", "lower-secret")
                .file("site/assets/logo.png", "lower-logo"));
        submit("middle", TarBuilder.create().whiteout("", "site"));
        submit("upper", TarBuilder.create()
                .directory("site")
                .file("site/index.html", "upper-index")
                .file("site/assets/favicon.ico", "upper-favicon"));

        assertEquals("upper-index", new String(mergeService.readFile("site/index.html"),
                StandardCharsets.UTF_8));
        assertEquals("upper-favicon", new String(mergeService.readFile("site/assets/favicon.ico"),
                StandardCharsets.UTF_8));
        assertThrows(ApiException.NotFound.class,
                () -> mergeService.readFile("site/secret.txt"));
        assertThrows(ApiException.NotFound.class,
                () -> mergeService.readFile("site/assets/logo.png"));

        assertEquals(List.of("/site/assets", "/site/index.html"), names("/site"));
        assertEquals(List.of("/site/assets/favicon.ico"), names("/site/assets"));
        assertEquals(List.of("/site"), names("/").stream()
                .filter(p -> p.startsWith("/site")).toList());
    }

    @Test
    void sameLayerWhiteoutAndRecreationReplacesLowerDirectory() throws Exception {
        submit("base", TarBuilder.create()
                .directory("etc")
                .file("etc/old.conf", "old")
                .file("etc/keep.conf", "keep-lower"));
        submit("upper", TarBuilder.create()
                .whiteout("", "etc")
                .directory("etc")
                .file("etc/fresh.conf", "fresh"));

        assertEquals("fresh", new String(mergeService.readFile("etc/fresh.conf"),
                StandardCharsets.UTF_8));
        assertThrows(ApiException.NotFound.class,
                () -> mergeService.readFile("etc/old.conf"));
        assertThrows(ApiException.NotFound.class,
                () -> mergeService.readFile("etc/keep.conf"));
        assertEquals(List.of("/etc/fresh.conf"), names("/etc"));
    }

    @Test
    void hiddenDirectoryLeavesNoEmptyShellAncestors() throws Exception {
        submit("base", TarBuilder.create()
                .file("a/b/c/deep.txt", "deep")
                .file("a/b/sibling.txt", "sibling"));
        submit("upper", TarBuilder.create().whiteout("a", "b"));

        assertThrows(ApiException.NotFound.class, () -> mergeService.stat("a"));
        assertThrows(ApiException.NotFound.class, () -> mergeService.stat("a/b"));
        assertThrows(ApiException.NotFound.class, () -> mergeService.stat("a/b/c"));
        assertThrows(ApiException.NotFound.class, () -> mergeService.listDirectory("a"));
        assertFalse(names("/").contains("/a"));
        assertTrue(names("/").isEmpty());
    }

    @Test
    void siblingContentSurvivesWhenOnlyOneChildDirectoryIsHidden() throws Exception {
        submit("base", TarBuilder.create()
                .file("root/keep/file.txt", "keep")
                .file("root/drop/gone.txt", "gone"));
        submit("upper", TarBuilder.create().whiteout("root", "drop"));

        assertEquals(List.of("/root"), names("/"));
        assertEquals(List.of("/root/keep"), names("/root"));
        assertEquals("keep", new String(mergeService.readFile("root/keep/file.txt"),
                StandardCharsets.UTF_8));
        assertThrows(ApiException.NotFound.class, () -> mergeService.stat("root/drop"));
    }

    @Test
    void whiteoutMarkersNeverAppearAsViewEntries() throws Exception {
        submit("base", TarBuilder.create()
                .directory("dir")
                .file("dir/file.txt", "x"));
        submit("upper", TarBuilder.create()
                .whiteout("", "dir")
                .whiteout("", "ghost"));

        assertFalse(names("/").stream().anyMatch(p -> p.contains(".wh.")));
        assertThrows(ApiException.NotFound.class, () -> mergeService.stat(".wh.dir"));
        assertThrows(ApiException.NotFound.class, () -> mergeService.stat(".wh.ghost"));
        assertThrows(ApiException.NotFound.class, () -> mergeService.readFile(".wh.dir"));
    }

    @Test
    void deletingWhiteoutLayerRestoresWholeSubtree() throws Exception {
        submit("base", TarBuilder.create()
                .directory("data")
                .file("data/x.txt", "x")
                .file("data/y/z.txt", "z"));
        submit("wiper", TarBuilder.create().whiteout("", "data"));

        assertThrows(ApiException.NotFound.class, () -> mergeService.stat("data"));
        layerManager.delete("wiper");

        assertEquals(MergeService.TYPE_DIRECTORY, mergeService.stat("data").getType());
        assertEquals("x", new String(mergeService.readFile("data/x.txt"),
                StandardCharsets.UTF_8));
        assertEquals("z", new String(mergeService.readFile("data/y/z.txt"),
                StandardCharsets.UTF_8));
        assertEquals(List.of("/data/x.txt", "/data/y"), names("/data"));
    }

    @Test
    void nestedWhiteoutsAcrossManyLayersStayCoherent() throws Exception {
        submit("l1", TarBuilder.create()
                .file("a/b/c/one.txt", "one")
                .file("a/b/two.txt", "two")
                .file("a/root.txt", "root"));
        submit("l2", TarBuilder.create().whiteout("a", "b"));
        submit("l3", TarBuilder.create()
                .directory("a/b")
                .file("a/b/three.txt", "three"));
        submit("l4", TarBuilder.create().whiteout("a/b", "c"));
        submit("l5", TarBuilder.create()
                .directory("a/b/c")
                .file("a/b/c/five.txt", "five"));

        assertThrows(ApiException.NotFound.class,
                () -> mergeService.readFile("a/b/c/one.txt"));
        assertThrows(ApiException.NotFound.class,
                () -> mergeService.readFile("a/b/two.txt"));
        assertEquals("root", new String(mergeService.readFile("a/root.txt"),
                StandardCharsets.UTF_8));
        assertEquals("three", new String(mergeService.readFile("a/b/three.txt"),
                StandardCharsets.UTF_8));
        assertEquals("five", new String(mergeService.readFile("a/b/c/five.txt"),
                StandardCharsets.UTF_8));
        assertEquals(List.of("/a/b/c", "/a/b/three.txt"), names("/a/b"));
        assertEquals(List.of("/a/b/c/five.txt"), names("/a/b/c"));
    }

    @Test
    void deepHierarchyAndManyLayersResolveQuicklyAndCorrectly() throws Exception {
        TarBuilder thick = TarBuilder.create();
        for (int file = 0; file < 3000; file++) {
            thick.file("thick/batch" + (file / 100) + "/f" + file + ".txt", "thick-" + file);
        }
        submit("thick", thick);

        StringBuilder deep = new StringBuilder("d0");
        for (int i = 1; i < 20; i++) {
            deep.append("/d").append(i);
        }
        String deepDir = deep.toString();
        for (int layer = 0; layer < 40; layer++) {
            TarBuilder builder = TarBuilder.create();
            for (int file = 0; file < 60; file++) {
                builder.file("bulk/l" + layer + "/f" + file + ".txt", "l" + layer + "-" + file);
            }
            builder.file(deepDir + "/leaf" + layer + ".txt", "leaf-" + layer);
            submit("layer-" + layer, builder);
        }
        submit("wiper", TarBuilder.create()
                .whiteout("bulk", "l10")
                .whiteout("thick", "batch5"));

        long start = System.nanoTime();
        for (int round = 0; round < 50; round++) {
            assertEquals("leaf-39", new String(
                    mergeService.readFile(deepDir + "/leaf39.txt"), StandardCharsets.UTF_8));
            assertEquals("l20-5", new String(
                    mergeService.readFile("bulk/l20/f5.txt"), StandardCharsets.UTF_8));
            assertEquals("thick-2999", new String(
                    mergeService.readFile("thick/batch29/f2999.txt"), StandardCharsets.UTF_8));
            assertThrows(ApiException.NotFound.class,
                    () -> mergeService.stat("bulk/l10/f0.txt"));
            assertThrows(ApiException.NotFound.class,
                    () -> mergeService.stat("bulk/l10"));
            assertThrows(ApiException.NotFound.class,
                    () -> mergeService.stat("thick/batch5/f500.txt"));
            assertEquals(MergeService.TYPE_DIRECTORY,
                    mergeService.stat(deepDir).getType());
            assertEquals(40, mergeService.listDirectory(deepDir).size());
            assertEquals(1, mergeService.listDirectory("d0/d1/d2/d3/d4/d5/d6/d7/d8/d9"
                    + "/d10/d11/d12/d13/d14/d15/d16/d17/d18").size());
            assertFalse(names("/bulk").contains("/bulk/l10"));
            assertEquals(29, names("/thick").size());
        }
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMillis < 10_000,
                "merged view operations took too long: " + elapsedMillis + "ms");
    }
}
