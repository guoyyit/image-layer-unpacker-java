package com.github.guoyyit.imagelayerunpackerjava;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Quotas and storage location for the unpacker service.
 */
@ConfigurationProperties(prefix = "unpacker")
public class UnpackerProperties {
    /** Root directory holding staged tars, layer rootfs trees and metadata. */
    private String rootDir = "./data";
    /** Maximum uncompressed size of a single layer in bytes. */
    private long maxLayerBytes = 256L * 1024 * 1024;
    /** Maximum total bytes occupied by committed layers. */
    private long maxTotalBytes = 2L * 1024 * 1024 * 1024;
    /** Maximum number of entries allowed in a single archive. */
    private long maxEntriesPerLayer = 100_000;

    public String getRootDir() {
        return rootDir;
    }

    public void setRootDir(String rootDir) {
        this.rootDir = rootDir;
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

    public long getMaxEntriesPerLayer() {
        return maxEntriesPerLayer;
    }

    public void setMaxEntriesPerLayer(long maxEntriesPerLayer) {
        this.maxEntriesPerLayer = maxEntriesPerLayer;
    }
}
