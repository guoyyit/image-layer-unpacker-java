package com.github.guoyyit.imagelayerunpackerjava.view;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Read-only handle over the committed layers, pinned to a consistent snapshot.
 * Whiteouts and opaque-dir markers of upper layers hide lower-layer content;
 * marker files themselves are never exposed.
 */
public interface MergedView extends AutoCloseable {

    /** List the children of a directory in the merged view. */
    List<ViewEntry> list(String path) throws IOException;

    /** Look up a single path, or null when it does not exist in the merged view. */
    ViewEntry stat(String path) throws IOException;

    /** Open the content of a regular file. */
    InputStream openFile(String path) throws IOException;

    /** Release the pinned layers. Idempotent. */
    @Override
    void close();
}
