package com.github.guoyyit.imagelayerunpackerjava.tests;

import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.guoyyit.imagelayerunpackerjava.config.ServiceProperties;
import com.github.guoyyit.imagelayerunpackerjava.error.ApiException;
import com.github.guoyyit.imagelayerunpackerjava.tar.TarReader;
import com.github.guoyyit.imagelayerunpackerjava.unpack.UnpackService;

import static org.junit.jupiter.api.Assertions.assertThrows;

class MaliciousArchiveTests {

    @TempDir
    Path tempDir;

    private UnpackService unpackService;
    private Path root;

    @BeforeEach
    void setUp() {
        ServiceProperties properties = new ServiceProperties();
        properties.setMaxLayerBytes(1024 * 1024);
        unpackService = new UnpackService(properties);
        root = tempDir.resolve("out");
    }

    @Test
    void rejectsDeviceNodes() {
        byte[] tar = MaliciousTars.deviceNode("dev/null");
        assertThrows(ApiException.class, () -> unpack(tar));
    }

    @Test
    void rejectsPaxExtendedHeaders() {
        byte[] tar = MaliciousTars.paxHeader();
        assertThrows(ApiException.class, () -> unpack(tar));
    }

    @Test
    void rejectsAbsolutePath() {
        byte[] tar = MaliciousTars.named("/etc/evil", "x");
        assertThrows(ApiException.UnsafeArchive.class, () -> unpack(tar));
    }

    @Test
    void rejectsEmbeddedParentReference() {
        byte[] tar = MaliciousTars.named("dir/../../evil", "x");
        assertThrows(ApiException.UnsafeArchive.class, () -> unpack(tar));
    }

    @Test
    void rejectsGnuLongLink() {
        byte[] tar = MaliciousTars.gnuLongLink();
        assertThrows(ApiException.class, () -> unpack(tar));
    }

    @Test
    void readerRejectsUnknownMagic() {
        byte[] tar = MaliciousTars.badMagic();
        assertThrows(ApiException.CorruptArchive.class,
                () -> new TarReader().collect(new java.io.ByteArrayInputStream(tar)));
    }

    private void unpack(byte[] tar) throws Exception {
        Path blob = tempDir.resolve("blob.tar");
        java.nio.file.Files.write(blob, tar);
        unpackService.extract(blob, root);
    }
}
