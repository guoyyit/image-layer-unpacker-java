package com.github.guoyyit.imagelayerunpackerjava.tests;

import com.github.guoyyit.imagelayerunpackerjava.UnpackerProperties;
import com.github.guoyyit.imagelayerunpackerjava.layer.LayerManager;
import com.github.guoyyit.imagelayerunpackerjava.view.MergedView;
import com.github.guoyyit.imagelayerunpackerjava.view.ViewEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import static com.github.guoyyit.imagelayerunpackerjava.tests.TarBuilder.tar;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Overlay merge semantics: upper overrides lower, directories merge,
 * whiteouts delete, opaque dirs hide lower content.
 */
class MergeSemanticsTest {

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

    private static List<String> names(List<ViewEntry> entries) {
        return entries.stream().map(ViewEntry::name).collect(Collectors.toList());
    }

    @Test
    void upperFileOverridesLowerFile() throws Exception {
        LayerManager manager = newManager();
        manager.unpackLayer("l1", new ByteArrayInputStream(tar()
                .file("app/config.txt", "v1")
                .build()));
        manager.unpackLayer("l2", new ByteArrayInputStream(tar()
                .file("app/config.txt", "v2")
                .build()));
        try (MergedView view = manager.openView()) {
            assertEquals("v2", readString(view, "/app/config.txt"));
        }
    }

    @Test
    void sameNameDirectoriesMerge() throws Exception {
        LayerManager manager = newManager();
        manager.unpackLayer("l1", new ByteArrayInputStream(tar()
                .file("etc/a.conf", "a")
                .file("etc/b.conf", "b-lower")
                .build()));
        manager.unpackLayer("l2", new ByteArrayInputStream(tar()
                .file("etc/b.conf", "b-upper")
                .file("etc/c.conf", "c")
                .build()));
        try (MergedView view = manager.openView()) {
            assertEquals(List.of("a.conf", "b.conf", "c.conf"), names(view.list("/etc")));
            assertEquals("a", readString(view, "/etc/a.conf"));
            assertEquals("b-upper", readString(view, "/etc/b.conf"));
            assertEquals("c", readString(view, "/etc/c.conf"));
        }
    }

    @Test
    void whiteoutRemovesLowerFile() throws Exception {
        LayerManager manager = newManager();
        manager.unpackLayer("l1", new ByteArrayInputStream(tar()
                .file("data/keep.txt", "keep")
                .file("data/drop.txt", "drop")
                .build()));
        manager.unpackLayer("l2", new ByteArrayInputStream(tar()
                .whiteout("data/drop.txt")
                .build()));
        try (MergedView view = manager.openView()) {
            assertEquals(List.of("keep.txt"), names(view.list("/data")));
            assertNull(view.stat("/data/drop.txt"));
            assertEquals("keep", readString(view, "/data/keep.txt"));
        }
    }

    @Test
    void whiteoutRemovesLowerDirectoryRecursively() throws Exception {
        LayerManager manager = newManager();
        manager.unpackLayer("l1", new ByteArrayInputStream(tar()
                .file("opt/pkg/bin/tool", "binary")
                .file("opt/pkg/doc/readme", "doc")
                .build()));
        manager.unpackLayer("l2", new ByteArrayInputStream(tar()
                .whiteout("opt/pkg")
                .build()));
        try (MergedView view = manager.openView()) {
            assertEquals(List.of(), names(view.list("/opt")));
            assertNull(view.stat("/opt/pkg"));
            assertNull(view.stat("/opt/pkg/bin/tool"));
        }
    }

    @Test
    void whiteoutMarkersAreNeverExposed() throws Exception {
        LayerManager manager = newManager();
        manager.unpackLayer("l1", new ByteArrayInputStream(tar()
                .file("x/gone", "1")
                .build()));
        manager.unpackLayer("l2", new ByteArrayInputStream(tar()
                .whiteout("x/gone")
                .opaqueDir("x")
                .build()));
        try (MergedView view = manager.openView()) {
            List<String> rootNames = names(view.list("/x"));
            assertTrue(rootNames.stream().noneMatch(n -> n.startsWith(".wh.")),
                    "whiteout markers must not leak into the view: " + rootNames);
        }
    }

    @Test
    void opaqueDirHidesLowerContentButKeepsUpper() throws Exception {
        LayerManager manager = newManager();
        manager.unpackLayer("l1", new ByteArrayInputStream(tar()
                .file("srv/old1", "1")
                .file("srv/old2", "2")
                .build()));
        manager.unpackLayer("l2", new ByteArrayInputStream(tar()
                .opaqueDir("srv")
                .file("srv/new1", "n1")
                .build()));
        try (MergedView view = manager.openView()) {
            assertEquals(List.of("new1"), names(view.list("/srv")));
            assertNull(view.stat("/srv/old1"));
            assertEquals("n1", readString(view, "/srv/new1"));
        }
    }

    @Test
    void fileReplacedByDirectoryAndViceVersa() throws Exception {
        LayerManager manager = newManager();
        manager.unpackLayer("l1", new ByteArrayInputStream(tar()
                .file("node", "i-am-a-file")
                .build()));
        manager.unpackLayer("l2", new ByteArrayInputStream(tar()
                .dir("node")
                .file("node/child", "c")
                .build()));
        try (MergedView view = manager.openView()) {
            assertEquals(ViewEntry.EntryType.DIRECTORY, view.stat("/node").type());
            assertEquals("c", readString(view, "/node/child"));
        }
    }
}
