package com.hdmapreforged;

import java.util.*;

/** A binary min-heap of longs (priority in the high bits, node in the low bits). */
final class LongHeap {
    long[] items = new long[1024];
    int size;

    boolean isEmpty() {
        return size == 0;
    }

    void push(long value) {
        if (size == items.length) {
            items = Arrays.copyOf(items, size * 2);
        }
        int i = size++;
        while (i > 0) {
            int parent = (i - 1) >>> 1;
            if (items[parent] <= value) {
                break;
            }
            items[i] = items[parent];
            i = parent;
        }
        items[i] = value;
    }

    long pop() {
        long top = items[0], last = items[--size];
        int i = 0;
        while (true) {
            int child = 2 * i + 1;
            if (child >= size) {
                break;
            }
            if (child + 1 < size && items[child + 1] < items[child]) {
                child++;
            }
            if (items[child] >= last) {
                break;
            }
            items[i] = items[child];
            i = child;
        }
        items[i] = last;
        return top;
    }
}
