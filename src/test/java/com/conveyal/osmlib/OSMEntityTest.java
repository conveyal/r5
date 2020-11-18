package com.conveyal.osmlib;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class OSMEntityTest {

    @Test
    public void testCreateTag()
    {
        OSMEntity.Tag tag = new OSMEntity.Tag("key","value");
        assertEquals(tag.key, "key");
        assertEquals(tag.value, "value");
    }
    
    @Test
    public void testTagged(){
    	class TaggedTester extends OSMEntity{
			private static final long serialVersionUID = 1L;

			@Override
			public Type getType() {
				// TODO Auto-generated method stub
				return null;
			}}
    	
    	TaggedTester tt = new TaggedTester();
    	assertTrue(tt.hasNoTags());
    	
    	tt.addTag("key", "value");
    	
    	assertFalse(tt.hasNoTags());
    	assertTrue(tt.hasTag("key"));
    	assertTrue(tt.hasTag("key", "value"));

    	tt.setTagsFromString("foo=true;bar=false");
    	
    	assertTrue(tt.hasTag("foo", "true"));
    	assertTrue(tt.tagIsTrue("foo"));
    	assertTrue(tt.tagIsFalse("bar"));
    	
    	assertEquals(tt.getTag("key"), "value");
    	assertEquals(tt.getTag("foo"), "true");
    }
}
