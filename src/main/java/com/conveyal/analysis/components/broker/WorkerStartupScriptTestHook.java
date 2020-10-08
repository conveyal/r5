package com.conveyal.analysis.components.broker;

import com.google.common.io.ByteStreams;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.MessageFormat;

/**
 * Test hook (main class) that reads the worker startup script and substitutes in values.
 */
public class WorkerStartupScriptTestHook {
    public static void main (String... args) throws Exception {
        InputStream scriptIs = Broker.class.getClassLoader().getResourceAsStream("worker.sh");
        ByteArrayOutputStream scriptBaos = new ByteArrayOutputStream();
        ByteStreams.copy(scriptIs, scriptBaos);
        scriptIs.close();
        scriptBaos.close();
        String scriptTemplate = scriptBaos.toString();

        String workerDownloadUrl = "https://r5-builds.s3.amazonaws.com/v2.3.1.jar";
        String logGroup = "test-log-group";
        String workerConfigString = "key=val\nkey2=val\n";

        String script = MessageFormat.format(scriptTemplate, workerDownloadUrl, logGroup, workerConfigString);
        System.out.println(script);
    }
}
