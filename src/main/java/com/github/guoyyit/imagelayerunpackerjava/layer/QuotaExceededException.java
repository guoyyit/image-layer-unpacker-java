package com.github.guoyyit.imagelayerunpackerjava.layer;

/**
 * The layer was rejected because a quota (single-layer size, entry count or
 * total occupancy) would be exceeded. Unlike corrupt/unsafe archives, a
 * quota-rejected layer leaves no trace: the service keeps its previous state.
 */
public class QuotaExceededException extends UnpackException {
    public QuotaExceededException(String message) {
        super(message);
    }
}
