package com.github.guoyyit.imagelayerunpackerjava.merge;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
            if (!normalized.isEmpty()) {
                Resolved resolved = resolve(snapshot, layers, normalized);
                if (resolved == null) {
                    throw new ApiException.NotFound("path not found in merged view: " + path);
                }
                if (!TYPE_DIRECTORY.equals(resolved.type)) {
                    throw new ApiException.BadRequest("path is not a directory: " + path);
                }
            }
            return listMergedDirectory(snapshot, layers, normalized);
        });
    }

    public byte[] readFile(String path) throws IOException {
        String normalized = normalizeViewPath(path);
        if (normalized.isEmpty()) {
            throw new ApiException.BadRequest("root is not a file");
        }
        return layerManager.withSnapshot(snapshot -> {
            List<LayerRecord> layers = sortedLayers(snapshot);
            Resolved resolved = resolve(snapshot, layers, normalized);
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
            if (normalized.isEmpty()) {
                return new MergeNode("", TYPE_DIRECTORY, 0);
            }
            Resolved resolved = resolve(snapshot, layers, normalized);
            if (resolved == null) {
                throw new ApiException.NotFound("path not found in merged view: " + path);
            }
            return new MergeNode("/" + normalized, resolved.type, resolved.size);
        });
    }

    private List<MergeNode> listMergedDirectory(LayerManager.Snapshot snapshot,
            List<LayerRecord> layers, String dirPath) throws IOException {
        java.util.TreeSet<String> childNames = new java.util.TreeSet<>();
        String childPrefix = dirPath.isEmpty() ? "" : dirPath + "/";
        for (LayerRecord layer : layers) {
            for (String path : layer.getPaths()) {
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
        }
        List<MergeNode> result = new ArrayList<>();
        for (String childName : childNames) {
            String childPath = dirPath.isEmpty() ? childName : dirPath + "/" + childName;
            Resolved resolved = resolve(snapshot, layers, childPath);
            if (resolved != null) {
                result.add(new MergeNode("/" + childPath, resolved.type, resolved.size));
            }
        }
        return result;
    }

    private Resolved resolve(LayerManager.Snapshot snapshot, List<LayerRecord> layers,
            String viewPath) throws IOException {
        String prefix = viewPath + "/";
        int owner = -1;
        for (int i = layers.size() - 1; i >= 0; i--) {
            LayerRecord layer = layers.get(i);
            if (removedByLayer(layer, viewPath)) {
                return null;
            }
            Path layerRoot = snapshot.layerRoot(layer.getId());
            Path physical = layerRoot.resolve(viewPath);
            if (Files.exists(physical, LinkOption.NOFOLLOW_LINKS)) {
                owner = i;
                break;
            }
            if (layerContainsDirectoryEntry(layer, viewPath, prefix)) {
                owner = i;
                break;
            }
        }
        if (owner < 0) {
            return null;
        }
        for (int i = owner + 1; i < layers.size(); i++) {
            if (opaqueIn(layers.get(i), viewPath)) {
                return null;
            }
        }
        LayerRecord ownerLayer = layers.get(owner);
        Path layerRoot = snapshot.layerRoot(ownerLayer.getId()).toAbsolutePath().normalize();
        Path physical = layerRoot.resolve(viewPath).normalize();
        if (!physical.startsWith(layerRoot)) {
            throw new ApiException.UnsafeArchive(
                    "resolved path escapes layer root: " + viewPath);
        }
        if (Files.exists(physical, LinkOption.NOFOLLOW_LINKS)) {
                String type = entryType(physical);
                if (TYPE_OTHER.equals(type)) {
                    return null;
                }
                long size = TYPE_FILE.equals(type)
                        && Files.isRegularFile(physical, LinkOption.NOFOLLOW_LINKS)
                                ? Files.size(physical)
                                : 0;
                return new Resolved(type, size, physical);
        }
        return new Resolved(TYPE_DIRECTORY, 0, null);
    }

    private boolean layerContainsDirectoryEntry(LayerRecord layer, String dirPath, String prefix) {
        List<String> paths = layer.getPaths();
        if (paths.contains(dirPath)) {
            return true;
        }
        for (String candidate : paths) {
            if (candidate.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean removedByLayer(LayerRecord layer, String path) {
        if (layer.getWhiteouts().contains(path)) {
            return true;
        }
        return false;
    }

    private boolean opaqueIn(LayerRecord layer, String childPath) {
        for (String opaqueDir : layer.getOpaqueDirs()) {
            if (childPath.startsWith(opaqueDir + "/")) {
                return true;
            }
        }
        return false;
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
