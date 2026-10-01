package com.github.guoyyit.imagelayerunpackerjava.unpack;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.github.guoyyit.imagelayerunpackerjava.config.ServiceProperties;
import com.github.guoyyit.imagelayerunpackerjava.error.ApiException;
import com.github.guoyyit.imagelayerunpackerjava.model.UnpackReport;
import com.github.guoyyit.imagelayerunpackerjava.tar.TarReader;
import com.github.guoyyit.imagelayerunpackerjava.tar.TarReader.TarEntry;

@Service
public class UnpackService {

    private static final String WHITEOUT_PREFIX = ".wh.";
    private static final String OPAQUE_MARKER = ".wh..wh..opq";
    private static final int PERMISSION_MASK = 0777;
    private static final int PERMISSION_TRIPLET = 07;

    private final TarReader tarReader = new TarReader();
    private final ServiceProperties properties;

    public UnpackService(ServiceProperties properties) {
        this.properties = properties;
    }

    public UnpackReport extract(Path blob, Path targetRoot) throws IOException {
        List<TarEntry> entries;
        try (InputStream in = Files.newInputStream(blob)) {
            entries = tarReader.collect(in);
        }

        Plan plan = validate(entries);
        Path absoluteRoot = targetRoot.toAbsolutePath().normalize();
        Path parent = absoluteRoot.getParent();
        Files.createDirectories(parent);
        Path pending = parent.resolve(absoluteRoot.getFileName().toString() + ".pending");
        deleteQuietly(pending);
        Files.createDirectories(pending);

        UnpackReport report = new UnpackReport();
        report.setPayloadBytes(Files.size(blob));
        report.setUnpackedBytes(plan.totalBytes);
        report.setWhiteouts(new ArrayList<>(plan.whiteouts));
        report.setOpaqueDirs(new ArrayList<>(plan.opaqueDirs));
        report.setPaths(new ArrayList<>(plan.allPaths));

        try {
            materialize(blob, plan, pending);
            Files.move(pending, absoluteRoot);
        } catch (IOException | RuntimeException e) {
            deleteQuietly(pending);
            deleteQuietly(absoluteRoot);
            throw e;
        }
        return report;
    }

    private static final class Plan {
        private final Map<String, TarEntry> materialEntries = new LinkedHashMap<>();
        private final Set<String> directories = new HashSet<>();
        private final Set<String> fileLike = new HashSet<>();
        private final List<String> whiteouts = new ArrayList<>();
        private final Set<String> opaqueDirs = new HashSet<>();
        private final Set<String> allPaths = new HashSet<>();
        private long totalBytes;
    }

