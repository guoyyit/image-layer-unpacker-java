package com.github.guoyyit.imagelayerunpackerjava.tar;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.github.guoyyit.imagelayerunpackerjava.error.ApiException;

public class TarReader {

    public static final byte TYPE_REGULAR = '0';
    public static final byte TYPE_REGULAR_ALT = 0;
    public static final byte TYPE_HARDLINK = '1';
    public static final byte TYPE_SYMLINK = '2';
    public static final byte TYPE_DIRECTORY = '5';

    private static final int BLOCK = 512;

    public static class TarEntry {
        public String name;
        public int mode;
        public long size;
        public byte typeFlag;
        public String linkTarget;
    }

    public interface EntryHandler {
        void handle(TarEntry entry, InputStream content) throws IOException;
    }

    public List<TarEntry> collect(InputStream in) throws IOException {
        List<TarEntry> entries = new ArrayList<>();
        read(in, (entry, content) -> {
            entries.add(entry);
            if (content instanceof BoundedEntryStream bounded) {
                bounded.drain();
            }
        });
        return entries;
    }

    public void read(InputStream in, EntryHandler handler) throws IOException {
        byte[] block = new byte[BLOCK];
        boolean sawEntry = false;
        while (true) {
            readFully(in, block, BLOCK);
            if (isZeroBlock(block)) {
                int second = readFully(in, block, BLOCK);
                if (second < BLOCK || !isZeroBlock(block)) {
                    throw new ApiException.CorruptArchive(
                            "corrupt tar archive: missing second zero block at end");
                }
                if (!sawEntry) {
                    throw new ApiException.CorruptArchive(
                            "corrupt tar archive: archive contains no entries");
                }
                return;
            }
            sawEntry = true;
            TarEntry entry = parseHeader(block);
            BoundedEntryStream content = new BoundedEntryStream(in, entry.size);
            handler.handle(entry, content);
            content.drain();
            long skip = entry.size % BLOCK;
            if (skip != 0) {
                readFully(in, block, (int) (BLOCK - skip));
            }
        }
    }

    private static TarEntry parseHeader(byte[] block) {
        verifyChecksum(block);
        String magic = fixedString(block, 257, 5);
        if (!"ustar".equals(magic)) {
            throw new ApiException.CorruptArchive(
                    "corrupt tar archive: unsupported magic '" + escape(magic) + "', only ustar is supported");
        }
        byte typeFlag = block[156];
        switch (typeFlag) {
            case TYPE_REGULAR, TYPE_REGULAR_ALT, TYPE_HARDLINK, TYPE_SYMLINK, TYPE_DIRECTORY -> {
            }
            default -> throw new ApiException.UnsafeArchive(
                    "unsupported tar entry type '" + (char) typeFlag + "'");
        }
        TarEntry entry = new TarEntry();
        entry.typeFlag = typeFlag;
        entry.mode = (int) parseOctal(block, 100, 8, "mode");
        String prefix = fixedString(block, 345, 155);
        String name = fixedString(block, 0, 100);
        if (!prefix.isEmpty()) {
            entry.name = normalizeName(prefix + "/" + name);
        } else {
            entry.name = normalizeName(name);
        }
        if (entry.name.isEmpty()) {
            throw new ApiException.CorruptArchive("corrupt tar archive: empty entry name");
        }
        entry.size = parseOctal(block, 124, 12, "size");
        if (entry.size < 0) {
            throw new ApiException.CorruptArchive("corrupt tar archive: negative entry size");
        }
        if (typeFlag == TYPE_DIRECTORY && entry.size != 0) {
            throw new ApiException.CorruptArchive(
                    "corrupt tar archive: directory entry with non-zero size");
        }
        entry.linkTarget = (typeFlag == TYPE_SYMLINK || typeFlag == TYPE_HARDLINK)
                ? fixedString(block, 157, 100)
                : "";
        if ((typeFlag == TYPE_SYMLINK || typeFlag == TYPE_HARDLINK) && entry.linkTarget.isEmpty()) {
            throw new ApiException.CorruptArchive("corrupt tar archive: link entry without target");
        }
        return entry;
    }

