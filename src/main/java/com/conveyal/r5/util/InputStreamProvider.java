package com.conveyal.r5.util;

import java.io.IOException;
import java.io.InputStream;

/**
 * This interface is used in cases where we need to repeatedly request the same input stream, as when loading
 * from uploaded CSV files.
 */
public interface InputStreamProvider {

    /**
     * @return a new input stream properly wrapped to buffer the input and remove UTF-8 BOM as needed.
     */
    InputStream getInputStream() throws IOException;

}
