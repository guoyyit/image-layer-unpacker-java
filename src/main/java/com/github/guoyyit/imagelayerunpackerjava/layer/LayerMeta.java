package com.github.guoyyit.imagelayerunpackerjava.layer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Persistent metadata of a single layer. Mutable, serialized to JSON on every change.
 */
public class LayerMeta {
    private String id;
    private LayerStatus status;
    private int seq;
    private long sizeBytes;
    private long fileCount;
    private Instant createdAt;
    private Instant updatedAt;
    private String error;
    private List<String> whiteouts = new ArrayList<>();
    private List<String> opaqueDirs = new ArrayList<>();

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public LayerStatus getStatus() {
        return status;
    }

    public void setStatus(LayerStatus status) {
        this.status = status;
    }

    public int getSeq() {
        return seq;
    }

    public void setSeq(int seq) {
        this.seq = seq;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public long getFileCount() {
        return fileCount;
    }

    public void setFileCount(long fileCount) {
        this.fileCount = fileCount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getError() {
        return error;
    }

    public void setError(String error) {
        this.error = error;
    }

    public List<String> getWhiteouts() {
        return whiteouts;
    }

    public void setWhiteouts(List<String> whiteouts) {
        this.whiteouts = whiteouts;
    }

    public List<String> getOpaqueDirs() {
        return opaqueDirs;
    }

    public void setOpaqueDirs(List<String> opaqueDirs) {
        this.opaqueDirs = opaqueDirs;
    }
}
