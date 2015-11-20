package com.conveyal.r5.common;


import org.junit.Test;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.assertEquals;

public class MmapIntListTest {

    @Test public void putGet() throws IOException {
        MmapIntList l = new MmapIntList(File.createTempFile("mapdb","intlist"),10);

        l.add(100);
        l.add(1000);
        l.add(10000);
        assertEquals(100, l.get(0));
        assertEquals(1000, l.get(1));
        assertEquals(10000, l.get(2));
        l.set(1,1001);
        assertEquals(100, l.get(0));
        assertEquals(1001, l.get(1));
        assertEquals(10000, l.get(2));

        l.close();
    }

    @Test public void remove() throws IOException {
        MmapIntList l = new MmapIntList(File.createTempFile("mapdb","intlist"),1000);

        for(int i=0;i<1000;i++) {
            l.add(i*2);
        }
        for(int i=0;i<1000;i++) {
            assertEquals(i*2, l.get(i));
        }

        assertEquals(1000,l.size());

        l.remove(100, 200);
        for(int i=0;i<100;i++) {
            assertEquals(i*2, l.get(i));
        }

        assertEquals(1000-200, l.size());
        for(int i=0;i<100;i++) {
            assertEquals(i*2, l.get(i));
        }
        for(int i=100;i<1000-200;i++) {
            assertEquals((i+200)*2, l.get(i));
        }

        l.close();
    }

}