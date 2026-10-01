package com.github.guoyyit.imagelayerunpackerjava.tests;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal ustar archive builder used by tests.
 */
public final class TarBuilder {

    private static final int BLOCK = 512;

    private final List<Entry> entries = new ArrayList<>();
    private boolean validTerminator = true;
    private boolean validChecksum = true;
    private boolean posixMagic = false;

    private static final class Entry {
        private final String name;
        private final byte[] content;
        private final byte typeFlag;
        private final int mode;
        private final String linkTarget;

        private Entry(String name, byte[] content, byte typeFlag, int mode, String linkTarget) {
            this.name = name;
            this.content = content;
            this.typeFlag = typeFlag;
            this.mode = mode;
            this.linkTarget = linkTarget;
        }
    }

    public static TarBuilder create() {
        return new TarBuilder();
    }

    public TarBuilder file(String name, String content) {
        return file(name, content.getBytes(StandardCharsets.UTF_8));
    }

    public TarBuilder file(String name, byte[] content) {
        entries.add(new Entry(name, content, (byte) '0', 0644, ""));
        return this;
    }

    public TarBuilder executable(String name, String content) {
        entries.add(new Entry(name, content.getBytes(StandardCharsets.UTF_8), (byte) '0', 0755, ""));
        return this;
    }

    public TarBuilder directory(String name) {
        String normalized = name.endsWith("/") ? name : name + "/";
        entries.add(new Entry(normalized, new byte[0], (byte) '5', 0755, ""));
        return this;
    }

    public TarBuilder symlink(String name, String target) {
        entries.add(new Entry(name, new byte[0], (byte) '2', 0777, target));
        return this;
    }

    public TarBuilder hardlink(String name, String target) {
        entries.add(new Entry(name, new byte[0], (byte) '1', 0644, target));
        return this;
    }

    public TarBuilder whiteout(String parentDir, String removedName) {
        String name = parentDir.isEmpty()
                ? ".wh." + removedName
                : parentDir + "/.wh." + removedName;
        entries.add(new Entry(name, new byte[0], (byte) '0', 0644, ""));
        return this;
    }

    public TarBuilder opaque(String parentDir) {
        entries.add(new Entry(parentDir + "/.wh..wh..opq", new byte[0], (byte) '0', 0644, ""));
        return this;
    }

    public TarBuilder withoutTerminator() {
        this.validTerminator = false;
        return this;
    }

    public TarBuilder withBadChecksum() {
        this.validChecksum = false;
        return this;
    }

    public TarBuilder posixMagic() {
        this.posixMagic = true;
        return this;
    }

    public TarBuilder rawEntry(byte[] headerAndContent) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(headerAndContent);
        return this;
    }

    public byte[] build() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Entry entry : entries) {
            writeEntry(out, entry);
        }
        if (validTerminator) {
            out.writeBytes(new byte[BLOCK * 2]);
        }
        return out.toByteArray();
    }

    public byte[] truncated(long bytes) {
        byte[] full = build();
        int length = (int) Math.min(bytes, full.length);
        byte[] result = new byte[length];
        System.arraycopy(full, 0, result, 0, length);
        return result;
    }

    private void writeEntry(ByteArrayOutputStream out, Entry entry) {
        byte[] header = new byte[BLOCK];
        putString(header, 0, 100, entry.name);
        putOctal(header, 100, 8, entry.mode & 0777);
        putOctal(header, 108, 8, 0);
        putOctal(header, 116, 8, 0);
        putOctal(header, 124, 12, entry.content.length);
        putOctal(header, 136, 12, 0);
        if (validChecksum) {
            for (int i = 148; i < 156; i++) {
                header[i] = ' ';
            }
        }
        header[156] = entry.typeFlag;
        putString(header, 157, 100, entry.linkTarget);
        if (posixMagic) {
            putString(header, 257, 5, "ustar");
            header[262] = 0;
            putString(header, 263, 2, "00");
        } else {
            putString(header, 257, 6, "ustar ");
            header[263] = ' ';
        }
        putOctal(header, 264, 2, 0);
        putString(header, 265, 32, "");
        putString(header, 297, 32, "");
        putOctal(header, 329, 8, 0);
        putOctal(header, 337, 8, 0);
        if (validChecksum) {
            long checksum = 0;
            for (byte b : header) {
                checksum += b & 0xFF;
            }
            putOctal(header, 148, 7, checksum);
            header[155] = ' ';
        } else {
            putOctal(header, 148, 7, 12345);
            header[155] = ' ';
        }
        out.writeBytes(header);
        out.writeBytes(entry.content);
        int padding = (BLOCK - (entry.content.length % BLOCK)) % BLOCK;
        out.writeBytes(new byte[padding]);
    }

    private static void putString(byte[] buffer, int offset, int length, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        int count = Math.min(bytes.length, length);
        System.arraycopy(bytes, 0, buffer, offset, count);
    }

    private static void putOctal(byte[] buffer, int offset, int length, long value) {
        String octal = Long.toOctalString(value);
        String padded;
        if (octal.length() >= length) {
            padded = octal.substring(octal.length() - length);
        } else {
            padded = "0".repeat(length - octal.length()) + octal;
        }
        putString(buffer, offset, length, padded);
    }
}
