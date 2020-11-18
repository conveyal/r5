package com.conveyal.osmlib;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class OSMEntityTest {

    @Test
    public void testCreateTag()
    {
        OSMEntity.Tag tag = new OSMEntity.Tag("key","value");
        Assertions.assertEquals(tag.key, "key");
        Assertions.assertEquals(tag.value, "value");
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
    	Assertions.assertTrue(tt.hasNoTags());
    	
    	tt.addTag("key", "value");
    	
    	Assertions.assertFalse(tt.hasNoTags());
    	Assertions.assertTrue(tt.hasTag("key"));
    	Assertions.assertTrue(tt.hasTag("key", "value"));

    	tt.setTagsFromString("foo=true;bar=false");
    	
    	Assertions.assertTrue(tt.hasTag("foo", "true"));
    	Assertions.assertTrue(tt.tagIsTrue("foo"));
    	Assertions.assertTrue(tt.tagIsFalse("bar"));
    	
    	Assertions.assertEquals(tt.getTag("key"), "value");
    	Assertions.assertEquals(tt.getTag("foo"), "true");
    }
}
