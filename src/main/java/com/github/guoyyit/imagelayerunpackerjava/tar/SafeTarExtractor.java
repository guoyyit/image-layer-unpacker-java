package com.github.guoyyit.imagelayerunpackerjava.tar;

import com.github.guoyyit.imagelayerunpackerjava.layer.QuotaExceededException;
import com.github.guoyyit.imagelayerunpackerjava.layer.UnpackException;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Extracts a tar archive into a staging directory while enforcing the safety
 * policy: no path traversal, no absolute paths, no symlink/hardlink escapes,
 * no device/fifo/socket entries. Whiteout markers ({@code .wh.} prefix) are
 * recorded in the result instead of being materialized.
 *
 * On any failure the staging directory is fully removed: a failed unpack
 * never leaves partial state behind.
 */
public class SafeTarExtractor {

    static final String WHITEOUT_PREFIX = ".wh.";
    static final String OPAQUE_MARKER = ".wh..wh..opq";
    private static final long TAR_BLOCK = 512L;

    private final long maxBytes;
    private final long maxEntries;

    public SafeTarExtractor(long maxBytes, long maxEntries) {
        this.maxBytes = maxBytes;
        this.maxEntries = maxEntries;
    }

    /**
     * Extract {@code tarFile} into {@code stagingDir}.
     *
     * @return statistics plus the whiteout/opaque-dir sets of this layer
     * @throws UnpackException if the archive is corrupt, truncated, unsafe or exceeds quotas
     */
    public UnpackResult extract(Path tarFile, Path stagingDir) throws UnpackException {
        try {
            checkTarTrailer(tarFile);
            Files.createDirectories(stagingDir);
            return doExtract(tarFile, stagingDir);
        } catch (UnpackException e) {
            deleteRecursivelyQuietly(stagingDir);
            throw e;
        } catch (IOException | RuntimeException e) {
            deleteRecursivelyQuietly(stagingDir);
            throw new UnpackException("corrupt or unreadable tar archive: " + e.getMessage(), e);
        }
    }

    /**
     * A well-formed tar is a multiple of 512 bytes and ends with at least two
     * zero blocks. This catches truncated uploads before extraction starts.
     */
    private void checkTarTrailer(Path tarFile) throws IOException, UnpackException {
        long size = Files.size(tarFile);
        if (size == 0 || size % TAR_BLOCK != 0) {
            throw new UnpackException("truncated tar archive: size " + size + " is not a multiple of 512");
        }
        byte[] tail = new byte[(int) Math.min(2 * TAR_BLOCK, size)];
        try (RandomAccessFile raf = new RandomAccessFile(tarFile.toFile(), "r")) {
            raf.seek(size - tail.length);
            raf.readFully(tail);
        }
        for (byte b : tail) {
            if (b != 0) {
                throw new UnpackException("truncated tar archive: missing zero-block trailer");
            }
        }
    }