    private static void verifyChecksum(byte[] block) {
        long unsigned = 0;
        long signed = 0;
        for (int i = 0; i < BLOCK; i++) {
            int value = block[i] & 0xFF;
            unsigned += (i >= 148 && i < 156) ? 32 : value;
            int signedValue = block[i];
            signed += (i >= 148 && i < 156) ? 32 : signedValue;
        }
        long stored = parseOctalLoose(block, 148, 8);
        if (stored != unsigned && stored != signed) {
            throw new ApiException.CorruptArchive(
                    "corrupt tar archive: header checksum mismatch for '"
                            + escape(fixedString(block, 0, 100)) + "'");
        }
    }

    private static long parseOctalLoose(byte[] block, int offset, int length) {
        long value = 0;
        boolean sawDigit = false;
        for (int i = offset; i < offset + length; i++) {
            byte b = block[i];
            if (b == 0 || b == ' ') {
                if (sawDigit) {
                    break;
                }
                continue;
            }
            if (b < '0' || b > '7') {
                throw new ApiException.CorruptArchive(
                        "corrupt tar archive: invalid octal field");
            }
            sawDigit = true;
            value = (value << 3) + (b - '0');
        }
        return value;
    }

    private static long parseOctal(byte[] block, int offset, int length, String field) {
        boolean sawDigit = false;
        for (int i = offset; i < offset + length - 1; i++) {
            byte b = block[i];
            if (b == 0 || b == ' ') {
                if (sawDigit) {
                    for (int j = i; j < offset + length - 1; j++) {
                        if (block[j] != 0 && block[j] != ' ') {
                            throw new ApiException.CorruptArchive(
                                    "corrupt tar archive: invalid " + field + " field");
                        }
                    }
                    break;
                }
                continue;
            }
            if (b < '0' || b > '7') {
                throw new ApiException.CorruptArchive(
                        "corrupt tar archive: invalid " + field + " field");
            }
            sawDigit = true;
        }
        return parseOctalLoose(block, offset, length);
    }

    private static String fixedString(byte[] block, int offset, int length) {
        int end = offset;
        int limit = offset + length;
        while (end < limit && block[end] != 0) {
            end++;
        }
        return new String(block, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static String normalizeName(String name) {
        String normalized = name.replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        while (normalized.endsWith("/.")) {
            normalized = normalized.substring(0, normalized.length() - 2);
        }
        return normalized;
    }

    private static String escape(String value) {
        return value.replaceAll("[^\\x20-\\x7E]", "?");
    }

    private static boolean isZeroBlock(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static int readFully(InputStream in, byte[] buffer, int required) throws IOException {
        int read = 0;
        while (read < required) {
            int n = in.read(buffer, read, required - read);
            if (n == -1) {
                break;
            }
            read += n;
        }
        if (read > 0 && read < required) {
            for (int i = read; i < required; i++) {
                buffer[i] = 0;
            }
            throw new ApiException.CorruptArchive(
                    "corrupt tar archive: unexpected end of stream (truncated block)");
        }
        return read;
    }

    private static final class BoundedEntryStream extends InputStream {

        private final InputStream delegate;
        private long remaining;

        private BoundedEntryStream(InputStream delegate, long remaining) {
            this.delegate = delegate;
            this.remaining = remaining;
        }

        @Override
        public int read() throws IOException {
            if (remaining == 0) {
                return -1;
            }
            int value = delegate.read();
            if (value == -1) {
                throw new ApiException.CorruptArchive(
                        "corrupt tar archive: truncated entry content");
            }
            remaining--;
            return value;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining == 0) {
                return -1;
            }
            int toRead = (int) Math.min(len, remaining);
            int n = delegate.read(b, off, toRead);
            if (n == -1) {
                throw new ApiException.CorruptArchive(
                        "corrupt tar archive: truncated entry content");
            }
            remaining -= n;
            return n;
        }

        private void drain() throws IOException {
            byte[] buffer = new byte[BLOCK * 8];
            while (remaining > 0) {
                int n = read(buffer, 0, buffer.length);
                if (n == -1) {
                    throw new ApiException.CorruptArchive(
                            "corrupt tar archive: truncated entry content");
                }
            }
        }
    }
}
