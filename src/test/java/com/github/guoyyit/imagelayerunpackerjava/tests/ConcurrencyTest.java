package com.github.guoyyit.imagelayerunpackerjava.tests;

import com.github.guoyyit.imagelayerunpackerjava.UnpackerProperties;
import com.github.guoyyit.imagelayerunpackerjava.layer.LayerManager;
import com.github.guoyyit.imagelayerunpackerjava.layer.LayerStatus;
import com.github.guoyyit.imagelayerunpackerjava.view.MergedView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.github.guoyyit.imagelayerunpackerjava.tests.TarBuilder.tar;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Reads and unpacks run concurrently: every view must be a self-consistent
 * snapshot, and deletion must wait for readers without ever reclaiming a
 * layer that is still pinned.
 */
class ConcurrencyTest {

    @TempDir
    Path tempDir;

    private LayerManager newManager() throws Exception {
        UnpackerProperties properties = new UnpackerProperties();
        properties.setRootDir(tempDir.toString());
        LayerManager manager = new LayerManager(properties);
        manager.recover();
        return manager;
    }

    private static String readString(MergedView view, String path) throws Exception {
        try (var in = view.openFile(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void readersNeverSeeMixedViewDuringUnpack() throws Exception {
        LayerManager manager = newManager();
        manager.unpackLayer("l1", new ByteArrayInputStream(tar()
                .file("a.txt", "v1")
                .build()));
        byte[] l2 = tar().file("a.txt", "v2").file("b.txt", "b").build();

        int readerCount = 8;
        ExecutorService pool = Executors.newFixedThreadPool(readerCount + 1);
        CountDownLatch start = new CountDownLatch(1);
        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger iterations = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < readerCount; i++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    while (!stop.get()) {
                        try (MergedView view = manager.openView()) {
                            boolean hasB = view.stat("/b.txt") != null;
                            String a = readString(view, "/a.txt");
                            if (hasB) {
                                assertEquals("v2", a, "view has b.txt but stale a.txt: mixed state!");
                            } else {
                                assertEquals("v1", a, "view lacks b.txt but has new a.txt: mixed state!");
                            }
                            iterations.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        Future<?> unpacker = pool.submit(() -> {
            try {
                start.await();
                Thread.sleep(30); // let readers spin on the old state first
                manager.unpackLayer("l2", new ByteArrayInputStream(l2));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        start.countDown();
        unpacker.get(30, TimeUnit.SECONDS);
        // Give readers a moment to observe the post-commit state too.
        Thread.sleep(100);
        stop.set(true);
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        assertTrue(iterations.get() > 0, "readers should have completed some iterations");
    }

    @Test
    void deleteWaitsForPinnedReadersAndThenReclaims() throws Exception {
        LayerManager manager = newManager();
        manager.unpackLayer("l1", new ByteArrayInputStream(tar().file("f.txt", "data").build()));
        Path rootfs = tempDir.resolve("layers/l1/rootfs");
        assertTrue(java.nio.file.Files.isDirectory(rootfs));

        MergedView pinned = manager.openView();
        assertEquals("data", readString(pinned, "/f.txt"));

        AtomicBoolean deleteDone = new AtomicBoolean();
        Thread deleter = new Thread(() -> {
            try {
                manager.deleteLayer("l1");
                deleteDone.set(true);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        deleter.start();
        Thread.sleep(200);
        assertTrue(deleter.isAlive(), "delete must block while a reader pins the layer");
        assertFalse(deleteDone.get());
        assertTrue(java.nio.file.Files.isDirectory(rootfs), "pinned layer must not be reclaimed");
        // The pinned view still reads fine while deletion is pending.
        assertEquals("data", readString(pinned, "/f.txt"));

        pinned.close();
        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> deleter.join());
        assertTrue(deleteDone.get());
        assertFalse(java.nio.file.Files.exists(rootfs), "layer files must be reclaimed after drain");
        assertEquals(0, manager.listLayers().size());
    }

    @Test
    void deleteAndUnpackOfDifferentLayersRunConcurrently() throws Exception {
        LayerManager manager = newManager();
        manager.unpackLayer("old", new ByteArrayInputStream(tar().file("old.txt", "o").build()));
        byte[] newLayer = tar().file("new.txt", "n").build();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> deleter = pool.submit(() -> {
            try {
                manager.deleteLayer("old");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        Future<?> unpacker = pool.submit(() -> {
            try {
                manager.unpackLayer("fresh", new ByteArrayInputStream(newLayer));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        deleter.get(30, TimeUnit.SECONDS);
        unpacker.get(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertEquals(1, manager.listLayers().size());
        assertEquals("fresh", manager.listLayers().get(0).getId());
        assertEquals(LayerStatus.COMMITTED, manager.listLayers().get(0).getStatus());
        try (MergedView view = manager.openView()) {
            assertNull(view.stat("/old.txt"));
            assertEquals("n", readString(view, "/new.txt"));
        }
    }

    @Test
    void concurrentUnpackOfSameIdIsRejectedDeterministically() throws Exception {
        LayerManager manager = newManager();
        byte[] archive = tar().file("f", "1").build();
        int threads = 4;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger committed = new AtomicInteger();
        AtomicInteger conflicts = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                try {
                    start.await();
                    manager.unpackLayer("same", new ByteArrayInputStream(archive));
                    committed.incrementAndGet();
                } catch (IllegalStateException e) {
                    conflicts.incrementAndGet();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdownNow();
        // Exactly one unpack runs; the rest either conflict or return the committed layer.
        assertEquals(threads, committed.get() + conflicts.get());
        assertEquals(1, manager.listLayers().size());
        assertEquals(LayerStatus.COMMITTED, manager.listLayers().get(0).getStatus());
        try (MergedView view = manager.openView()) {
            assertEquals("1", readString(view, "/f"));
        }
    }
}
