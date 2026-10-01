package com.github.guoyyit.imagelayerunpackerjava.config;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "imagelayer")
public class ServiceProperties {

    private Path storageDir;
    private long maxLayerBytes = 10L * 1024 * 1024;
    private long maxTotalBytes = 100L * 1024 * 1024;

    public Path getStorageDir() {
        return storageDir;
    }

    public void setStorageDir(Path storageDir) {
        this.storageDir = storageDir;
    }

    public long getMaxLayerBytes() {
        return maxLayerBytes;
    }

    public void setMaxLayerBytes(long maxLayerBytes) {
        this.maxLayerBytes = maxLayerBytes;
    }

    public long getMaxTotalBytes() {
        return maxTotalBytes;
    }

    public void setMaxTotalBytes(long maxTotalBytes) {
        this.maxTotalBytes = maxTotalBytes;
    }
}
