package com.github.guoyyit.imagelayerunpackerjava.layer;

/**
 * Lifecycle of a layer.
 */
public enum LayerStatus {
    /** Tar received/staged, unpack not finished yet. */
    UNPACKING,
    /** Fully unpacked and visible in the merged view. */
    COMMITTED,
    /** Unpack failed; layer is inactive and can be retried or deleted. */
    FAILED
}
