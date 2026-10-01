package com.github.guoyyit.imagelayerunpackerjava.layer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import org.springframework.stereotype.Service;

import org.springframework.http.HttpStatus;

import com.github.guoyyit.imagelayerunpackerjava.config.ServiceProperties;
import com.github.guoyyit.imagelayerunpackerjava.error.ApiException;
import com.github.guoyyit.imagelayerunpackerjava.model.LayerRecord;
import com.github.guoyyit.imagelayerunpackerjava.model.UnpackReport;
import com.github.guoyyit.imagelayerunpackerjava.storage.StorageLayout;
import com.github.guoyyit.imagelayerunpackerjava.unpack.UnpackService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

@Service
public class LayerManager {

    private final StorageLayout layout;
    private final UnpackService unpackService;
    private final ServiceProperties properties;
    private final ObjectMapper mapper;

    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock(true);
    private final Map<String, LayerRecord> committed = new LinkedHashMap<>();
    private final Map<String, Object> inFlight = new ConcurrentHashMap<>();
    private long nextSequence = 1;
    private long totalUnpackedBytes;

    public LayerManager(StorageLayout layout, UnpackService unpackService,
            ServiceProperties properties) {
        this.layout = layout;
        this.unpackService = unpackService;
        this.properties = properties;
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public static final class Snapshot {
        private final List<LayerRecord> layers;
        private final StorageLayout layout;

        Snapshot(List<LayerRecord> layers, StorageLayout layout) {
            this.layers = layers;
            this.layout = layout;
        }

        public List<LayerRecord> getLayers() {
            return layers;
        }

        public Path layerRoot(String id) {
            return layout.layerRoot(id);
        }
    }

    public void initialize() {
        layout.initialize();
        rwLock.writeLock().lock();
        try {
            loadRegistry();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public LayerRecord submit(String id, InputStream tarStream) {
        validateId(id);
        Object monitor = inFlight.computeIfAbsent(id, key -> new Object());
        synchronized (monitor) {
            try {
                rwLock.readLock().lock();
                LayerRecord existing;
                try {
                    existing = committed.get(id);
                } finally {
                    rwLock.readLock().unlock();
                }
                if (existing != null) {
                    return copy(existing);
                }

                String token = Long.toHexString(System.nanoTime());
                Path staging = layout.stagingDir(id, token);
                Path finalDir = layout.layerDir(id);
                Path blob = staging.resolve("blob.tar");
                Path root = staging.resolve("root");
                try {
                    try {
                        Files.createDirectories(staging);
                    } catch (IOException e) {
                        throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                                "failed to prepare layer storage: " + e.getMessage());
                    }
                    byte[] data;
                    try {
                        data = tarStream.readAllBytes();
                    } catch (IOException e) {
                        throw new ApiException.BadRequest(
                                "failed to read layer payload: " + e.getMessage());
                    }
                    if (data.length == 0) {
                        throw new ApiException.BadRequest("empty layer payload");
                    }
                    if (data.length > properties.getMaxLayerBytes()) {
                        throw new ApiException.QuotaExceeded(
                                "layer payload exceeds single layer quota");
                    }
                    Files.write(blob, data);
                    String digest = sha256(blob);
                    UnpackReport report = unpackService.extract(blob, root);
                    if (report.getUnpackedBytes() > properties.getMaxLayerBytes()) {
                        throw new ApiException.QuotaExceeded(
                                "unpacked layer size exceeds single layer quota");
                    }
                    report.setSha256(digest);
                    return commit(id, staging, finalDir, report, false);
                } catch (ApiException e) {
                    throw e;
                } catch (IOException e) {
                    throw new ApiException.CorruptArchive(
                            "failed to unpack layer: " + e.getMessage());
                }
            } finally {
                deleteStagingQuietly(id);
                inFlight.remove(id);
            }
        }
    }

    private void deleteStagingQuietly(String id) {
        Path layersDir = layout.layersDir();
        if (!Files.isDirectory(layersDir)) {
            return;
        }
        try (var stream = Files.list(layersDir)) {
            stream.filter(path -> path.getFileName().toString().startsWith(id + ".staging-"))
                    .forEach(LayerManager::deleteQuietly);
        } catch (IOException ignored) {
        }
    }

    private LayerRecord commit(String id, Path staging, Path finalDir, UnpackReport report,
            boolean recovering) throws IOException {
        rwLock.writeLock().lock();
        try {
            LayerRecord previous = committed.get(id);
            if (previous != null && !recovering) {
                return copy(committed.get(id));
            }
            long baseline = previous == null ? totalUnpackedBytes
                    : totalUnpackedBytes - previous.getUnpackedBytes();
            long projected = baseline + report.getUnpackedBytes();
            if (projected > properties.getMaxTotalBytes()) {
                throw new ApiException.QuotaExceeded(
                        "total storage quota would be exceeded by layer '" + id + "'");
            }
            if (Files.exists(finalDir)) {
                deleteRecursively(finalDir);
            }
            Files.move(staging, finalDir);

            LayerRecord record = new LayerRecord();
            record.setId(id);
            record.setSequence(previous == null ? nextSequence++ : previous.getSequence());
            record.setPayloadBytes(report.getPayloadBytes());
            record.setUnpackedBytes(report.getUnpackedBytes());
            record.setSha256(report.getSha256());
            record.setCommittedAt(previous == null ? Instant.now() : previous.getCommittedAt());
            record.setWhiteouts(new ArrayList<>(report.getWhiteouts()));
            record.setOpaqueDirs(new ArrayList<>(report.getOpaqueDirs()));
            record.setPaths(new ArrayList<>(report.getPaths()));
            writeMeta(record);
            committed.put(id, record);
            totalUnpackedBytes = baseline + report.getUnpackedBytes();
            persistRegistry();
            return copy(record);
        } catch (IOException | RuntimeException e) {
            deleteQuietly(finalDir);
            throw e;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public LayerRecord get(String id) {
        validateId(id);
        rwLock.readLock().lock();
        try {
            LayerRecord record = committed.get(id);
            if (record == null) {
                throw new ApiException.NotFound("layer not found: " + id);
            }
            return copy(record);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public List<LayerRecord> list() {
        rwLock.readLock().lock();
        try {
            return committed.values().stream().map(LayerManager::copy).toList();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public void delete(String id) {
        validateId(id);
        rwLock.writeLock().lock();
        try {
            LayerRecord record = committed.remove(id);
            if (record == null) {
                throw new ApiException.NotFound("layer not found: " + id);
            }
            totalUnpackedBytes -= record.getUnpackedBytes();
            if (totalUnpackedBytes < 0) {
                totalUnpackedBytes = 0;
            }
            try {
                deleteRecursively(layout.layerDir(id));
            } catch (IOException e) {
                committed.put(id, record);
                totalUnpackedBytes += record.getUnpackedBytes();
                throw new ApiException(
                        org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                        "failed to delete layer data: " + e.getMessage());
            }
            persistRegistry();
        } catch (IOException e) {
            throw new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                    "failed to persist registry: " + e.getMessage());
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public <T> T withSnapshot(SnapshotAction<T> action) throws IOException {
        rwLock.readLock().lock();
        try {
            Snapshot snapshot = new Snapshot(
                    committed.values().stream().map(LayerManager::copy).toList(), layout);
            return action.apply(snapshot);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public interface SnapshotAction<T> {
        T apply(Snapshot snapshot) throws IOException;
    }

    public void recover() {
        List<String> pending = new ArrayList<>();
        rwLock.writeLock().lock();
        try {
            Path layersDir = layout.layersDir();
            if (!Files.isDirectory(layersDir)) {
                return;
            }
            List<Path> found;
            try (var stream = Files.list(layersDir)) {
                found = stream.toList();
            } catch (IOException e) {
                throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "failed to inspect storage during recovery: " + e.getMessage());
            }
            for (Path entry : found) {
                String name = entry.getFileName().toString();
                if (name.contains(".staging-")) {
                    deleteQuietly(entry);
                    continue;
                }
                if (!StorageLayout.isValidId(name)) {
                    deleteQuietly(entry);
                    continue;
                }
                if (committed.containsKey(name)) {
                    if (!Files.isDirectory(layout.layerRoot(name))) {
                        pending.add(name);
                    }
                } else if (Files.isRegularFile(layout.layerBlob(name))) {
                    pending.add(name);
                } else {
                    deleteQuietly(entry);
                }
            }
        } finally {
            rwLock.writeLock().unlock();
        }

        for (String id : pending) {
            try {
                try (InputStream in = Files.newInputStream(layout.layerBlob(id))) {
                    resubmitRecovered(id, in);
                }
            } catch (IOException e) {
                rwLock.writeLock().lock();
                try {
                    deleteQuietly(layout.layerDir(id));
                } finally {
                    rwLock.writeLock().unlock();
                }
                org.slf4j.LoggerFactory.getLogger(LayerManager.class)
                        .warn("discarding unrecoverable incomplete layer '{}': {}",
                                id, e.getMessage());
            } catch (RuntimeException e) {
                rwLock.writeLock().lock();
                try {
                    deleteQuietly(layout.layerDir(id));
                } finally {
                    rwLock.writeLock().unlock();
                }
                org.slf4j.LoggerFactory.getLogger(LayerManager.class)
                        .warn("discarding unrecoverable incomplete layer '{}': {}",
                                id, e.getMessage());
            }
        }
    }

    private void resubmitRecovered(String id, InputStream tarStream) throws IOException {
        Object monitor = inFlight.computeIfAbsent(id, key -> new Object());
        synchronized (monitor) {
            byte[] data = tarStream.readAllBytes();
            String token = Long.toHexString(System.nanoTime());
            Path staging = layout.stagingDir(id, token);
            Path finalDir = layout.layerDir(id);
            Path blob = staging.resolve("blob.tar");
            Path root = staging.resolve("root");
            Files.createDirectories(staging);
            try {
                Files.write(blob, data);
                String digest = sha256(blob);
                UnpackReport report = unpackService.extract(blob, root);
                if (report.getUnpackedBytes() > properties.getMaxLayerBytes()) {
                    throw new ApiException.QuotaExceeded(
                            "unpacked layer size exceeds single layer quota");
                }
                report.setSha256(digest);
                commit(id, staging, finalDir, report, true);
            } catch (IOException | RuntimeException e) {
                deleteQuietly(staging);
                throw e;
            } finally {
                deleteQuietly(staging);
                inFlight.remove(id);
            }
        }
    }

    private void loadRegistry() {
        Path registry = layout.registryFile();
        if (!Files.exists(registry)) {
            return;
        }
        RegistryFile file;
        try {
            file = mapper.readValue(Files.readString(registry, StandardCharsets.UTF_8),
                    RegistryFile.class);
        } catch (IOException e) {
            throw new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                    "failed to read layer registry: " + e.getMessage());
        }
        committed.clear();
        nextSequence = file.getNextSequence() <= 0 ? 1 : file.getNextSequence();
        totalUnpackedBytes = 0;
        for (LayerRecord record : file.getLayers()) {
            if (!StorageLayout.isValidId(record.getId())) {
                throw new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                        "invalid layer id in registry: " + record.getId());
            }
            if (!Files.isDirectory(layout.layerRoot(record.getId()))) {
                continue;
            }
            committed.put(record.getId(), record);
            totalUnpackedBytes += record.getUnpackedBytes();
            if (record.getSequence() >= nextSequence) {
                nextSequence = record.getSequence() + 1;
            }
        }
        persistRegistryQuietly();
    }

    private void persistRegistry() throws IOException {
        RegistryFile file = new RegistryFile();
        file.setNextSequence(nextSequence);
        file.setLayers(new ArrayList<>(committed.values()));
        Path registry = layout.registryFile();
        Path tmp = registry.resolveSibling(registry.getFileName() + ".tmp");
        Files.writeString(tmp, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(file),
                StandardCharsets.UTF_8);
        try {
            Files.move(tmp, registry,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            Files.move(tmp, registry, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void persistRegistryQuietly() {
        try {
            persistRegistry();
        } catch (IOException e) {
            throw new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                    "failed to persist registry: " + e.getMessage());
        }
    }

    private void writeMeta(LayerRecord record) throws IOException {
        Path meta = layout.layerMeta(record.getId());
        Files.writeString(meta, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(record),
                StandardCharsets.UTF_8);
    }

    private static LayerRecord copy(LayerRecord source) {
        LayerRecord target = new LayerRecord();
        target.setId(source.getId());
        target.setSequence(source.getSequence());
        target.setPayloadBytes(source.getPayloadBytes());
        target.setUnpackedBytes(source.getUnpackedBytes());
        target.setSha256(source.getSha256());
        target.setCommittedAt(source.getCommittedAt());
        target.setWhiteouts(new ArrayList<>(source.getWhiteouts()));
        target.setOpaqueDirs(new ArrayList<>(source.getOpaqueDirs()));
        target.setPaths(new ArrayList<>(source.getPaths()));
        return target;
    }

    private static String sha256(Path path) {
        if (!Files.exists(path)) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    digest.update(buffer, 0, n);
                }
            }
            StringBuilder builder = new StringBuilder();
            for (byte b : digest.digest()) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (Exception e) {
            throw new ApiException(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR,
                    "failed to compute sha256: " + e.getMessage());
        }
    }

    private static void validateId(String id) {
        if (!StorageLayout.isValidId(id)) {
            throw new ApiException.BadRequest("invalid layer id: " + id);
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path) && !Files.isSymbolicLink(path)) {
            return;
        }
        Files.walkFileTree(path, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path file,
                    java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFileFailed(Path file, IOException exc)
                    throws IOException {
                Files.deleteIfExists(file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException exc)
                    throws IOException {
                Files.delete(dir);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    private static void deleteQuietly(Path path) {
        try {
            deleteRecursively(path);
        } catch (IOException ignored) {
        }
    }

    public static final class RegistryFile {
        private long nextSequence;
        private List<LayerRecord> layers = new ArrayList<>();

        public long getNextSequence() {
            return nextSequence;
        }

        public void setNextSequence(long nextSequence) {
            this.nextSequence = nextSequence;
        }

        public List<LayerRecord> getLayers() {
            return layers;
        }

        public void setLayers(List<LayerRecord> layers) {
            this.layers = layers;
        }
    }
}
