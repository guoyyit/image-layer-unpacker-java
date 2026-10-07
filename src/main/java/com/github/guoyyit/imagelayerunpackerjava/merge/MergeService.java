package com.github.guoyyit.imagelayerunpackerjava.merge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

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

    public List<MergeNode> listDirectory(String path) throws IOException {
        String normalized = normalizeViewPath(path);
        return layerManager.withSnapshot(snapshot -> {
            List<LayerRecord> layers = sortedLayers(snapshot);
            MergedView view = MergedView.build(layers);
            if (!normalized.isEmpty()) {
                Resolved resolved = resolve(snapshot, layers, view, normalized);
                if (resolved == null) {
                    throw new ApiException.NotFound("path not found in merged view: " + path);
                }
                if (!TYPE_DIRECTORY.equals(resolved.type)) {
                    throw new ApiException.BadRequest("path is not a directory: " + path);
                }
            }
            return listMergedDirectory(snapshot, layers, view, normalized);
        });
    }

    public byte[] readFile(String path) throws IOException {
        String normalized = normalizeViewPath(path);
        if (normalized.isEmpty()) {
            throw new ApiException.BadRequest("root is not a file");
        }
        return layerManager.withSnapshot(snapshot -> {
            List<LayerRecord> layers = sortedLayers(snapshot);
            MergedView view = MergedView.build(layers);
            Resolved resolved = resolve(snapshot, layers, view, normalized);
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
            List<LayerRecord> layers = sortedLayers(snapshot);
            MergedView view = MergedView.build(layers);
            if (normalized.isEmpty()) {
                return new MergeNode("", TYPE_DIRECTORY, 0);
            }
            Resolved resolved = resolve(snapshot, layers, view, normalized);
            if (resolved == null) {
                throw new ApiException.NotFound("path not found in merged view: " + path);
            }
            return new MergeNode("/" + normalized, resolved.type, resolved.size);
        });
    }

    private List<MergeNode> listMergedDirectory(LayerManager.Snapshot snapshot,
            List<LayerRecord> layers, MergedView view, String dirPath) throws IOException {
        TreeSet<String> childNames = new TreeSet<>();
        String childPrefix = dirPath.isEmpty() ? "" : dirPath + "/";
        for (String path : view.paths()) {
            if (!path.startsWith(childPrefix) || path.length() <= childPrefix.length()) {
                continue;
            }
            String rest = path.substring(childPrefix.length());
            int slash = rest.indexOf('/');
            String childName = slash < 0 ? rest : rest.substring(0, slash);
            if (!childName.startsWith(".wh.")) {
                childNames.add(childName);
            }
        }
        List<MergeNode> result = new ArrayList<>();
        for (String childName : childNames) {
            String childPath = dirPath.isEmpty() ? childName : dirPath + "/" + childName;
            Resolved resolved = resolve(snapshot, layers, view, childPath);
            if (resolved != null) {
                result.add(new MergeNode("/" + childPath, resolved.type, resolved.size));
            }
        }
        return result;
    }

    private Resolved resolve(LayerManager.Snapshot snapshot, List<LayerRecord> layers,
            MergedView view, String viewPath) throws IOException {
        Integer ownerIndex = view.owner(viewPath);
        if (ownerIndex == null) {
            return null;
        }
        LayerRecord ownerLayer = layers.get(ownerIndex);
        Path layerRoot = snapshot.layerRoot(ownerLayer.getId()).toAbsolutePath().normalize();
        Path physical = layerRoot.resolve(viewPath).normalize();
        if (!physical.startsWith(layerRoot)) {
            throw new ApiException.UnsafeArchive(
                    "resolved path escapes layer root: " + viewPath);
        }
        String type;
        long size = 0;
        Path physicalResult = physical;
        if (Files.exists(physical, LinkOption.NOFOLLOW_LINKS)) {
            type = entryType(physical);
            if (TYPE_OTHER.equals(type)) {
                return null;
            }
            if (TYPE_FILE.equals(type)) {
                size = Files.size(physical);
            }
        } else {
            type = TYPE_DIRECTORY;
            physicalResult = null;
        }
        if (TYPE_DIRECTORY.equals(type) && !view.hasVisibleDescendant(viewPath)) {
            return null;
        }
        return new Resolved(type, size, physicalResult);
    }

    /**
     * Visibility model computed once per request from a consistent snapshot of the
     * layer stack. A whiteout entry hides not only its exact target path but the
     * whole subtree beneath it in every lower layer; an opaque marker hides only
     * the strict descendants of its directory in lower layers. Markers of a layer
     * never hide entries of the same layer, so a higher (or the same) layer may
     * recreate a path that a lower-aimed whiteout removed.
     */
    private static final class MergedView {
        private final Map<String, Integer> ownerByPath;
        private Set<String> nonEmptyDirs;

        private MergedView(Map<String, Integer> ownerByPath) {
            this.ownerByPath = ownerByPath;
        }

        static MergedView build(List<LayerRecord> layers) {
            Map<String, Integer> owner = new HashMap<>();
            Set<String> whiteouts = new HashSet<>();
            Set<String> opaqueDirs = new HashSet<>();
            for (int i = layers.size() - 1; i >= 0; i--) {
                LayerRecord layer = layers.get(i);
                for (String path : layer.getPaths()) {
                    if (!owner.containsKey(path) && !isHidden(whiteouts, opaqueDirs, path)) {
                        owner.put(path, i);
                    }
                }
                whiteouts.addAll(layer.getWhiteouts());
                opaqueDirs.addAll(layer.getOpaqueDirs());
            }
            return new MergedView(owner);
        }

        private static boolean isHidden(Set<String> whiteouts, Set<String> opaqueDirs,
                String path) {
            String current = path;
            while (true) {
                if (whiteouts.contains(current)) {
                    return true;
                }
                int slash = current.lastIndexOf('/');
                if (slash < 0) {
                    break;
                }
                current = current.substring(0, slash);
            }
            current = path;
            while (true) {
                int slash = current.lastIndexOf('/');
                if (slash < 0) {
                    break;
                }
                current = current.substring(0, slash);
                if (opaqueDirs.contains(current)) {
                    return true;
                }
            }
            return false;
        }

        Integer owner(String path) {
            return ownerByPath.get(path);
        }

        Set<String> paths() {
            return ownerByPath.keySet();
        }

        boolean hasVisibleDescendant(String dirPath) {
            if (nonEmptyDirs == null) {
                Set<String> dirs = new HashSet<>();
                for (String path : ownerByPath.keySet()) {
                    String current = path;
                    while (true) {
                        int slash = current.lastIndexOf('/');
                        if (slash < 0) {
                            break;
                        }
                        current = current.substring(0, slash);
                        if (!dirs.add(current)) {
                            break;
                        }
                    }
                }
                nonEmptyDirs = dirs;
            }
            return nonEmptyDirs.contains(dirPath);
        }
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
