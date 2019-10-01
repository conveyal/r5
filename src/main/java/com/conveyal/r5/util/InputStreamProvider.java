package com.conveyal.r5.util;

import java.io.IOException;
import java.io.InputStream;

/**
 * Created by abyrd on 2019-10-01
 */
public interface InputStreamProvider {

    InputStream getInputStream() throws IOException;

}
