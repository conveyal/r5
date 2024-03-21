package com.conveyal.file;

/**
 * Combines a url for downloading a file, which might include a globally unique but human-annoying UUID, with a
 * suggested human-readable name for that file when saved by an end user. The humanName may not be globally unique,
 * so is only appropriate for cases where it doesn't need to be machine discoverable using a UUID. The humanName can
 * be used as the download attribute of an HTML link, or as the attachment name in a content-disposition header.
 * Instances of this record are intended to be serialized to JSON as an HTTP API response.
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
     * Given an arbitrary string, make it safe for use in a friendly human-readable filename. This can yield non-unique
     * strings and is intended for files downloaded by end users that do not need to be machine-discoverable by unique
     * IDs. A length of up to 255 characters will work with most filesystems and within ZIP files. In all names we
     * generate, the end of the name more uniquely identifies it (contains a fragment of a hex object ID or contains
     * the distinguishing factors such as cutoff and percentile for files within a ZIP archive). Therefore, we truncate
     * to a suffix rather than a prefix when the name is too long. We keep the length somewhat under 255 in case some
     * other short suffix needs to be appended before use as a filename.
     * Note that this will strip dot characters out of the string, so any dot and extension must be suffixed later.
     */
    public static String filenameCleanString (String original) {
        String ret = original.replaceAll("\\W+", "_");
        if (ret.length() > TRUNCATE_FILENAME_CHARS) {
            ret = ret.substring(ret.length() - TRUNCATE_FILENAME_CHARS, ret.length());
        }
        return ret;
    }

    public static UrlWithHumanName fromCleanedName (String url, String rawHumanName, String humanExtension) {
        String humanName = UrlWithHumanName.filenameCleanString(rawHumanName) + "." + humanExtension;
        return new UrlWithHumanName(url, humanName);
    }
}
