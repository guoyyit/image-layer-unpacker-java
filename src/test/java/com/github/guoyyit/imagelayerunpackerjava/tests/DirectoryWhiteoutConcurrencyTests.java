package com.github.guoyyit.imagelayerunpackerjava.tests;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
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
import com.github.guoyyit.imagelayerunpackerjava.error.ApiException;
import com.github.guoyyit.imagelayerunpackerjava.layer.LayerManager;
import com.github.guoyyit.imagelayerunpackerjava.merge.MergeService;
import com.github.guoyyit.imagelayerunpackerjava.storage.StorageLayout;
import com.github.guoyyit.imagelayerunpackerjava.unpack.UnpackService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * While a directory whiteout layer is repeatedly unpacked and deleted, every
 * read must observe one self-consistent layer set: the subtree is either fully
 * visible or fully hidden, never a mixture.
 */
class DirectoryWhiteoutConcurrencyTests {

    @TempDir
    Path tempDir;

    private LayerManager layerManager;
    private MergeService mergeService;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        ServiceProperties properties = new ServiceProperties();
        properties.setStorageDir(tempDir.resolve("store"));
        properties.setMaxLayerBytes(4L * 1024 * 1024);
        properties.setMaxTotalBytes(64L * 1024 * 1024);
        StorageLayout layout = new StorageLayout(properties);
        UnpackService unpackService = new UnpackService(properties);
        layerManager = new LayerManager(layout, unpackService, properties);
        layerManager.initialize();
        mergeService = new MergeService(layerManager);
        executor = Executors.newFixedThreadPool(8);
    }

    @AfterEach
    void tearDown() throws Exception {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    }

    @Test
    void readersObserveConsistentSubtreeVisibilityDuringDeleteAndUnpack() throws Exception {
        layerManager.submit("base", new ByteArrayInputStream(TarBuilder.create()
                .file("tree/sub/a.txt", "a-content")
                .file("tree/b.txt", "b-content")
                .file("other.txt", "other")
                .build()));
        byte[] wiper = TarBuilder.create().whiteout("", "tree").build();

        AtomicInteger errors = new AtomicInteger();
        AtomicInteger visibleReads = new AtomicInteger();
        AtomicInteger hiddenReads = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch writerDone = new CountDownLatch(1);

        Runnable reader = () -> {
            try {
                start.await();
                while (writerDone.getCount() > 0) {
                    checkConsistentState();
                    if (exists("tree")) {
                        visibleReads.incrementAndGet();
                    } else {
                        hiddenReads.incrementAndGet();
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (java.io.IOException | RuntimeException e) {
                errors.incrementAndGet();
            }
        };
        for (int i = 0; i < 5; i++) {
            executor.submit(reader);
        }
        executor.submit(() -> {
            try {
                start.await();
                for (int round = 0; round < 15; round++) {
                    layerManager.submit("wiper", new ByteArrayInputStream(wiper));
                    layerManager.delete("wiper");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                errors.incrementAndGet();
            } finally {
                writerDone.countDown();
            }
        });
        start.countDown();

        assertTrue(writerDone.await(60, TimeUnit.SECONDS));
        executor.shutdown();
        assertTrue(executor.awaitTermination(60, TimeUnit.SECONDS));
        assertEquals(0, errors.get());
        assertTrue(visibleReads.get() > 0, "readers should observe the visible state");
        assertTrue(hiddenReads.get() > 0, "readers should observe the hidden state");

        layerManager.submit("wiper", new ByteArrayInputStream(wiper));
        assertThrows(ApiException.NotFound.class, () -> mergeService.stat("/tree"));
        assertThrows(ApiException.NotFound.class, () -> mergeService.readFile("tree/sub/a.txt"));
        layerManager.delete("wiper");
        assertEquals("a-content", new String(mergeService.readFile("tree/sub/a.txt"),
                StandardCharsets.UTF_8));
    }

    private void checkConsistentState() throws java.io.IOException {
        // Every single request must reflect one complete layer set: either the
        // base layer alone (subtree fully visible) or base plus the wiper layer
        // (subtree fully hidden). Separate requests may legitimately observe
        // different layer sets while the writer toggles the wiper layer.
        List<String> root = mergeService.listDirectory("/").stream()
                .map(MergeService.MergeNode::getPath)
                .toList();
        if (!root.contains("/other.txt")) {
            throw new IllegalStateException("unrelated entry vanished from root: " + root);
        }
        if (root.stream().anyMatch(name -> name.contains(".wh."))) {
            throw new IllegalStateException("whiteout marker leaked into listing");
        }
        if (root.contains("/tree") && root.size() != 2) {
            throw new IllegalStateException("unexpected root entries: " + root);
        }
        if (!root.contains("/tree") && root.size() != 1) {
            throw new IllegalStateException("unexpected root entries: " + root);
        }

        try {
            String content = new String(mergeService.readFile("tree/sub/a.txt"),
                    StandardCharsets.UTF_8);
            if (!"a-content".equals(content)) {
                throw new IllegalStateException("torn read of tree/sub/a.txt: " + content);
            }
        } catch (ApiException.NotFound expected) {
            // hidden state: legal
        }

        try {
            List<String> tree = mergeService.listDirectory("/tree").stream()
                    .map(MergeService.MergeNode::getPath)
                    .toList();
            if (!tree.equals(List.of("/tree/b.txt", "/tree/sub"))) {
                throw new IllegalStateException(
                        "single listing exposed a partial subtree: " + tree);
            }
        } catch (ApiException.NotFound expected) {
            // hidden state: legal
        }
    }

    private boolean exists(String path) throws java.io.IOException {
        try {
            mergeService.stat(path);
            return true;
        } catch (ApiException.NotFound e) {
            return false;
        }
    }
}
