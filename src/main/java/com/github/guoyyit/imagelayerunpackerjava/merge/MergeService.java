package com.github.guoyyit.imagelayerunpackerjava.merge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.stereotype.Service;

import com.github.guoyyit.imagelayerunpackerjava.error.ApiException;
import com.github.guoyyit.imagelayerunpackerjava.layer.LayerManager;
import com.github.guoyyit.imagelayerunpackerjava.model.LayerRecord;

@Service
public class MergeService {

    public static final String TYPE_FILE = "file";
    public static final String TYPE_DIRECTORY = "directory";
    public static final String TYPE_SYMLINK = "symlink";
    public static final String TYPE_OTHER = "other";

    private final LayerManager layerManager;
    private final AtomicReference<MergedView> viewCache = new AtomicReference<>();

    public MergeService(LayerManager layerManager) {
        this.layerManager = layerManager;
    }

    public static class MergeNode {
        private final String path;
        private final String type;
        private final long size;

        public MergeNode(String path, String type, long size) {
            this.path = path;
            this.type = type;
            this.size = size;
        }

        public String getPath() {
            return path;
        }

        public String getType() {
            return type;
        }

        public long getSize() {
            return size;
        }
    }

    private static final class Leaf {
        private final String type;
        private final long size;
        private final Path physical;

        private Leaf(String type, long size, Path physical) {
            this.type = type;
            this.size = size;
            this.physical = physical;
        }
    }

    private static final class Resolved {
        private final String type;
        private final long size;
        private final Path physical;

        private Resolved(String type, long size, Path physical) {
            this.type = type;
            this.size = size;
            this.physical = physical;
        }
    }

    /**
     * Immutable merged view of one registry version. A whiteout hiding a
     * directory covers the directory itself and every descendant path, so the
     * view is computed by sweeping layers from top to bottom while carrying the
     * set of covered prefixes; visible directories are exactly the ancestors of
     * visible leaf entries, which keeps hidden subtrees (and their empty shell
     * ancestors) out of every read path.
     */
    private static final class MergedView {
        private final long version;
        private final Map<String, Leaf> leaves;
        private final Set<String> directories;

        private MergedView(long version, Map<String, Leaf> leaves, Set<String> directories) {
            this.version = version;
            this.leaves = leaves;
            this.directories = directories;
        }
    }

    public List<MergeNode> listDirectory(String path) throws IOException {
        String normalized = normalizeViewPath(path);
        return layerManager.withSnapshot(snapshot -> {
            MergedView view = view(snapshot);
            if (!normalized.isEmpty()) {
                Resolved resolved = resolve(view, normalized);
                if (resolved == null) {
                    throw new ApiException.NotFound("path not found in merged view: " + path);
                }
                if (!TYPE_DIRECTORY.equals(resolved.type)) {
                    throw new ApiException.BadRequest("path is not a directory: " + path);
                }
            }
            return listMergedDirectory(view, normalized);
        });
    }

    public byte[] readFile(String path) throws IOException {
        String normalized = normalizeViewPath(path);
        if (normalized.isEmpty()) {
            throw new ApiException.BadRequest("root is not a file");
        }
        return layerManager.withSnapshot(snapshot -> {
            MergedView view = view(snapshot);
            Resolved resolved = resolve(view, normalized);
            if (resolved == null) {
                throw new ApiException.NotFound("path not found in merged view: " + path);
            }
            if (!TYPE_FILE.equals(resolved.type)) {
                throw new ApiException.BadRequest("path is not a regular file: " + path);
            }
            return Files.readAllBytes(resolved.physical);
        });
    }

    public MergeNode stat(String path) throws IOException {
        String normalized = normalizeViewPath(path);
        return layerManager.withSnapshot(snapshot -> {
            MergedView view = view(snapshot);
            if (normalized.isEmpty()) {
                return new MergeNode("", TYPE_DIRECTORY, 0);
            }
            Resolved resolved = resolve(view, normalized);
            if (resolved == null) {
                throw new ApiException.NotFound("path not found in merged view: " + path);
            }
            return new MergeNode("/" + normalized, resolved.type, resolved.size);
        });
    }

    private MergedView view(LayerManager.Snapshot snapshot) throws IOException {
        long version = snapshot.getVersion();
        MergedView cached = viewCache.get();
        if (cached != null && cached.version == version) {
            return cached;
        }
        MergedView built = buildView(snapshot, version);
        MergedView current = viewCache.get();
        if (current == null || built.version >= current.version) {
            viewCache.compareAndSet(current, built);
        }
        return built;
    }

