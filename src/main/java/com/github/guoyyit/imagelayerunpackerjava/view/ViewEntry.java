package com.github.guoyyit.imagelayerunpackerjava.view;

/**
 * One node of the merged view.
 */
public record ViewEntry(String name, String path, EntryType type, long size) {

    public enum EntryType {
        FILE, DIRECTORY, SYMLINK
    }
}
