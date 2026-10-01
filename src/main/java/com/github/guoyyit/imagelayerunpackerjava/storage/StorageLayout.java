package com.github.guoyyit.imagelayerunpackerjava.storage;

import java.io.IOException;
import java.nio.file.Path;

import org.springframework.stereotype.Component;
import org.springframework.http.HttpStatus;

import com.github.guoyyit.imagelayerunpackerjava.config.ServiceProperties;
import com.github.guoyyit.imagelayerunpackerjava.error.ApiException;

@Component
public class StorageLayout {

    private static final String LAYERS_DIR = "layers";
    private static final String REGISTRY_FILE = "registry.json";
    private static final String ROOT_DIR = "root";
    private static final String BLOB_FILE = "blob.tar";
    private static final String META_FILE = "meta.json";

    private final ServiceProperties properties;
    private Path root;

    public StorageLayout(ServiceProperties properties) {
        this.properties = properties;
    }

    public Path root() {
        return root;
    }

    public Path layersDir() {
        return root.resolve(LAYERS_DIR);
    }

    public Path registryFile() {
        return root.resolve(REGISTRY_FILE);
    }

    public Path layerDir(String id) {
        return layersDir().resolve(id);
    }

    public Path layerRoot(String id) {
        return layerDir(id).resolve(ROOT_DIR);
    }

    public Path layerBlob(String id) {
        return layerDir(id).resolve(BLOB_FILE);
    }

    public Path layerMeta(String id) {
        return layerDir(id).resolve(META_FILE);
    }

    public Path stagingDir(String id, String token) {
        return layersDir().resolve(id + ".staging-" + token);
    }

    public void initialize() {
        if (root == null) {
            Path configured = properties.getStorageDir();
            if (configured == null) {
                try {
                    root = java.nio.file.Files.createTempDirectory("image-layer-unpacker-");
                } catch (IOException e) {
                    throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                            "failed to create storage directory: " + e.getMessage());
                }
            } else {
                root = configured.toAbsolutePath().normalize();
            }
        }
        try {
            java.nio.file.Files.createDirectories(layersDir());
        } catch (IOException e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "failed to initialize storage: " + e.getMessage());
        }
    }

    public static boolean isValidId(String id) {
        if (id == null || id.isEmpty() || id.length() > 128) {
            return false;
        }
        return id.matches("[A-Za-z0-9._-]+")
                && !".".equals(id)
                && !"..".equals(id)
                && !id.startsWith(".");
    }
}
