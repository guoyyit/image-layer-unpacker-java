package com.github.guoyyit.imagelayerunpackerjava.tests;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds tar archives in memory for tests.
 */
class TarBuilder {

    @FunctionalInterface
    private interface IOStep {
        void run(TarArchiveOutputStream out) throws IOException;
    }

    private final List<IOStep> steps = new ArrayList<>();

    static TarBuilder tar() {
        return new TarBuilder();
    }

    TarBuilder file(String name, String content) {
        return file(name, content.getBytes(StandardCharsets.UTF_8));
    }

    TarBuilder file(String name, byte[] content) {
        steps.add(out -> {
            TarArchiveEntry entry = new TarArchiveEntry(name);
            entry.setSize(content.length);
            out.putArchiveEntry(entry);
            out.write(content);
            out.closeArchiveEntry();
        });
        return this;
    }

    /** File entry whose raw name is preserved exactly (e.g. leading slash). */
    TarBuilder rawFile(String name, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        steps.add(out -> {
            TarArchiveEntry entry = new TarArchiveEntry(name, true);
            entry.setSize(bytes.length);
            out.putArchiveEntry(entry);
            out.write(bytes);
            out.closeArchiveEntry();
        });
        return this;
    }

    TarBuilder dir(String name) {
        steps.add(out -> {
            String dirName = name.endsWith("/") ? name : name + "/";
            TarArchiveEntry entry = new TarArchiveEntry(dirName);
            out.putArchiveEntry(entry);
            out.closeArchiveEntry();
        });
        return this;
    }

    TarBuilder symlink(String name, String target) {
        steps.add(out -> {
            TarArchiveEntry entry = new TarArchiveEntry(name, TarArchiveEntry.LF_SYMLINK);
            entry.setLinkName(target);
            out.putArchiveEntry(entry);
            out.closeArchiveEntry();
        });
        return this;
    }

    TarBuilder hardlink(String name, String target) {
        steps.add(out -> {
            TarArchiveEntry entry = new TarArchiveEntry(name, TarArchiveEntry.LF_LINK);
            entry.setLinkName(target);
            out.putArchiveEntry(entry);
            out.closeArchiveEntry();
        });
        return this;
    }

    TarBuilder device(String name) {
        steps.add(out -> {
            TarArchiveEntry entry = new TarArchiveEntry(name, TarArchiveEntry.LF_CHR);
            out.putArchiveEntry(entry);
            out.closeArchiveEntry();
        });
        return this;
    }

    /** Whiteout marker: delete {@code name} from lower layers. */
    TarBuilder whiteout(String name) {
        int idx = name.lastIndexOf('/');
        String marker = idx < 0 ? ".wh." + name
                : name.substring(0, idx + 1) + ".wh." + name.substring(idx + 1);
        return file(marker, new byte[0]);
    }

    /** Opaque-dir marker: the directory hides all lower-layer content. */
    TarBuilder opaqueDir(String dirName) {
        return file(dirName + "/.wh..wh..opq", new byte[0]);
    }

    byte[] build() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (TarArchiveOutputStream out = new TarArchiveOutputStream(buffer)) {
            for (IOStep step : steps) {
                step.run(out);
            }
        }
        return buffer.toByteArray();
    }
}