    private UnpackResult doExtract(Path tarFile, Path stagingDir) throws IOException, UnpackException {
        Set<String> whiteouts = new LinkedHashSet<>();
        Set<String> opaqueDirs = new LinkedHashSet<>();
        long totalBytes = 0;
        long fileCount = 0;
        long entries = 0;
        try (InputStream fileIn = Files.newInputStream(tarFile);
             TarArchiveInputStream tar = new TarArchiveInputStream(fileIn)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                if (++entries > maxEntries) {
                    throw new QuotaExceededException("archive exceeds entry limit of " + maxEntries);
                }
                String rel = sanitizeName(entry.getName());
                if (rel.isEmpty()) {
                    continue;
                }
                String baseName = rel.substring(rel.lastIndexOf('/') + 1);
                if (OPAQUE_MARKER.equals(baseName)) {
                    String dir = parentOf(rel);
                    opaqueDirs.add(dir);
                    materializeParentDirs(stagingDir, rel);
                    continue;
                }
                if (baseName.startsWith(WHITEOUT_PREFIX) && baseName.length() > WHITEOUT_PREFIX.length()) {
                    String victim = joinRel(parentOf(rel), baseName.substring(WHITEOUT_PREFIX.length()));
                    whiteouts.add(victim);
                    materializeParentDirs(stagingDir, rel);
                    continue;
                }
                // A real entry cancels an earlier whiteout for the same path in this layer.
                whiteouts.remove(rel);
                Path target = stagingDir.resolve(rel).normalize();
                if (!target.startsWith(stagingDir)) {
                    throw new UnpackException("entry escapes layer root: " + entry.getName());
                }
                if (entry.isCharacterDevice() || entry.isBlockDevice() || entry.isFIFO()) {
                    throw new UnpackException("device/fifo entries are not allowed: " + entry.getName());
                } else if (entry.isDirectory()) {
                    replaceWithDirectory(target);
                } else if (entry.isSymbolicLink()) {
                    String linkTarget = sanitizeLinkTarget(entry.getLinkName(), parentOf(rel));
                    replaceWithFresh(target);
                    Files.createSymbolicLink(target, Path.of(linkTarget));
                    fileCount++;
                } else if (entry.isLink()) {
                    String linkRel = sanitizeName(entry.getLinkName());
                    Path linkSource = stagingDir.resolve(linkRel).normalize();
                    if (!linkSource.startsWith(stagingDir) || !Files.exists(linkSource)) {
                        throw new UnpackException("hardlink target missing or outside layer: " + entry.getLinkName());
                    }
                    replaceWithFresh(target);
                    Files.createLink(target, linkSource);
                    fileCount++;
                } else if (entry.isFile()) {
                    replaceWithFresh(target);
                    Files.createDirectories(target.getParent());
                    totalBytes += copyCounted(tar, target);
                    if (totalBytes > maxBytes) {
                        throw new QuotaExceededException("layer exceeds size limit of " + maxBytes + " bytes");
                    }
                    fileCount++;
                } else {
                    throw new UnpackException("unsupported tar entry type (device/fifo/socket?): " + entry.getName());
                }
                applyAttributesBestEffort(entry, target);
            }
        }
        return new UnpackResult(stagingDir, List.copyOf(whiteouts), List.copyOf(opaqueDirs), totalBytes, fileCount);
    }

    /**
     * Normalize an entry name to a relative path with '/' separators.
     * Rejects absolute paths and any '..' segment (path traversal).
     */
    static String sanitizeName(String rawName) throws UnpackException {
        if (rawName == null) {
            throw new UnpackException("tar entry with null name");
        }
        String name = rawName.replace('\\', '/');
        if (name.startsWith("/")) {
            throw new UnpackException("absolute path in archive: " + rawName);
        }
        List<String> parts = new ArrayList<>();
        for (String seg : name.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) {
                continue;
            }
            if (seg.equals("..")) {
                throw new UnpackException("path traversal in archive: " + rawName);
            }
            if (seg.indexOf('\0') >= 0) {
                throw new UnpackException("NUL byte in entry name: " + rawName);
            }
            parts.add(seg);
        }
        return String.join("/", parts);
    }

    /**
     * Validate a symlink target: must be relative and, once resolved against
     * the link's parent directory, must stay inside the layer root.
     */
    static String sanitizeLinkTarget(String rawTarget, String parentRel) throws UnpackException {
        if (rawTarget == null || rawTarget.isEmpty()) {
            throw new UnpackException("empty symlink target");
        }
        String target = rawTarget.replace('\\', '/');
        if (target.startsWith("/")) {
            throw new UnpackException("absolute symlink target: " + rawTarget);
        }
        Path resolved = (parentRel.isEmpty() ? Path.of("") : Path.of(parentRel))
                .resolve(target).normalize();
        if (resolved.startsWith("..")) {
            throw new UnpackException("symlink escapes layer root: " + rawTarget);
        }
        return target;
    }

    private static long copyCounted(InputStream in, Path target) throws IOException {
        Files.createFile(target);
        long written = 0;
        byte[] buf = new byte[64 * 1024];
        try (var out = Files.newOutputStream(target)) {
            int n;
            while ((n = in.read(buf)) >= 0) {
                out.write(buf, 0, n);
                written += n;
            }
        }
        return written;
    }

    private static void replaceWithDirectory(Path target) throws IOException {
        if (Files.exists(target) && !Files.isDirectory(target)) {
            deleteRecursively(target);
        }
        Files.createDirectories(target);
    }

    private static void replaceWithFresh(Path target) throws IOException {
        if (Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            deleteRecursively(target);
        }
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
    }

    private static void applyAttributesBestEffort(TarArchiveEntry entry, Path target) {
        try {
            if (entry.getModTime() != null && Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(target)) {
                Files.setLastModifiedTime(target,
                        java.nio.file.attribute.FileTime.fromMillis(entry.getModTime().getTime()));
            }
        } catch (IOException | RuntimeException ignored) {
            // best effort only
        }
    }

    static String parentOf(String rel) {
        int idx = rel.lastIndexOf('/');
        return idx < 0 ? "" : rel.substring(0, idx);
    }

    /**
     * A whiteout/opaque marker lives inside a directory of the upper layer;
     * that directory must exist in the layer rootfs (as in overlayfs), even
     * though the marker file itself is consumed.
     */
    private static void materializeParentDirs(Path stagingDir, String markerRel) throws IOException {
        String parent = parentOf(markerRel);
        if (!parent.isEmpty()) {
            Files.createDirectories(stagingDir.resolve(parent));
        }
    }

    static String joinRel(String parent, String name) {
        return parent.isEmpty() ? name : parent + "/" + name;
    }

    public static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteRecursivelyQuietly(Path dir) {
        try {
            deleteRecursively(dir);
        } catch (IOException ignored) {
            // best effort cleanup
        }
    }
}
