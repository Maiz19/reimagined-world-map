package com.hdmapreforged;

import java.util.*;

/** Int keys to int values in open addressing arrays, for the search's hot loop (no boxing). Not thread-safe. */
final class IntMap {
    /** Returned for a missing key; never a key itself. */
    static final int MISSING = Integer.MIN_VALUE;
    int[] keys, values;
    int size, mask;

    IntMap(int capacity) {
        allocate(Integer.highestOneBit(Math.max(16, capacity) - 1) << 1);
    }

    void allocate(int n) {
        keys = new int[n];
        Arrays.fill(keys, MISSING);
        values = new int[n];
        mask = n - 1;
        size = 0;
    }

    int slot(int key) {
        int h = key * 0x9E3779B9, i = (h ^ h >>> 16) & mask;
        while (keys[i] != MISSING && keys[i] != key) {
            i = (i + 1) & mask;
        }
        return i;
    }

    int get(int key) {
        int i = slot(key);
        return keys[i] == MISSING ? MISSING : values[i];
    }

    void put(int key, int value) {
        int i = slot(key);
        if (keys[i] == MISSING) {
            if ((size + 1) * 2 > keys.length) {
                grow();
                i = slot(key);
            }
            keys[i] = key;
            size++;
        }
        values[i] = value;
    }

    void grow() {
        var oldKeys = keys;
        var oldValues = values;
        allocate(oldKeys.length * 2);
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldKeys[i] != MISSING) {
                put(oldKeys[i], oldValues[i]);
            }
        }
    }
}
