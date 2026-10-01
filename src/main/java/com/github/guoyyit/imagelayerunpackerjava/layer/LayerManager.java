package com.github.guoyyit.imagelayerunpackerjava.layer;

import com.github.guoyyit.imagelayerunpackerjava.UnpackerProperties;
import com.github.guoyyit.imagelayerunpackerjava.tar.SafeTarExtractor;
import com.github.guoyyit.imagelayerunpackerjava.tar.UnpackResult;
import com.github.guoyyit.imagelayerunpackerjava.view.MergedView;
import com.github.guoyyit.imagelayerunpackerjava.view.MergedViewImpl;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Coordinates the layer lifecycle: staging, safe unpack, atomic commit, quota
 * enforcement, deletion with reader pinning, and crash recovery.
 *
 * Concurrency contract:
 * <ul>
 *   <li>Unpack works on private staging paths and only touches shared state at
 *       commit, so reads never observe a half-written layer.</li>
 *   <li>{@link #openView()} pins the committed layers under the monitor; the
 *       view is a self-consistent snapshot. New commits do not affect it.</li>
 *   <li>Deletion first detaches the layer (no new readers), then waits for
 *       pinned readers to drain before reclaiming disk space.</li>
 * </ul>
 */
@Service
public class LayerManager {

    private static final Logger log = LoggerFactory.getLogger(LayerManager.class);
    private static final Pattern LAYER_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}");

    private final UnpackerProperties properties;
    private final LayerStore store;
    private final SafeTarExtractor extractor;
    /** Guarded by {@code this}. Insertion order = stacking order. */
    private final Map<String, LayerState> layers = new LinkedHashMap<>();
    private int nextSeq = 0;

    public LayerManager(UnpackerProperties properties) {
        this.properties = properties;
        this.store = new LayerStore(Path.of(properties.getRootDir()).toAbsolutePath().normalize());
        this.extractor = new SafeTarExtractor(properties.getMaxLayerBytes(), properties.getMaxEntriesPerLayer());
    }

    @PostConstruct
    void init() throws IOException {
        recover();
    }

    /**
     * Reload persisted state. Interrupted layers (status UNPACKING with a
     * staged tar still present) are re-unpacked; committed layers are adopted
     * as-is and never unpacked twice. Orphan files are cleaned up.
     */
    public synchronized void recover() throws IOException {
        store.initDirectories();
        List<LayerMeta> metas = store.loadAll();
        for (LayerMeta meta : metas) {
            switch (meta.getStatus()) {
                case COMMITTED -> {
                    if (Files.isDirectory(store.rootfsDir(meta.getId()))) {
                        register(meta);
                        store.deleteStagingTar(meta.getId());
                    } else {
                        log.warn("committed layer {} lost its rootfs, dropping metadata", meta.getId());
                        store.delete(meta.getId());
                    }
                }
                case UNPACKING -> recoverInterrupted(meta);
                case FAILED -> register(meta);
            }
        }
        cleanOrphans();
    }

    private void recoverInterrupted(LayerMeta meta) throws IOException {
        Path stagedTar = store.stagingTar(meta.getId());
        SafeTarExtractor.deleteRecursively(store.rootfsDir(meta.getId()));
        if (!Files.isRegularFile(stagedTar)) {
            log.warn("layer {} was interrupted and its staged tar is gone, marking FAILED", meta.getId());
            meta.setStatus(LayerStatus.FAILED);
            meta.setError("unpack interrupted by restart and staged tar is missing");
            meta.setUpdatedAt(Instant.now());
            store.save(meta);
            register(meta);
            return;
        }
        log.info("re-unpacking interrupted layer {}", meta.getId());
        try {
            UnpackResult result = extractor.extract(stagedTar, store.rootfsDir(meta.getId()));
            applyResult(meta, result);
            meta.setStatus(LayerStatus.COMMITTED);
            meta.setError(null);
            meta.setUpdatedAt(Instant.now());
            store.save(meta);
            store.deleteStagingTar(meta.getId());
        } catch (UnpackException e) {
            meta.setStatus(LayerStatus.FAILED);
            meta.setError(e.getMessage());
            meta.setUpdatedAt(Instant.now());
            store.save(meta);
            store.deleteStagingTar(meta.getId());
        }
        register(meta);
    }

    private void cleanOrphans() throws IOException {
        Path layersDir = store.rootDir().resolve("layers");
        if (Files.isDirectory(layersDir)) {
            try (Stream<Path> stream = Files.list(layersDir)) {
                for (Path dir : stream.filter(Files::isDirectory).toList()) {
                    if (!layers.containsKey(dir.getFileName().toString())) {
                        SafeTarExtractor.deleteRecursively(dir);
                    }
                }
            }
        }
        Path stagingDir = store.rootDir().resolve("staging");
        if (Files.isDirectory(stagingDir)) {
            try (Stream<Path> stream = Files.list(stagingDir)) {
                for (Path tar : stream.filter(Files::isRegularFile).toList()) {
                    String name = tar.getFileName().toString();
                    String id = name.endsWith(".tar") ? name.substring(0, name.length() - 4) : name;
                    if (!layers.containsKey(id)) {
                        Files.deleteIfExists(tar);
                    }
                }
            }
        }
    }

    private void register(LayerMeta meta) {
        LayerState state = new LayerState();
        state.meta = meta;
        state.rootfs = store.rootfsDir(meta.getId());
        layers.put(meta.getId(), state);
        nextSeq = Math.max(nextSeq, meta.getSeq() + 1);
    }

    /**
     * Unpack a layer and commit it as the new top layer.
     *
     * Idempotent: re-uploading an already committed id returns the existing
     * metadata unchanged (ids are expected to be content-addressed). A failed
     * or interrupted layer with the same id is cleaned up and unpacked again,
     * so a retry always converges to the same deterministic result.
     */
    public LayerMeta unpackLayer(String layerId, InputStream tarStream) throws IOException, UnpackException {
        validateLayerId(layerId);
        LayerState state;
        synchronized (this) {
            LayerState existing = layers.get(layerId);
            if (existing != null) {
                if (existing.deleting) {
                    throw new IllegalStateException("layer " + layerId + " is being deleted, retry later");
                }
                switch (existing.meta.getStatus()) {
                    case COMMITTED -> {
                        return copyOf(existing.meta);
                    }
                    case UNPACKING -> throw new IllegalStateException("layer " + layerId + " is already unpacking");
                    case FAILED -> {
                        store.delete(layerId);
                        layers.remove(layerId);
                    }
                }
            }
            state = new LayerState();
            state.meta = newMeta(layerId);
            state.rootfs = store.rootfsDir(layerId);
            layers.put(layerId, state);
            store.save(state.meta);
        }
        try {
            stageTar(layerId, tarStream);
            UnpackResult result = extractor.extract(store.stagingTar(layerId), store.rootfsDir(layerId));
            synchronized (this) {
                enforceTotalQuota(state.meta, result.sizeBytes());
                applyResult(state.meta, result);
                state.meta.setStatus(LayerStatus.COMMITTED);
                state.meta.setUpdatedAt(Instant.now());
                store.save(state.meta);
            }
            store.deleteStagingTar(layerId);
            return copyOf(state.meta);
        } catch (UnpackException e) {
            if (e instanceof QuotaExceededException) {
                removeLayer(state);
            } else {
                failLayer(state, e.getMessage());
            }
            throw e;
        } catch (IOException | RuntimeException e) {
            failLayer(state, e.getMessage());
            throw e;
        }
    }

    private void stageTar(String layerId, InputStream tarStream) throws IOException {
        Path tmp = store.stagingTar(layerId).resolveSibling(layerId + ".tar.part");
        try (tarStream) {
            Files.copy(tarStream, tmp, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        Files.move(tmp, store.stagingTar(layerId), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Quota check runs under the monitor so concurrent commits serialize deterministically. */
    private void enforceTotalQuota(LayerMeta candidate, long candidateBytes) throws UnpackException {
        long total = candidateBytes;
        for (LayerState other : layers.values()) {
            if (other.meta != candidate && other.meta.getStatus() == LayerStatus.COMMITTED) {
                total += other.meta.getSizeBytes();
            }
        }
        if (total > properties.getMaxTotalBytes()) {
            throw new QuotaExceededException("total quota exceeded: " + total + " > " + properties.getMaxTotalBytes() + " bytes");
        }
    }

    /** Quota rejections leave no trace: the service keeps its previous state. */
    private void removeLayer(LayerState state) {
        synchronized (this) {
            layers.remove(state.meta.getId());
            try {
                store.delete(state.meta.getId());
            } catch (IOException io) {
                log.error("failed to clean rejected layer {}", state.meta.getId(), io);
            }
        }
    }

    private void failLayer(LayerState state, String error) {
        synchronized (this) {
            state.meta.setStatus(LayerStatus.FAILED);
            state.meta.setError(error);
            state.meta.setUpdatedAt(Instant.now());
            try {
                store.save(state.meta);
                store.deleteStagingTar(state.meta.getId());
            } catch (IOException io) {
                log.error("failed to persist FAILED state of layer {}", state.meta.getId(), io);
            }
            try {
                SafeTarExtractor.deleteRecursively(store.rootfsDir(state.meta.getId()));
            } catch (IOException io) {
                log.error("failed to clean rootfs of layer {}", state.meta.getId(), io);
            }
        }
    }

    /**
     * Delete a layer and reclaim its space. The layer is detached from the
     * stacking order first (no new readers can pin it), then the call blocks
     * until already-pinned readers drain, then files are removed.
     */
    public void deleteLayer(String layerId) throws IOException {
        LayerState state;
        synchronized (this) {
            state = layers.get(layerId);
            if (state == null) {
                throw new NoSuchFileException("unknown layer: " + layerId);
            }
            if (state.deleting) {
                throw new IllegalStateException("layer " + layerId + " is already being deleted");
            }
            if (state.meta.getStatus() == LayerStatus.UNPACKING) {
                throw new IllegalStateException("layer " + layerId + " is unpacking, cannot delete now");
            }
            state.deleting = true;
            while (state.readers > 0) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    state.deleting = false;
                    notifyAll();
                    throw new IOException("interrupted while waiting for readers of layer " + layerId, e);
                }
            }
            layers.remove(layerId);
        }
        store.delete(layerId);
    }

    /** Snapshot of all known layers in stacking order (bottom first). */
    public List<LayerMeta> listLayers() {
        synchronized (this) {
            return layers.values().stream().map(s -> copyOf(s.meta)).toList();
        }
    }

    /**
     * Open a consistent merged view over the currently committed layers.
     * The returned view pins its layers; always close it after use.
     */
    public MergedView openView() {
        List<LayerState> pinned;
        synchronized (this) {
            pinned = new ArrayList<>();
            for (LayerState state : layers.values()) {
                if (state.meta.getStatus() == LayerStatus.COMMITTED && !state.deleting) {
                    state.readers++;
                    pinned.add(state);
                }
            }
        }
        List<MergedViewImpl.ViewLayer> viewLayers = pinned.stream()
                .map(s -> new MergedViewImpl.ViewLayer(
                        s.rootfs,
                        Set.copyOf(s.meta.getWhiteouts()),
                        Set.copyOf(s.meta.getOpaqueDirs())))
                .toList();
        return new MergedViewImpl(viewLayers, () -> unpin(pinned));
    }

    private void unpin(List<LayerState> pinned) {
        synchronized (this) {
            for (LayerState state : pinned) {
                state.readers--;
            }
            notifyAll();
        }
    }

    private static void validateLayerId(String layerId) {
        if (layerId == null || !LAYER_ID.matcher(layerId).matches()) {
            throw new IllegalArgumentException("invalid layer id: " + layerId);
        }
    }

    private LayerMeta newMeta(String layerId) {
        LayerMeta meta = new LayerMeta();
        meta.setId(layerId);
        meta.setStatus(LayerStatus.UNPACKING);
        meta.setSeq(nextSeq++);
        meta.setCreatedAt(Instant.now());
        meta.setUpdatedAt(meta.getCreatedAt());
        return meta;
    }

    private static void applyResult(LayerMeta meta, UnpackResult result) {
        meta.setSizeBytes(result.sizeBytes());
        meta.setFileCount(result.fileCount());
        meta.setWhiteouts(result.whiteouts());
        meta.setOpaqueDirs(result.opaqueDirs());
    }

    private static LayerMeta copyOf(LayerMeta meta) {
        LayerMeta copy = new LayerMeta();
        copy.setId(meta.getId());
        copy.setStatus(meta.getStatus());
        copy.setSeq(meta.getSeq());
        copy.setSizeBytes(meta.getSizeBytes());
        copy.setFileCount(meta.getFileCount());
        copy.setCreatedAt(meta.getCreatedAt());
        copy.setUpdatedAt(meta.getUpdatedAt());
        copy.setError(meta.getError());
        copy.setWhiteouts(List.copyOf(meta.getWhiteouts()));
        copy.setOpaqueDirs(List.copyOf(meta.getOpaqueDirs()));
        return copy;
    }

    private static final class LayerState {
        LayerMeta meta;
        Path rootfs;
        int readers;
        boolean deleting;
    }
}
