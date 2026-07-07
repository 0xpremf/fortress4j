package com.github.fortress4j.storage;

import com.github.fortress4j.WindowState;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;

public class InMemoryStorage<T> implements StorageImpl.storage<T> {


    private final ConcurrentHashMap<String, T> storage = new ConcurrentHashMap<>();


}
