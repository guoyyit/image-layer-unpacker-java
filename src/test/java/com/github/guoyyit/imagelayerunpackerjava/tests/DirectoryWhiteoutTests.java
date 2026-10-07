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
        properties.setMaxLayerBytes(4L * 1024 * 1024);
        properties.setMaxTotalBytes(64L * 1024 * 1024);
        StorageLayout layout = new StorageLayout(properties);
        UnpackService unpackService = new UnpackService(properties);
        layerManager = new LayerManager(layout, unpackService, properties);
        layerManager.initialize();
        mergeService = new MergeService(layerManager);
    }

    private void submit(String id, byte[] tar) {
        layerManager.submit(id, new ByteArrayInputStream(tar));
    }

    private List<String> names(String path) throws Exception {
        return mergeService.listDirectory(path).stream()
                .map(MergeService.MergeNode::getPath)
                .toList();
    }

    private String read(String path) throws Exception {
        return new String(mergeService.readFile(path), StandardCharsets.UTF_8);
    }

    private void assertHidden(String path) {
        assertThrows(ApiException.NotFound.class, () -> mergeService.stat(path),
                "stat should not see hidden path: " + path);
        assertThrows(ApiException.NotFound.class, () -> mergeService.readFile(path),
                "read should not see hidden path: " + path);
        assertThrows(ApiException.NotFound.class, () -> mergeService.listDirectory(path),
                "list should not see hidden path: " + path);
    }

    @Test
    void directoryWhiteoutHidesEntireSubtreeAcrossAllReadEntries() throws Exception {
        submit("base", TarBuilder.create()
                .directory("app")
                .directory("app/config")
                .file("app/config/db.yml", "db")
                .file("app/config/nested/deep.txt", "deep")
                .file("app/live.txt", "live")
                .build());
        submit("upper", TarBuilder.create()
                .whiteout("app", "config")
                .build());

        assertHidden("app/config");
        assertHidden("app/config/db.yml");
        assertHidden("app/config/nested");
        assertHidden("app/config/nested/deep.txt");

        List<String> app = names("/app");
        assertEquals(List.of("/app/live.txt"), app,
                "hidden subtree must not leave entries behind");
        assertFalse(app.stream().anyMatch(name -> name.contains(".wh.")),
                "whiteout marker itself must never appear in listings");
        assertEquals("live", read("app/live.txt"));
        assertEquals(MergeService.TYPE_DIRECTORY, mergeService.stat("/app").getType());
    }

    @Test
    void whiteoutMarkerIsNotReadableOrListable() throws Exception {
        submit("base", TarBuilder.create()
                .file("w/target/f.txt", "x")
                .file("w/other.txt", "other")
                .build());
        submit("upper", TarBuilder.create()
                .whiteout("w", "target")
                .build());

        List<String> w = names("/w");
        assertEquals(List.of("/w/other.txt"), w);
        assertHidden("w/target");
        assertHidden("w/target/f.txt");
        assertThrows(ApiException.NotFound.class, () -> mergeService.stat("w/.wh.target"));
    }

    @Test
    void inferredAndExplicitDirectoriesAreHiddenIdentically() throws Exception {
        submit("base", TarBuilder.create()
                .directory("x")
                .directory("x/y")
                .file("x/y/f.txt", "explicit-parent")
                .file("z/y/f.txt", "inferred-parent")
                .build());
        submit("upper", TarBuilder.create()
                .whiteout("x", "y")
                .whiteout("z", "y")
                .build());

        assertHidden("x/y");
        assertHidden("x/y/f.txt");
        assertHidden("z/y");
        assertHidden("z/y/f.txt");
        assertHidden("x");
        assertHidden("z");
        assertTrue(names("/").isEmpty(),
                "root listing must not keep empty shells of hidden subtrees");
    }

    @Test
    void emptyShellAncestorsArePrunedOnlyWhenNoVisibleChildrenRemain() throws Exception {
        submit("base", TarBuilder.create()
                .file("a/b/c.txt", "c")
                .file("a/keep.txt", "keep")
                .file("m/n/o.txt", "o")
                .build());
        submit("upper", TarBuilder.create()
                .whiteout("a", "b")
                .whiteout("m", "n")
                .build());

        assertEquals(List.of("/a/keep.txt"), names("/a"));
        assertEquals(MergeService.TYPE_DIRECTORY, mergeService.stat("/a").getType());
        assertHidden("a/b");
        assertHidden("a/b/c.txt");

        assertHidden("m");
        assertHidden("m/n");
        assertHidden("m/n/o.txt");
        assertEquals(List.of("/a"), names("/"));
    }

    @Test
    void symlinksInsideHiddenSubtreeAndSymlinkTargetsFollowTheSameRule() throws Exception {
        submit("base", TarBuilder.create()
                .file("data/secret.txt", "secret")
                .symlink("data/link", "secret.txt")
                .file("gone/dir/real.txt", "real")
                .symlink("gone/dir/inner", "real.txt")
                .file("t/target.txt", "target")
                .symlink("t/link", "target.txt")
                .build());
        submit("upper", TarBuilder.create()
                .whiteout("data", "secret.txt")
                .whiteout("", "gone")
                .whiteout("t", "link")
                .build());

        assertEquals(MergeService.TYPE_SYMLINK, mergeService.stat("/data/link").getType(),
                "a symlink whose target is hidden stays a visible symlink entry");
        assertHidden("data/secret.txt");
        assertEquals(List.of("/data/link"), names("/data"));

        assertHidden("gone");
        assertHidden("gone/dir/real.txt");
        assertHidden("gone/dir/inner");

        assertHidden("t/link");
        assertEquals("target", read("t/target.txt"));
    }

    @Test
    void recreationInHigherLayerRestoresVisibilityWithoutLeakingLowerContent() throws Exception {
        submit("base", TarBuilder.create()
                .file("dir/old.txt", "old")
                .file("dir/sub/deep.txt", "deep")
                .build());
        submit("middle", TarBuilder.create()
                .whiteout("", "dir")
                .build());
        submit("top", TarBuilder.create()
                .file("dir/new.txt", "new")
                .file("dir/sub/fresh.txt", "fresh")
                .build());

        assertHidden("dir/old.txt");
        assertHidden("dir/sub/deep.txt");
        assertEquals("new", read("dir/new.txt"));
        assertEquals("fresh", read("dir/sub/fresh.txt"));
        assertEquals(List.of("/dir/new.txt", "/dir/sub"), names("/dir"));
        assertEquals(List.of("/dir/sub/fresh.txt"), names("/dir/sub"));

        layerManager.delete("top");
        assertHidden("dir");
        assertHidden("dir/old.txt");
        assertHidden("dir/new.txt");
        assertHidden("dir/sub/deep.txt");

        layerManager.delete("middle");
        assertEquals("old", read("dir/old.txt"));
        assertEquals("deep", read("dir/sub/deep.txt"));
        assertHidden("dir/new.txt");
    }

    @Test
    void sameLayerMayRecreatePathsCoveredByItsOwnWhiteout() throws Exception {
        submit("base", TarBuilder.create()
                .file("dir/old.txt", "old")
                .build());
        submit("upper", TarBuilder.create()
                .whiteout("", "dir")
                .file("dir/new.txt", "new")
                .build());

        assertHidden("dir/old.txt");
        assertEquals("new", read("dir/new.txt"));
        assertEquals(List.of("/dir/new.txt"), names("/dir"));
    }

    @Test
    void fileWhiteoutAndOpaqueSemanticsArePreserved() throws Exception {
        submit("base", TarBuilder.create()
                .file("d/a.txt", "a")
                .file("d/b.txt", "b")
                .file("o/lower.txt", "lower")
                .build());
        submit("upper", TarBuilder.create()
                .whiteout("d", "a.txt")
                .opaque("o")
                .file("o/upper.txt", "upper")
                .build());

        assertHidden("d/a.txt");
        assertEquals("b", read("d/b.txt"));
        assertEquals(List.of("/d/b.txt"), names("/d"));

        assertHidden("o/lower.txt");
        assertEquals("upper", read("o/upper.txt"));
        assertEquals(MergeService.TYPE_DIRECTORY, mergeService.stat("/o").getType(),
                "opaque marker hides lower content but never the directory itself");
        assertEquals(List.of("/o/upper.txt"), names("/o"));
    }

    @Test
    void deepHierarchiesAreHandledWithoutRecursionFailures() throws Exception {
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            deep.append("d/");
        }
        String deepDir = deep.substring(0, deep.length() - 1);
        String deepFile = deepDir + "/f.txt";
        String levelTwoDir = "d/d";

        submit("base", TarBuilder.create()
                .file(deepFile, "bottom")
                .file("shallow.txt", "shallow")
                .build());

        assertEquals("bottom", read(deepFile));
        assertEquals(MergeService.TYPE_DIRECTORY, mergeService.stat("/" + deepDir).getType());

        submit("upper", TarBuilder.create()
                .whiteout("d", "d")
                .build());

        assertHidden(levelTwoDir);
        assertHidden(deepDir);
        assertHidden(deepFile);
        assertHidden("d");
        assertEquals("shallow", read("shallow.txt"));
        assertEquals(List.of("/shallow.txt"), names("/"),
                "once every descendant is hidden, the whole empty chain is pruned");
    }
}
