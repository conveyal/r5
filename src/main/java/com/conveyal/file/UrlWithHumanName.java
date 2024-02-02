package com.conveyal.file;

/**
 * Combines a url for downloading a file, which might include a globally unique but human-annoying UUID, with a
 * suggested human-readable name for that file when saved by an end user. The humanName may not be globally unique,
 * so is only appropriate for cases where it doesn't need to be machine discoverable using a UUID. The humanName can
 * be used as the download attribute of an HTML link, or as the attachment name in a content-disposition header.
 * Instances of this record are intended to be serialized to JSON as an HTTP API response.
 * // TODO make this into a class with factory methods and move static cleanFilename method here.
 */
public class UrlWithHumanName {
    public final String url;
    public final String humanName;

    public UrlWithHumanName (String url, String humanName) {
        this.url = url;
        this.humanName = humanName;
    }

    private static final int TRUNCATE_FILENAME_CHARS = 220;

    /**
     * Given an arbitrary string, make it safe for use as a friendly human-readable filename.
     * This can yield non-unique strings and is intended for files downloaded by end users that do not need to be
     * machine-discoverable by unique IDs.
     */
    public static String filenameCleanString (String original) {
        String ret = original.replaceAll("\\W+", "_");
        if (ret.length() > TRUNCATE_FILENAME_CHARS) {
            ret = ret.substring(0, TRUNCATE_FILENAME_CHARS);
        }
        return ret;
    }

    public static UrlWithHumanName fromCleanedName (String url, String rawHumanName, String humanExtension) {
        String humanName = UrlWithHumanName.filenameCleanString(rawHumanName) + "." + humanExtension;
        return new UrlWithHumanName(url, humanName);
    }
}
