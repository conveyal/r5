package com.conveyal.file;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.zip.GZIPInputStream;

public abstract class FileUtils {
    /**
     * When a zip file needs to be expanded and the individual files are parsed, it's useful to create a temporary
     * directory to help facilitate that.
     * @return File temporary directory
     */
    public static File createScratchDirectory () {
        try {
            return Files.createTempDirectory("com.conveyal.file").toFile();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * This is used to make files that will be written, then closed and put into the FileStorage.
     * This method doesn't belong on the FileStorage, which deals only with immutable system-wide files.
     *
     * @param type a short string revealing what kind of file this is, just to make filenames more human readable.
     * @return File a file to write to
     */
    public static File createScratchFile (String type) {
        try {
            File tempFile = File.createTempFile("com.conveyal.file", type);
            // The file deletion shutdown hook applies to the temp file path, not the file contents.
            // If the file is moved to another path (which we often do) it will not be deleted.
            tempFile.deleteOnExit();
            return tempFile;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Create a File from an InputStream. Closes the InputStream on completion.
     * @param inputStream Stream to read from.
     * @return File
     */
    public static File createScratchFile(InputStream inputStream) {
        File scratch = createScratchFile("tmp");
        try (OutputStream outputStream = new FileOutputStream(scratch)) {
            inputStream.transferTo(outputStream);
            inputStream.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return scratch;
    }

    /**
     * In many instances you may not know the file type. Explicitly allow passing null.
     * @return File
     */
    public static File createScratchFile () {
        return createScratchFile("tmp");
    }

    /**
     * Write an OutputStream to a File.
     * @param file File to be written to.
     * @param os OutputStream to be consumed.
     */
    public static void transferFromFileTo(File file, OutputStream os) {
        InputStream is = getInputStream(file);
        try {
            is.transferTo(os);
            is.close();
            os.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Get an BufferedInputStream for a file. Read bytes from the underlying file stream without causing a system call
     * for each byte read.
     */
    public static BufferedInputStream getInputStream (File file) {
        try {
            return new BufferedInputStream(new FileInputStream(file));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Get a BufferedOutputStream for a file. Write bytes to the underlying output stream without necessarily causing a
     * call to the underlying system for each byte written.
     */
    public static BufferedOutputStream getOutputStream (File file) {
        try {
            return new BufferedOutputStream(new FileOutputStream(file));
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Check if a File is gzipped.
     */
    public static boolean isGzip (File file) {
        try {
            RandomAccessFile raf = new RandomAccessFile(file, "r");
            boolean isGzip = GZIPInputStream.GZIP_MAGIC == (raf.read() & 0xff | ((raf.read() << 8) & 0xff00));
            raf.close();
            return isGzip;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
