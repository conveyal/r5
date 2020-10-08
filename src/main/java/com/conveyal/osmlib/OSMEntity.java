package com.conveyal.osmlib;

import com.beust.jcommander.internal.Lists;

import java.io.Serializable;
import java.util.List;

public abstract class OSMEntity implements Serializable {

    private static final long serialVersionUID = 1L;

    public static enum Type {
        NODE, WAY, RELATION;
    }

    public List<Tag> tags;

    public static class Tag implements Serializable {
        public String key, value;
        public Tag (String key, String value) {
            this.key = key;
            this.value = value != null ? value : "";
        }

        @Override
        public String toString() {
            return key + "=" + value;
        }

        @Override
        public boolean equals (Object other) {
            if (!(other instanceof Tag)) return false;
            Tag otherTag = (Tag) other;
            return this.key.equals(otherTag.key) && this.value.equals(otherTag.value);
        }

    }

    /** Return the tag value for the given key. Returns null if the tag key is not present. */
    public String getTag(String key) {
        if (tags == null) return null;
        for (Tag tag : tags) {
            if (tag.key.equals(key)) {
                return tag.value;
            }
        }
        return null;
    }
    
    public boolean hasTag(String key) {
        return (getTag(key) != null);
    }

    public boolean hasTag(String key, String value) {
        return (value.equals(getTag(key)));
    }

    public boolean hasNoTags() {
        return tags == null || tags.isEmpty();
    }

    public boolean tagIsTrue (String key) {
        String value = getTag(key);
        return value != null && ("yes".equalsIgnoreCase(value) || "true".equalsIgnoreCase(value) || "1".equals(value));
    }

    public boolean tagIsFalse (String key) {
        String value = getTag(key);
        return value != null && ("no".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value) || "0".equals(value));
    }

    /** Set the tags from a string in the format key=value;key=value */
    public void setTagsFromString (String s) {
        for (String tag : s.split(";")) {
            String[] kv = tag.split("=", 2);
            if (kv.length == 2) {
                addTag(kv[0], kv[1]);
            } else {
                addTag(kv[0], "");
            }
        }
    }

    public void addTag (String key, String value) {
        if (tags == null) {
            tags = Lists.newArrayList();
        }
        tags.add(new Tag(key, value));
    }

    public void addOrReplaceTag (String key, String value) {
        if (tags == null) {
            tags = Lists.newArrayList();
        }
        for (Tag tag : tags) {
            if (tag.key.equalsIgnoreCase(key)) {
                tag.value = value;
                return;
            }
        }
        tags.add(new Tag(key, value));
    }

    public boolean tagsEqual (OSMEntity other) {
        if (this.hasNoTags()) {
            return other.hasNoTags();
        }
        return this.tags.equals(other.tags);
    }

    /** This feels strange because we're using Enums to duplicate Java type data (Node.class) */
    public abstract Type getType();
    
}