    private Plan validate(List<TarEntry> entries) {
        Plan plan = new Plan();
        Set<String> seen = new HashSet<>();
        Set<String> regularFiles = new HashSet<>();
        Map<String, String> hardLinks = new LinkedHashMap<>();

        for (TarEntry entry : entries) {
            String raw = entry.name;
            String relPath = validateRelativePath(raw);
            boolean explicitDir = entry.typeFlag == TarReader.TYPE_DIRECTORY;
            String cleanPath = relPath.endsWith("/") ? relPath.substring(0, relPath.length() - 1) : relPath;
            String parentPath = parentPath(cleanPath);
            String base = baseName(cleanPath);

            if (base.equals(OPAQUE_MARKER)) {
                requireEmptyWhiteout(entry, cleanPath);
                if (parentPath.isEmpty()) {
                    throw new ApiException.UnsafeArchive(
                            "opaque whiteout marker at root is not allowed");
                }
                ensureParentIsDir(plan, parentPath);
                plan.opaqueDirs.add(parentPath);
                continue;
            }
            if (base.startsWith(WHITEOUT_PREFIX)) {
                requireEmptyWhiteout(entry, cleanPath);
                String removedName = base.substring(WHITEOUT_PREFIX.length());
                if (removedName.isEmpty() || removedName.equals(".") || removedName.equals("..")) {
                    throw new ApiException.UnsafeArchive("invalid whiteout target: " + cleanPath);
                }
                String removed = parentPath.isEmpty() ? removedName : parentPath + "/" + removedName;
                validateRelativePath(removed);
                ensureParentIsDir(plan, parentPath);
                if (plan.allPaths.contains(removed)) {
                    throw new ApiException.UnsafeArchive(
                            "whiteout conflicts with another entry in the same layer: " + removed);
                }
                plan.whiteouts.add(removed);
                continue;
            }

            if (!seen.add(cleanPath + (explicitDir ? "/" : ""))) {
                throw new ApiException.UnsafeArchive("duplicate tar entry: " + cleanPath);
            }
            addParentDirectories(plan, cleanPath);

            switch (entry.typeFlag) {
                case TarReader.TYPE_DIRECTORY -> {
                    if (plan.fileLike.contains(cleanPath)) {
                        throw new ApiException.UnsafeArchive(
                                "directory conflicts with non-directory entry: " + cleanPath);
                    }
                    plan.directories.add(cleanPath);
                    plan.allPaths.add(cleanPath);
                }
                case TarReader.TYPE_REGULAR, TarReader.TYPE_REGULAR_ALT -> {
                    requireParentDir(plan, cleanPath);
                    if (plan.whiteouts.contains(cleanPath)) {
                        throw new ApiException.UnsafeArchive(
                                "entry conflicts with whiteout in the same layer: " + cleanPath);
                    }
                    regularFiles.add(cleanPath);
                    plan.fileLike.add(cleanPath);
                    plan.materialEntries.put(cleanPath, entry);
                    plan.allPaths.add(cleanPath);
                    plan.totalBytes = addBytes(plan, entry.size, cleanPath);
                }
                case TarReader.TYPE_SYMLINK -> {
                    requireParentDir(plan, cleanPath);
                    if (plan.whiteouts.contains(cleanPath)) {
                        throw new ApiException.UnsafeArchive(
                                "entry conflicts with whiteout in the same layer: " + cleanPath);
                    }
                    validateSymlinkTarget(cleanPath, entry.linkTarget);
                    plan.fileLike.add(cleanPath);
                    plan.materialEntries.put(cleanPath, entry);
                    plan.allPaths.add(cleanPath);
                }
                case TarReader.TYPE_HARDLINK -> {
                    requireParentDir(plan, cleanPath);
                    if (plan.whiteouts.contains(cleanPath)) {
                        throw new ApiException.UnsafeArchive(
                                "entry conflicts with whiteout in the same layer: " + cleanPath);
                    }
                    String target = validateRelativePath(entry.linkTarget);
                    plan.fileLike.add(cleanPath);
                    hardLinks.put(cleanPath, target);
                    plan.allPaths.add(cleanPath);
                }
                default -> throw new ApiException.UnsafeArchive(
                        "unsupported tar entry type at " + cleanPath);
            }
        }

        for (Map.Entry<String, String> link : hardLinks.entrySet()) {
            String linkPath = link.getKey();
            String target = link.getValue();
            if (!regularFiles.contains(target)) {
                throw new ApiException.UnsafeArchive(
                        "hard link target must be a regular file present in the same layer: "
                                + linkPath + " -> " + target);
            }
            plan.materialEntries.put(linkPath, plan.materialEntries.get(target));
        }
        return plan;
    }

    private long addBytes(Plan plan, long size, String cleanPath) {
        if (size < 0) {
            throw new ApiException.CorruptArchive("negative entry size: " + cleanPath);
        }
        long total = plan.totalBytes + size;
        if (size > properties.getMaxLayerBytes() || total > properties.getMaxLayerBytes()) {
            throw new ApiException.QuotaExceeded(
                    "unpacked layer size exceeds single layer quota: " + cleanPath);
        }
        return total;
    }