    private MergedView buildView(LayerManager.Snapshot snapshot, long version) throws IOException {
        List<LayerRecord> layers = sortedLayers(snapshot);
        Map<String, Leaf> leaves = new LinkedHashMap<>();
        Set<String> claimed = new HashSet<>();
        Set<String> covered = new HashSet<>();
        Set<String> opaque = new HashSet<>();
        for (int i = layers.size() - 1; i >= 0; i--) {
            LayerRecord layer = layers.get(i);
            Path layerRoot = snapshot.layerRoot(layer.getId()).toAbsolutePath().normalize();
            for (String path : layer.getPaths()) {
                if (!claimed.add(path)) {
                    continue;
                }
                if (isCovered(path, covered, opaque)) {
                    continue;
                }
                Path physical = layerRoot.resolve(path).normalize();
                if (!physical.startsWith(layerRoot)) {
                    throw new ApiException.UnsafeArchive(
                            "resolved path escapes layer root: " + path);
                }
                if (!Files.exists(physical, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                String type = entryType(physical);
                if (TYPE_DIRECTORY.equals(type) || TYPE_OTHER.equals(type)) {
                    continue;
                }
                long size = TYPE_FILE.equals(type) ? Files.size(physical) : 0;
                leaves.put(path, new Leaf(type, size, physical));
            }
            covered.addAll(layer.getWhiteouts());
            opaque.addAll(layer.getOpaqueDirs());
        }
        Set<String> directories = new HashSet<>();
        for (String leafPath : leaves.keySet()) {
            String parent = parentOf(leafPath);
            while (parent != null && directories.add(parent)) {
                parent = parentOf(parent);
            }
        }
        directories.removeAll(leaves.keySet());
        return new MergedView(version, leaves, directories);
    }

    private static boolean isCovered(String path, Set<String> covered, Set<String> opaque) {
        if (covered.contains(path)) {
            return true;
        }
        String parent = parentOf(path);
        while (parent != null) {
            if (covered.contains(parent) || opaque.contains(parent)) {
                return true;
            }
            parent = parentOf(parent);
        }
        return false;
    }

    private Resolved resolve(MergedView view, String viewPath) {
        Leaf leaf = view.leaves.get(viewPath);
        if (leaf != null) {
            return new Resolved(leaf.type, leaf.size, leaf.physical);
        }
        if (view.directories.contains(viewPath)) {
            return new Resolved(TYPE_DIRECTORY, 0, null);
        }
        return null;
    }

    private List<MergeNode> listMergedDirectory(MergedView view, String dirPath) {
        Map<String, MergeNode> children = new TreeMap<>();
        for (Map.Entry<String, Leaf> entry : view.leaves.entrySet()) {
            String childPath = entry.getKey();
            if (isDirectChild(childPath, dirPath)) {
                Leaf leaf = entry.getValue();
                children.put(childPath, new MergeNode("/" + childPath, leaf.type, leaf.size));
            }
        }
        for (String childPath : view.directories) {
            if (isDirectChild(childPath, dirPath)) {
                children.putIfAbsent(childPath,
                        new MergeNode("/" + childPath, TYPE_DIRECTORY, 0));
            }
        }
        return new ArrayList<>(children.values());
    }

    private static boolean isDirectChild(String path, String dirPath) {
        if (dirPath.isEmpty()) {
            return path.indexOf('/') < 0;
        }
        if (!path.startsWith(dirPath) || path.length() <= dirPath.length()
                || path.charAt(dirPath.length()) != '/') {
            return false;
        }
        return path.indexOf('/', dirPath.length() + 1) < 0;
    }

    private static String parentOf(String path) {
        int index = path.lastIndexOf('/');
        return index < 0 ? null : path.substring(0, index);
    }

    private static String entryType(Path path) {
        if (Files.isSymbolicLink(path)) {
            return TYPE_SYMLINK;
        }
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            return TYPE_DIRECTORY;
        }
        if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            return TYPE_FILE;
        }
        return TYPE_OTHER;
    }

    private List<LayerRecord> sortedLayers(LayerManager.Snapshot snapshot) {
        List<LayerRecord> layers = new ArrayList<>(snapshot.getLayers());
        layers.sort(Comparator.comparingLong(LayerRecord::getSequence));
        return layers;
    }

    static String normalizeViewPath(String path) {
        if (path == null || path.isEmpty() || "/".equals(path)) {
            return "";
        }
        String normalized = path.trim().replace('\\', '/');
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isEmpty()) {
            return "";
        }
        String[] parts = normalized.split("/");
        List<String> resolved = new ArrayList<>();
        for (String part : parts) {
            if (part.isEmpty() || ".".equals(part)) {
                continue;
            }
            if ("..".equals(part)) {
                throw new ApiException.BadRequest("path traversal is not allowed: " + path);
            }
            resolved.add(part);
        }
        return String.join("/", resolved);
    }
}
