package com.github.fortress4j.storage;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

public class InMemoryStorage<K,V> implements Storage<K,V> {


    private final ConcurrentHashMap<K,V> storage = new ConcurrentHashMap<>();



    @Override
    public V compute(K key, BiFunction<K, V, V> remappingFunction) {
        return storage.compute(key, remappingFunction);
    }
}