    private static void requireEmptyWhiteout(TarEntry entry, String cleanPath) {
        if (entry.typeFlag != TarReader.TYPE_REGULAR && entry.typeFlag != TarReader.TYPE_REGULAR_ALT) {
            throw new ApiException.UnsafeArchive("whiteout marker must be a regular file: " + cleanPath);
        }
        if (entry.size != 0) {
            throw new ApiException.UnsafeArchive("whiteout marker must be empty: " + cleanPath);
        }
    }

    private static void requireParentDir(Plan plan, String cleanPath) {
        Path parent = Path.of(cleanPath).getParent();
        if (parent == null) {
            return;
        }
        String parentName = parent.toString().replace('\\', '/');
        if (plan.fileLike.contains(parentName) && !plan.directories.contains(parentName)) {
            throw new ApiException.UnsafeArchive(
                    "path component is not a directory: " + parentName);
        }
    }

    private static void ensureParentIsDir(Plan plan, String parentPath) {
        if (!parentPath.isEmpty()) {
            addParentDirectories(plan, parentPath + "/x");
        }
    }

    private void materialize(Path blob, Plan plan, Path pending) throws IOException {
        for (String directory : sortedDirectories(plan)) {
            Files.createDirectories(pending.resolve(directory));
        }

        try (InputStream in = Files.newInputStream(blob)) {
            tarReader.read(in, (entry, content) -> {
                String cleanPath = entry.name.endsWith("/")
                        ? entry.name.substring(0, entry.name.length() - 1)
                        : entry.name;
                String base = baseName(cleanPath);
                if (base.equals(OPAQUE_MARKER) || base.startsWith(WHITEOUT_PREFIX)) {
                    drain(content);
                    return;
                }
                if (entry.typeFlag == TarReader.TYPE_DIRECTORY) {
                    return;
                }
                TarEntry material = plan.materialEntries.get(cleanPath);
                if (material == null) {
                    drain(content);
                    return;
                }
                Path target = pending.resolve(cleanPath).normalize();
                if (!target.startsWith(pending)) {
                    throw new ApiException.UnsafeArchive("path traversal: " + cleanPath);
                }
                switch (entry.typeFlag) {
                    case TarReader.TYPE_REGULAR, TarReader.TYPE_REGULAR_ALT -> {
                        long written = writeFile(content, target, entry.mode);
                        if (written != entry.size) {
                            throw new ApiException.CorruptArchive("size mismatch for " + cleanPath);
                        }
                    }
                    case TarReader.TYPE_SYMLINK ->
                            Files.createSymbolicLink(target, Path.of(entry.linkTarget));
                    case TarReader.TYPE_HARDLINK -> {
                        Path linkTarget = pending.resolve(
                                validateRelativePath(entry.linkTarget)).normalize();
                        if (!linkTarget.startsWith(pending)) {
                            throw new ApiException.UnsafeArchive(
                                    "hard link escapes layer: " + cleanPath);
                        }
                        Files.createLink(target, linkTarget);
                    }
                    default -> throw new ApiException.UnsafeArchive(
                            "unsupported tar entry type at " + cleanPath);
                }
            });
        }
    }

    private static List<String> sortedDirectories(Plan plan) {
        List<String> directories = new ArrayList<>(plan.directories);
        directories.sort(String::compareTo);
        return directories;
    }

