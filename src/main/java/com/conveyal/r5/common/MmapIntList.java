package com.conveyal.r5.common;

import gnu.trove.TIntCollection;
import gnu.trove.function.TIntFunction;
import gnu.trove.iterator.TIntIterator;
import gnu.trove.list.TIntList;
import gnu.trove.procedure.TIntProcedure;

import java.io.File;
import java.io.IOError;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.util.Collection;
import java.util.NoSuchElementException;
import java.util.Random;

/**
 * TIntArrayList replacement which stores data in mmap file
 */
public class MmapIntList implements TIntList{

    volatile int size=0;

    final File f;
    final FileChannel c;
    final ByteBuffer buf;
    final FileLock lock;

    public MmapIntList(){
        this(32);
    }

    public MmapIntList(int initialSize){
        this(null, initialSize);
    }

    public MmapIntList(File f, int initialSize){
        try {
            if(f == null)
                f = File.createTempFile("mapdb","intlist");
            this.f = f;
            checkIndex(initialSize);
            f.deleteOnExit();
            c = new RandomAccessFile(f,"rw").getChannel();
            lock = c.tryLock();
            buf = c.map(FileChannel.MapMode.READ_WRITE,0, Integer.MAX_VALUE);
            //write at maximal offset, to preallocate space
            if(initialSize>0)
                buf.putInt(initialSize*4-4,0);
        } catch (IOException e) {
            throw new IOError(e);
        }
    }


    void checkIndex(long index){
        if(index<0)
            throw new AssertionError("index is negative");
        if(index*4>Integer.MAX_VALUE)
            throw new AssertionError("index is too large");
    }

    @Override
    public boolean add(int val){
        checkIndex(size+4);
        buf.putInt(size*4, val);
        size+=1;
        return true;
    }

    @Override
    public void add(int[] vals) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public void add(int[] vals, int offset, int length) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public void insert(int offset, int value) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public void insert(int offset, int[] values) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public void insert(int offset, int[] values, int valOffset, int len) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public int get(int index){
        if(index>=size)
            throw new NoSuchElementException("index>=size");
        return buf.getInt(index*4);
    }

    @Override
    public int set(int index, int val){
        if(index>=size)
            throw new NoSuchElementException("index>=size");
        int oldVal = buf.getInt(index*4);
        buf.putInt(index*4, val);
        return oldVal;
    }

    @Override
    public void set(int offset, int[] values) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public void set(int offset, int[] values, int valOffset, int length) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public int replace(int offset, int val) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public boolean remove(int value) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public boolean containsAll(Collection<?> collection) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public boolean containsAll(TIntCollection collection) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public boolean containsAll(int[] array) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public boolean addAll(Collection<? extends Integer> collection) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public boolean addAll(TIntCollection collection) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public boolean addAll(int[] array) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public boolean retainAll(Collection<?> collection) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public boolean retainAll(TIntCollection collection) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public boolean retainAll(int[] array) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public boolean removeAll(Collection<?> collection) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public boolean removeAll(TIntCollection collection) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public boolean removeAll(int[] array) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public int removeAt(int offset) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    /**
     * Removes <tt>length</tt> values from the list, starting at
     * <tt>offset</tt>
     *
     * @param offset an <code>int</code> value
     * @param length an <code>int</code> value
     */
    public void remove( int offset, int length ){
        if(1L*size<offset+length)
            throw new NoSuchElementException();
        checkIndex(offset);
        checkIndex(1L*offset+1L*length);

        //TODO this can be optimized by direct copy
        //copy values
        for(int i=0;i<size-offset-length;i++){
            int val = buf.getInt((offset+length+i)*4);
            buf.putInt((offset+i)*4, val);
        }

        size-=length;
    }

    @Override
    public void transformValues(TIntFunction function) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public void reverse() {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public void reverse(int from, int to) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public void shuffle(Random rand) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public TIntList subList(int begin, int end) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public int[] toArray() {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public int[] toArray(int offset, int len) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public int[] toArray(int[] dest) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public int[] toArray(int[] dest, int offset, int len) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public int[] toArray(int[] dest, int source_pos, int dest_pos, int len) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public boolean forEach(TIntProcedure procedure) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public boolean forEachDescending(TIntProcedure procedure) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public void sort() {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public void sort(int fromIndex, int toIndex) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public void fill(int val) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public void fill(int fromIndex, int toIndex, int val) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public int binarySearch(int value) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public int binarySearch(int value, int fromIndex, int toIndex) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public int indexOf(int value) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public int indexOf(int offset, int value) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public int lastIndexOf(int value) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public int lastIndexOf(int offset, int value) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public boolean contains(int value) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public TIntIterator iterator() {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public TIntList grep(TIntProcedure condition) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public TIntList inverseGrep(TIntProcedure condition) {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public int max() {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public int min() {
        throw new UnsupportedOperationException("not yet implemented");
    }

    @Override
    public int sum() {
        throw new UnsupportedOperationException("not yet implemented");
    }

    /** releases resources and deletes underlying temp file*/
    public void close(){
        try {
            lock.release();
            c.close();
            f.delete();
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    @Override
    public int getNoEntryValue() {
        throw new UnsupportedOperationException("not yet implemented");
    }

    public int size() {
        return size;
    }

    @Override
    public boolean isEmpty() {
        return size==0;
    }
}
