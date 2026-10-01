package com.github.guoyyit.imagelayerunpackerjava.view;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Overlay read path. Layers are consulted top-first: an upper entry shadows
 * lower entries with the same name, a whiteout hides the lower entry entirely,
 * and an opaque directory stops the merge for that subtree. Marker files are
 * consumed at unpack time, so nothing {@code .wh.*} is ever exposed here.
 */
public class MergedViewImpl implements MergedView {

    /** One pinned layer: its rootfs plus the whiteout metadata recorded at unpack. */
    public record ViewLayer(Path rootfs, Set<String> whiteouts, Set<String> opaqueDirs) {
    }

    private static final int MAX_SYMLINK_HOPS = 40;

    private final List<ViewLayer> layersTopFirst;
    private final Runnable onClose;
    private final AtomicBoolean closed = new AtomicBoolean();

    public MergedViewImpl(List<ViewLayer> layersBottomFirst, Runnable onClose) {
        this.layersTopFirst = new ArrayList<>(layersBottomFirst);
        java.util.Collections.reverse(this.layersTopFirst);
        this.onClose = onClose;
    }

    @Override
    public List<ViewEntry> list(String path) throws IOException {
        ensureOpen();
        String rel = normalize(path);
        if (rel == null) {
            throw new NoSuchFileException("no such directory in merged view: " + normalizeForMessage(path));
        }
        if (!rel.isEmpty()) {
            Resolved self = resolve(rel);
            if (self == null || !Files.isDirectory(self.location())) {
                throw new NoSuchFileException("no such directory in merged view: " + normalizeForMessage(path));
            }
        }
        Map<String, ViewEntry> merged = new LinkedHashMap<>();
        Set<String> suppressed = new HashSet<>();
        boolean foundDir = false;
        for (ViewLayer layer : layersTopFirst) {
            for (String whiteout : layer.whiteouts()) {
                if (parentOf(whiteout).equals(rel)) {
                    String name = whiteout.substring(whiteout.lastIndexOf('/') + 1);
                    suppressed.add(name);
                    merged.remove(name);
                }
            }
            Path dir = rel.isEmpty() ? layer.rootfs() : layer.rootfs().resolve(rel);
            if (Files.isDirectory(dir)) {
                foundDir = true;
                try (Stream<Path> children = Files.list(dir)) {
                    for (Path child : children.toList()) {
                        String name = child.getFileName().toString();
                        if (name.startsWith(".wh.") || suppressed.contains(name) || merged.containsKey(name)) {
                            continue;
                        }
                        merged.put(name, toEntry(child, joinRel(rel, name)));
                    }
                }
            }
            if (layer.opaqueDirs().contains(rel)) {
                break;
            }
        }
        if (!foundDir) {
            throw new NoSuchFileException("no such directory in merged view: " + normalizeForMessage(path));
        }
        return merged.values().stream()
                .sorted(Comparator.comparing(ViewEntry::name))
                .toList();
    }

    @Override
    public ViewEntry stat(String path) throws IOException {
        ensureOpen();
        String rel = normalize(path);
        if (rel == null) {
            return null;
        }
        if (rel.isEmpty()) {
            return new ViewEntry("", "/", ViewEntry.EntryType.DIRECTORY, 0);
        }
        Resolved resolved = resolve(rel);
        if (resolved == null) {
            return null;
        }
        return toEntry(resolved.location(), rel);
    }

