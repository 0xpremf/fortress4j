package com.github.fortress4j.storage;

import java.util.function.BiFunction;

public interface Storage<K,V> {
    V compute(
            K key,
            BiFunction<K,V,V> remappingFunction
    );
}
