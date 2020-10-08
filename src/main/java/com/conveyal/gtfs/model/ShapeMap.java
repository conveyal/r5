package com.conveyal.gtfs.model;

import org.mapdb.Fun;
import org.mapdb.Fun.Tuple2;

import java.util.AbstractMap;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentNavigableMap;

/**
 * A map of a single shape_id with points indexed by shape_point_sequence.
 * Backed by a submap, but eliminates the need to refer to shape points always by shape ID.
 * @author mattwigway
 */
public class ShapeMap implements Map<Integer, Shape> {
    private String shapeId;
    
    /** A map from (shape_id, shape_pt_sequence) to shapes */
    private Map<Tuple2, Shape> wrapped;

    public ShapeMap (ConcurrentNavigableMap<Tuple2, Shape> allShapes, String shapeId) {
        this.wrapped = allShapes.subMap(
                new Tuple2 (shapeId, 0),
                new Tuple2 (shapeId, Fun.HI)
                );
        this.shapeId = shapeId;
    }

    @Override
    public int size() {
        return wrapped.size();
    }

    @Override
    public boolean isEmpty() {
        return wrapped.isEmpty();
    }

    @Override
    public boolean containsKey(Object key) {
        return wrapped.containsKey(makeKey(key));
    }

    @Override
    public boolean containsValue(Object value) {
        return wrapped.containsValue(value);
    }

    @Override
    public Shape get(Object key) {		
        return wrapped.get(makeKey(key));
    }

    @Override
    public Shape put(Integer key, Shape value) {
        return wrapped.put(makeKey(key), value);
    }

    @Override
    public Shape remove(Object key) {
        return wrapped.remove(makeKey(key));
    }

    @Override
    public void putAll(Map<? extends Integer, ? extends Shape> m) {
        for (Integer i : m.keySet()) {
            wrapped.put(makeKey(i), m.get(i));
        }
    }

    @Override
    public void clear() {
        wrapped.clear();
    }

    @Override
    public Collection<Shape> values() {
        return wrapped.values();
    }

    // these two are hard because the sets have to update the corresponding map.
    // We currently just expose them as immutable sets in RAM, since all of the modification operations are optional. 
    @Override
    public Set<Integer> keySet() {
        // use a linkedhashset so values come out in order
        Set<Integer> ret = new LinkedHashSet<>();

        for (Tuple2<String, Integer> t : wrapped.keySet()) {
            ret.add(t.b);
        }

        // Don't let the user modify the set as it won't do what they expect (change the map)
        return Collections.unmodifiableSet(ret);
    }

    @Override
    public Set<Map.Entry<Integer, Shape>> entrySet() {
        // it's ok to pull all the values into RAM as this represents a single shape not all shapes
        // use a linkedhashset so values come out in order
        Set<Entry<Integer, Shape>> ret = new LinkedHashSet<>();

        for (Map.Entry<Tuple2, Shape> e : wrapped.entrySet()) {
            ret.add(new AbstractMap.SimpleImmutableEntry(e.getKey().b, e.getValue()));
        }

        return Collections.unmodifiableSet(ret);
    }

    private Tuple2<String, Integer> makeKey (Object i) {
        return new Tuple2<String, Integer> (this.shapeId, (Integer) i);
    }

}
