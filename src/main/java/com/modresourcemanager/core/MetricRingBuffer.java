package com.modresourcemanager.core;

import java.util.ArrayList;
import java.util.List;

public final class MetricRingBuffer<T> {
    private final Object[] values;
    private int head;
    private int size;

    public MetricRingBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.values = new Object[capacity];
    }

    public synchronized void add(T value) {
        values[head] = value;
        head = (head + 1) % values.length;
        if (size < values.length) {
            size++;
        }
    }

    public synchronized List<T> snapshot() {
        List<T> result = new ArrayList<>(size);
        if (size == 0) {
            return result;
        }
        int start = size < values.length ? 0 : head;
        for (int i = 0; i < size; i++) {
            int index = (start + i) % values.length;
            @SuppressWarnings("unchecked")
            T value = (T) values[index];
            result.add(value);
        }
        return result;
    }

    public synchronized T latest() {
        if (size == 0) {
            return null;
        }
        int latestIndex = (head - 1 + values.length) % values.length;
        @SuppressWarnings("unchecked")
        T value = (T) values[latestIndex];
        return value;
    }

    public synchronized int size() {
        return size;
    }

    public synchronized void clear() {
        java.util.Arrays.fill(values, null);
        head = 0;
        size = 0;
    }
}
