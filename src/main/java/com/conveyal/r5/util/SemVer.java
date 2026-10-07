package com.conveyal.r5.util;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SemVer implements Comparable<SemVer> {
    public final int major;
    public final int minor;
    public final int patch;
    public final String qualifier; // Extra info after the dash, if any

    // Regular expression breakdown:
    // ^v                   : must start with "v"
    // (\\d+)               : major version (one or more digits)
    // \\. (\\d+)           : minor version (one or more digits), preceded by a dot
    // (?:\\.(\\d+))?       : optional patch version, preceded by a dot
    // (?:-(.+))?           : optional qualifier after a dash (e.g., "-147-gbb4ecbc")
    // $                    : end of string
    private static final Pattern pattern = Pattern.compile("^v(\\d+)\\.(\\d+)(?:\\.(\\d+))?(?:-(.+))?$");

    // Constructor that parses version strings
    public SemVer(String version) {
        if (version == null) {
            throw new IllegalArgumentException("Version string cannot be null");
        }

        Matcher matcher = pattern.matcher(version);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid version string: " + version);
        }
        this.major = Integer.parseInt(matcher.group(1));
        this.minor = Integer.parseInt(matcher.group(2));
        this.patch = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));
        this.qualifier = matcher.group(4) == null ? "" : matcher.group(4);
    }

    /**
     * Compares this version to another based on major, minor, and patch numbers.
     * If these are equal, then a version without a qualifier is considered higher than one with a qualifier.
     */
    @Override
    public int compareTo(SemVer other) {
        if (this.major != other.major) return Integer.compare(this.major, other.major);
        if (this.minor != other.minor) return Integer.compare(this.minor, other.minor);
        if (this.patch != other.patch) return Integer.compare(this.patch, other.patch);

        // Compare qualifiers lexicographically.
        return this.qualifier.compareTo(other.qualifier);
    }

    /**
     * Compare if version "a" is greater than or equal to version "b".
     *
     * @param a SemVer parseable string
     * @param b Semver parseable string
     * @return true if a >= b
     */
    public static boolean gte(String a, String b) {
        return new SemVer(a).compareTo(new SemVer(b)) >= 0;
    }
}
