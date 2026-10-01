package com.github.guoyyit.imagelayerunpackerjava.layer;

/**
 * Raised when a layer archive cannot be validated or unpacked.
 */
public class UnpackException extends Exception {
    public UnpackException(String message) {
        super(message);
    }

    public UnpackException(String message, Throwable cause) {
        super(message, cause);
    }
}
