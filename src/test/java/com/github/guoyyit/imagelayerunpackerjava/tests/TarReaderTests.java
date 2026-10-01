package com.github.guoyyit.imagelayerunpackerjava.tests;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.guoyyit.imagelayerunpackerjava.error.ApiException;
import com.github.guoyyit.imagelayerunpackerjava.tar.TarReader;
import com.github.guoyyit.imagelayerunpackerjava.tar.TarReader.TarEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TarReaderTests {

    private final TarReader reader = new TarReader();

    @Test
    void readsRegularFileAndDirectory() throws Exception {
        byte[] tar = TarBuilder.create()
                .directory("dir")
                .file("dir/a.txt", "hello")
                .build();

        List<TarEntry> entries = reader.collect(new ByteArrayInputStream(tar));
        assertEquals(2, entries.size());
        assertEquals("dir/", entries.get(0).name);
        assertEquals("dir/a.txt", entries.get(1).name);
        assertEquals(5, entries.get(1).size);
    }

    @Test
    void rejectsTruncatedArchive() {
        byte[] tar = TarBuilder.create()
                .file("a.txt", "hello world")
                .build();
        byte[] truncated = new byte[tar.length - 100];
        System.arraycopy(tar, 0, truncated, 0, truncated.length);

        ApiException error = assertThrows(ApiException.class,
                () -> reader.collect(new ByteArrayInputStream(truncated)));
        assertTrue(error instanceof ApiException.CorruptArchive);
    }

    @Test
    void rejectsBadChecksum() {
        byte[] tar = TarBuilder.create()
                .withBadChecksum()
                .file("a.txt", "hello")
                .build();

        assertThrows(ApiException.CorruptArchive.class,
                () -> reader.collect(new ByteArrayInputStream(tar)));
    }

    @Test
    void rejectsEmptyArchive() {
        byte[] tar = new byte[1024];
        assertThrows(ApiException.CorruptArchive.class,
                () -> reader.collect(new ByteArrayInputStream(tar)));
    }

    @Test
    void parsesSymlinkEntry() throws Exception {
        byte[] tar = TarBuilder.create()
                .symlink("link", "target.txt")
                .build();
        List<TarEntry> entries = new ArrayList<>();
        reader.read(new ByteArrayInputStream(tar), (entry, content) -> {
            entries.add(entry);
            content.transferTo(java.io.OutputStream.nullOutputStream());
        });
        assertEquals("link", entries.get(0).name);
        assertEquals("target.txt", entries.get(0).linkTarget);
        assertEquals(TarReader.TYPE_SYMLINK, entries.get(0).typeFlag);
    }

    @Test
    void acceptsPosixNullTerminatedMagic() throws Exception {
        byte[] tar = TarBuilder.create()
                .posixMagic()
                .file("a.txt", "x")
                .build();

        List<TarEntry> entries = reader.collect(new ByteArrayInputStream(tar));
        assertEquals("a.txt", entries.get(0).name);
    }
}