    private long writeFile(InputStream content, Path target, int mode) throws IOException {
        long written = 0;
        try (OutputStream out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = content.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                written += n;
                if (written > properties.getMaxLayerBytes()) {
                    throw new ApiException.QuotaExceeded(
                            "unpacked layer size exceeds single layer quota");
                }
            }
        }
        applyMode(target, mode);
        return written;
    }

    private void applyMode(Path target, int mode) {
        try {
            int safeMode = mode & PERMISSION_MASK;
            Set<PosixFilePermission> permissions =
                    PosixFilePermissions.fromString(toModeString(safeMode));
            Files.setPosixFilePermissions(target, permissions);
        } catch (IOException | UnsupportedOperationException ignored) {
        }
    }

    private static String toModeString(int mode) {
        StringBuilder builder = new StringBuilder();
        for (int shift = 6; shift >= 0; shift -= 3) {
            int triplet = (mode >> shift) & PERMISSION_TRIPLET;
            builder.append((triplet & 4) != 0 ? 'r' : '-');
            builder.append((triplet & 2) != 0 ? 'w' : '-');
            builder.append((triplet & 1) != 0 ? 'x' : '-');
        }
        return builder.toString();
    }

    private static void drain(InputStream in) throws IOException {
        byte[] buffer = new byte[4096];
        while (in.read(buffer) != -1) {
        }
    }

    private static void validateSymlinkTarget(String linkPath, String rawTarget) {
        if (rawTarget == null || rawTarget.isEmpty()) {
            throw new ApiException.UnsafeArchive("empty symlink target at " + linkPath);
        }
        if (rawTarget.indexOf('\u0000') >= 0) {
            throw new ApiException.UnsafeArchive("invalid symlink target at " + linkPath);
        }
        if (rawTarget.startsWith("/")) {
            throw new ApiException.UnsafeArchive(
                    "absolute symlink target is not allowed: " + linkPath + " -> " + rawTarget);
        }
        Path resolved = Path.of(parentPath(linkPath)).resolve(rawTarget).normalize();
        if (resolved.getNameCount() == 0 || resolved.getName(0).toString().equals("..")) {
            throw new ApiException.UnsafeArchive(
                    "symlink escapes the layer root: " + linkPath + " -> " + rawTarget);
        }
    }

    private static void addParentDirectories(Plan plan, String cleanPath) {
        Path current = Path.of(cleanPath).getParent();
        while (current != null) {
            String parent = current.toString().replace('\\', '/');
            if (plan.fileLike.contains(parent) && !plan.directories.contains(parent)) {
                throw new ApiException.UnsafeArchive(
                        "path component is not a directory: " + parent);
            }
            plan.directories.add(parent);
            plan.allPaths.add(parent);
            current = current.getParent();
        }
    }

    static String validateRelativePath(String name) {
        if (name == null || name.isEmpty()) {
            throw new ApiException.UnsafeArchive("empty entry name");
        }
        String normalized = name.replace('\\', '/');
        if (normalized.startsWith("/")) {
            throw new ApiException.UnsafeArchive("absolute path is not allowed: " + name);
        }
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        while (normalized.endsWith("/.")) {
            normalized = normalized.substring(0, normalized.length() - 2);
        }
        if (normalized.equals("..") || normalized.startsWith("../")
                || normalized.contains("/../") || normalized.endsWith("/..")) {
            throw new ApiException.UnsafeArchive("path traversal is not allowed: " + name);
        }
        if (normalized.indexOf('\u0000') >= 0) {
            throw new ApiException.UnsafeArchive("nul byte in path: " + name);
        }
        String[] parts = normalized.split("/");
        for (String part : parts) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) {
                throw new ApiException.UnsafeArchive("invalid path component in: " + name);
            }
        }
        return normalized;
    }

    private static String parentPath(String cleanPath) {
        int index = cleanPath.lastIndexOf('/');
        return index < 0 ? "" : cleanPath.substring(0, index);
    }

    private static String baseName(String cleanPath) {
        int index = cleanPath.lastIndexOf('/');
        return index < 0 ? cleanPath : cleanPath.substring(index + 1);
    }

    private static void deleteQuietly(Path path) {
        if (!Files.exists(path) && !Files.isSymbolicLink(path)) {
            return;
        }
        try {
            Files.walkFileTree(path, new java.nio.file.SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(Path file,
                        java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException exc)
                        throws IOException {
                    Files.delete(dir);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
        }
    }
}