    @Override
    public InputStream openFile(String path) throws IOException {
        ensureOpen();
        String rel = normalize(path);
        if (rel == null) {
            throw new NoSuchFileException("no such file in merged view: " + normalizeForMessage(path));
        }
        Resolved resolved = resolve(rel);
        if (resolved == null || !Files.isRegularFile(resolved.location())) {
            throw new NoSuchFileException("no such file in merged view: " + normalizeForMessage(path));
        }
        // Defense in depth: never serve bytes from outside the layer rootfs.
        Path real = resolved.location().toRealPath();
        Path rootReal = resolved.layer().rootfs().toRealPath();
        if (!real.startsWith(rootReal)) {
            throw new IOException("refusing to read outside layer rootfs: " + normalizeForMessage(path));
        }
        return Files.newInputStream(real);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            onClose.run();
        }
    }

    private void ensureOpen() throws IOException {
        if (closed.get()) {
            throw new IOException("view is closed");
        }
    }

    /** Find the topmost visible location of a path, or null when hidden/absent. */
    private Resolved resolve(String rel) throws IOException {
        int hops = 0;
        String current = rel;
        resolveLoop:
        while (true) {
            for (ViewLayer layer : layersTopFirst) {
                if (isWhitedOut(layer, current)) {
                    return null;
                }
                Path candidate = layer.rootfs().resolve(current);
                if (Files.exists(candidate, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    if (Files.isSymbolicLink(candidate)) {
                        if (++hops > MAX_SYMLINK_HOPS) {
                            throw new IOException("too many symlink levels resolving: " + rel);
                        }
                        String target = Files.readSymbolicLink(candidate).toString().replace('\\', '/');
                        if (target.startsWith("/")) {
                            current = normalize(target);
                        } else {
                            current = normalize(joinRel(parentOf(current), target));
                        }
                        if (current == null) {
                            return null;
                        }
                        continue resolveLoop;
                    }
                    return new Resolved(layer, candidate);
                }
                if (stopsDescent(layer, current)) {
                    break;
                }
            }
            return null;
        }
    }

    /** A whiteout hides its exact target and everything beneath it. */
    private boolean isWhitedOut(ViewLayer layer, String rel) {
        String current = rel;
        while (!current.isEmpty()) {
            if (layer.whiteouts().contains(current)) {
                return true;
            }
            current = parentOf(current);
        }
        return false;
    }

    /** True when an ancestor directory of {@code rel} is opaque in this layer. */
    private boolean stopsDescent(ViewLayer layer, String rel) {
        String parent = parentOf(rel);
        while (!parent.isEmpty()) {
            if (layer.opaqueDirs().contains(parent)) {
                return true;
            }
            parent = parentOf(parent);
        }
        return layer.opaqueDirs().contains("");
    }

    private ViewEntry toEntry(Path location, String rel) throws IOException {
        String name = rel.isEmpty() ? "" : rel.substring(rel.lastIndexOf('/') + 1);
        String viewPath = "/" + rel;
        if (Files.isSymbolicLink(location)) {
            return new ViewEntry(name, viewPath, ViewEntry.EntryType.SYMLINK, 0);
        }
        if (Files.isDirectory(location)) {
            return new ViewEntry(name, viewPath, ViewEntry.EntryType.DIRECTORY, 0);
        }
        return new ViewEntry(name, viewPath, ViewEntry.EntryType.FILE, Files.size(location));
    }

    /**
     * Normalize a view path to a relative form without leading slash.
     * Returns null when the path escapes the root via '..'.
     */
    static String normalize(String path) {
        if (path == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        for (String seg : path.replace('\\', '/').split("/")) {
            if (seg.isEmpty() || seg.equals(".")) {
                continue;
            }
            if (seg.equals("..")) {
                if (parts.isEmpty()) {
                    return null;
                }
                parts.remove(parts.size() - 1);
                continue;
            }
            parts.add(seg);
        }
        return String.join("/", parts);
    }

    private static String parentOf(String rel) {
        int idx = rel.lastIndexOf('/');
        return idx < 0 ? "" : rel.substring(0, idx);
    }

    private static String joinRel(String parent, String name) {
        return parent.isEmpty() ? name : parent + "/" + name;
    }

    private static String normalizeForMessage(String path) {
        return path == null ? "/" : path;
    }

    private record Resolved(ViewLayer layer, Path location) {
    }
}
