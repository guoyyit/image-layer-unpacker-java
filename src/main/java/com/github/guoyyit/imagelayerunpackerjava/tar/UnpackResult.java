package com.github.guoyyit.imagelayerunpackerjava.tar;

import java.nio.file.Path;
import java.util.List;

/**
 * Outcome of a successful unpack: where the rootfs lives, which paths are
 * whiteouts/opaque dirs, and basic stats.
 */
public record UnpackResult(Path rootfsDir,
                           List<String> whiteouts,
                           List<String> opaqueDirs,
                           long sizeBytes,
                           long fileCount) {
}
