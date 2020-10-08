package com.conveyal.data.census;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Seamless source for the file system.
 */
public class FileSeamlessSource extends SeamlessSource {
    private File directory;

    public FileSeamlessSource(String path) {
        this.directory = new File(path);
    }

    @Override protected InputStream getInputStream(int x, int y) throws IOException {
        File dir = new File(directory, x + "");
        File file = new File(dir, y + ".pbf.gz");

        if (!file.exists())
            return null;

        return new FileInputStream(file);
    }
}
