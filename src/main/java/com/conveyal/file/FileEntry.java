package com.conveyal.file;

import java.io.File;

public record FileEntry(FileStorageKey key, File file) {
}
