package com.github.guoyyit.imagelayerunpackerjava.tests;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.github.guoyyit.imagelayerunpackerjava.config.ServiceProperties;
import com.github.guoyyit.imagelayerunpackerjava.layer.LayerManager;
import com.github.guoyyit.imagelayerunpackerjava.merge.MergeService;
import com.github.guoyyit.imagelayerunpackerjava.storage.StorageLayout;
import com.github.guoyyit.imagelayerunpackerjava.unpack.UnpackService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrencyTests {

    @TempDir
    Path tempDir;

    private LayerManager layerManager;
    private MergeService mergeService;
    private ExecutorService executor;

    @BeforeEach
    void setUp() throws Exception {
        ServiceProperties properties = new ServiceProperties();
        properties.setStorageDir(tempDir.resolve("store"));
        properties.setMaxLayerBytes(1024 * 1024);
        properties.setMaxTotalBytes(64L * 1024 * 1024);
        StorageLayout layout = new StorageLayout(properties);
        UnpackService unpackService = new UnpackService(properties);
        layerManager = new LayerManager(layout, unpackService, properties);
        layerManager.initialize();
        layerManager.recover();
        mergeService = new MergeService(layerManager);
        executor = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() throws Exception {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    }

    @Test
    void readersAlwaysObserveCommittedSnapshotDuringUnpack() throws Exception {
        layerManager.submit("base", new ByteArrayInputStream(
                TarBuilder.create().file("version.txt", "v1").build()));

        byte[] upper = TarBuilder.create().file("version.txt", "v2").build();
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger v1 = new AtomicInteger();
        AtomicInteger v2 = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();

        for (int i = 0; i < 6; i++) {
            executor.submit(() -> {
                try {
                    start.await();
                    for (int j = 0; j < 200; j++) {
                        String value = new String(mergeService.readFile("version.txt"),
                                StandardCharsets.UTF_8);
                        if ("v1".equals(value)) {
                            v1.incrementAndGet();
                        } else if ("v2".equals(value)) {
                            v2.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
        }
        executor.submit(() -> {
            start.countDown();
            try {
                layerManager.submit("upper", new ByteArrayInputStream(upper));
            } catch (Exception e) {
                errors.incrementAndGet();
            }
        });
        start.countDown();

        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(0, errors.get());
        assertTrue(v1.get() > 0);
        assertTrue(v2.get() > 0);
    }

    @Test
    void listAndReadNeverObserveHalfWrittenLayer() throws Exception {
        layerManager.submit("base", new ByteArrayInputStream(
                TarBuilder.create()
                        .file("a.txt", "a")
                        .file("b.txt", "b")
                        .build()));

        byte[] upper = TarBuilder.create()
                .file("a.txt", "A")
                .file("b.txt", "B")
                .file("c.txt", "C")
                .build();
        AtomicInteger consistent = new AtomicInteger();
        AtomicInteger errors = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(1);

        for (int i = 0; i < 6; i++) {
            executor.submit(() -> {
                try {
                    while (done.getCount() > 0) {
                        boolean hasA = exists("a.txt");
                        boolean hasB = exists("b.txt");
                        boolean hasC = exists("c.txt");
                        boolean oldA = "a".equals(read("a.txt"));
                        boolean oldB = "b".equals(read("b.txt"));
                        boolean newA = "A".equals(read("a.txt"));
                        boolean newB = "B".equals(read("b.txt"));
                        boolean coherent = (oldA && oldB && !hasC) || (newA && newB && hasC);
                        if (coherent) {
                            consistent.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                }
            });
        }
        executor.submit(() -> {
            try {
                layerManager.submit("upper", new ByteArrayInputStream(upper));
            } finally {
                done.countDown();
            }
        });

        assertTrue(done.await(30, TimeUnit.SECONDS));
        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(0, errors.get());
        assertTrue(consistent.get() > 0);
    }

    @Test
    void concurrentDeleteAndSubmitAreSerializableAndDeterministic() throws Exception {
        layerManager.submit("layer", new ByteArrayInputStream(
                TarBuilder.create().file("file.txt", "x").build()));

        AtomicInteger failures = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        for (int round = 0; round < 20; round++) {
            String layerId = "layer-" + round;
            byte[] tar = TarBuilder.create().file("f.txt", layerId).build();
            executor.submit(() -> {
                try {
                    start.await();
                    layerManager.submit(layerId, new ByteArrayInputStream(tar));
                    layerManager.delete(layerId);
                } catch (Exception e) {
                    failures.incrementAndGet();
                }
            });
        }
        start.countDown();
        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(0, failures.get());
    }

    @Test
    void readsStaySelfConsistentWhileDirectoryWhiteoutLayerIsDeleted() throws Exception {
        layerManager.submit("base", new ByteArrayInputStream(
                TarBuilder.create()
                        .file("data/deep/file.txt", "base-content")
                        .file("top.txt", "top")
                        .build()));
        layerManager.submit("wiper", new ByteArrayInputStream(
                TarBuilder.create().whiteout("", "data").build()));

        AtomicInteger hiddenReads = new AtomicInteger();
        AtomicInteger visibleReads = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch sawHidden = new CountDownLatch(5);
        CountDownLatch sawVisible = new CountDownLatch(5);
        for (int i = 0; i < 4; i++) {
            executor.submit(() -> {
                try {
                    start.await();
                    while (sawVisible.getCount() > 0) {
                        try {
                            String value = read("data/deep/file.txt");
                            if (!"base-content".equals(value)) {
                                failures.incrementAndGet();
                            } else {
                                visibleReads.incrementAndGet();
                                sawVisible.countDown();
                            }
                        } catch (com.github.guoyyit.imagelayerunpackerjava.error.ApiException
                                .NotFound expected) {
                            hiddenReads.incrementAndGet();
                            sawHidden.countDown();
                        }
                        mergeService.listDirectory("/");
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                }
            });
        }
        executor.submit(() -> {
            try {
                start.await();
                if (sawHidden.await(20, TimeUnit.SECONDS)) {
                    layerManager.delete("wiper");
                } else {
                    failures.incrementAndGet();
                }
            } catch (Exception e) {
                failures.incrementAndGet();
            }
        });
        start.countDown();

        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(0, failures.get());
        assertTrue(hiddenReads.get() > 0);
        assertTrue(visibleReads.get() > 0);
        assertEquals("base-content", read("data/deep/file.txt"));
        assertEquals("directory", mergeService.stat("data").getType());
    }

    @Test
    void readsStaySelfConsistentWhileDirectoryWhiteoutLayerIsUnpacked() throws Exception {
        layerManager.submit("base", new ByteArrayInputStream(
                TarBuilder.create()
                        .file("data/deep/file.txt", "base-content")
                        .file("top.txt", "top")
                        .build()));

        AtomicInteger hiddenReads = new AtomicInteger();
        AtomicInteger visibleReads = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch sawVisible = new CountDownLatch(5);
        CountDownLatch sawHidden = new CountDownLatch(5);
        for (int i = 0; i < 4; i++) {
            executor.submit(() -> {
                try {
                    start.await();
                    while (sawHidden.getCount() > 0) {
                        try {
                            String value = read("data/deep/file.txt");
                            if (!"base-content".equals(value)) {
                                failures.incrementAndGet();
                            } else {
                                visibleReads.incrementAndGet();
                                sawVisible.countDown();
                            }
                        } catch (com.github.guoyyit.imagelayerunpackerjava.error.ApiException
                                .NotFound expected) {
                            hiddenReads.incrementAndGet();
                            sawHidden.countDown();
                        }
                        mergeService.listDirectory("/");
                        mergeService.stat("top.txt");
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                }
            });
        }
        executor.submit(() -> {
            try {
                start.await();
                if (sawVisible.await(20, TimeUnit.SECONDS)) {
                    layerManager.submit("wiper", new ByteArrayInputStream(
                            TarBuilder.create().whiteout("", "data").build()));
                } else {
                    failures.incrementAndGet();
                }
            } catch (Exception e) {
                failures.incrementAndGet();
            }
        });
        start.countDown();

        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS));
        assertEquals(0, failures.get());
        assertTrue(hiddenReads.get() > 0);
        assertTrue(visibleReads.get() > 0);
        assertThrows(
                com.github.guoyyit.imagelayerunpackerjava.error.ApiException.NotFound.class,
                () -> mergeService.stat("data"));
        assertEquals("top", read("top.txt"));
    }

    private boolean exists(String path) throws Exception {
        try {
            mergeService.stat(path);
            return true;
        } catch (com.github.guoyyit.imagelayerunpackerjava.error.ApiException.NotFound e) {
            return false;
        }
    }

    private String read(String path) throws Exception {
        return new String(mergeService.readFile(path), StandardCharsets.UTF_8);
    }
}
