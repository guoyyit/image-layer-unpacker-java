package com.github.guoyyit.imagelayerunpackerjava.model;

import java.util.ArrayList;
import java.util.List;

public class UnpackReport {

    private long payloadBytes;
    private long unpackedBytes;
    private String sha256 = "";
    private List<String> whiteouts = new ArrayList<>();
    private List<String> opaqueDirs = new ArrayList<>();
    private List<String> paths = new ArrayList<>();

    public long getPayloadBytes() {
        return payloadBytes;
    }

    public void setPayloadBytes(long payloadBytes) {
        this.payloadBytes = payloadBytes;
    }

    public long getUnpackedBytes() {
        return unpackedBytes;
    }

    public void setUnpackedBytes(long unpackedBytes) {
        this.unpackedBytes = unpackedBytes;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
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

    public List<String> getPaths() {
        return paths;
    }

    public void setPaths(List<String> paths) {
        this.paths = paths;
    }
}
