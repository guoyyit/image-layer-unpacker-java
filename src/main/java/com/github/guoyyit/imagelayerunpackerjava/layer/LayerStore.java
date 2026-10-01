package com.github.guoyyit.imagelayerunpackerjava.layer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.guoyyit.imagelayerunpackerjava.tar.SafeTarExtractor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Persists layer metadata (one JSON file per layer) under a root directory:
 * <pre>
 *   &lt;root&gt;/staging/&lt;id&gt;.tar        staged upload, kept until commit (crash recovery source)
 *   &lt;root&gt;/layers/&lt;id&gt;/rootfs/     unpacked layer content
 *   &lt;root&gt;/layers/&lt;id&gt;/meta.json   metadata + unpack journal (status field)
 * </pre>
 * A layer whose meta says UNPACKING after a restart was interrupted and must
 * be re-unpacked from its staged tar (or discarded when the tar is gone).
 */
public class LayerStore {

    private final Path rootDir;
    private final Path layersDir;
    private final Path stagingDir;
    private final ObjectMapper mapper;

    public LayerStore(Path rootDir) {
        this.rootDir = rootDir;
        this.layersDir = rootDir.resolve("layers");
        this.stagingDir = rootDir.resolve("staging");
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    public void initDirectories() throws IOException {
        Files.createDirectories(layersDir);
        Files.createDirectories(stagingDir);
    }

    /** Load all persisted layer metadata, ordered by sequence number. */
    public List<LayerMeta> loadAll() throws IOException {
        List<LayerMeta> result = new ArrayList<>();
        if (!Files.isDirectory(layersDir)) {
            return result;
        }
        try (Stream<Path> stream = Files.list(layersDir)) {
            for (Path layerDir : stream.filter(Files::isDirectory).toList()) {
                Path metaFile = layerDir.resolve("meta.json");
                if (Files.isRegularFile(metaFile)) {
                    result.add(mapper.readValue(metaFile.toFile(), LayerMeta.class));
                }
            }
        }
        result.sort(Comparator.comparingInt(LayerMeta::getSeq));
        return result;
    }

    /** Persist metadata atomically (write to temp file, then move). */
    public synchronized void save(LayerMeta meta) throws IOException {
        Path layerDir = layerDir(meta.getId());
        Files.createDirectories(layerDir);
        Path tmp = layerDir.resolve("meta.json.tmp");
        Path target = layerDir.resolve("meta.json");
        mapper.writeValue(tmp.toFile(), meta);
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Remove all persisted state of a layer: metadata, rootfs and staged tar. */
    public synchronized void delete(String layerId) throws IOException {
        SafeTarExtractor.deleteRecursively(layerDir(layerId));
        Files.deleteIfExists(stagingTar(layerId));
    }

    /** Delete only the staged tar of a layer (after successful commit). */
    public void deleteStagingTar(String layerId) throws IOException {
        Files.deleteIfExists(stagingTar(layerId));
    }

    public Path layerDir(String layerId) {
        return layersDir.resolve(layerId);
    }

    public Path rootfsDir(String layerId) {
        return layerDir(layerId).resolve("rootfs");
    }

    public Path stagingTar(String layerId) {
        return stagingDir.resolve(layerId + ".tar");
    }

    public Path rootDir() {
        return rootDir;
    }
}
