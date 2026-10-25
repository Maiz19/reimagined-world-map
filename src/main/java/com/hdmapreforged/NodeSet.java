package com.hdmapreforged;

import java.util.*;
import lombok.*;

/**
 * A set of nodes as bits per {@link NodeTable#block}, made as blocks are touched: a flood of the world takes 1 MB. Also
 * over blocks made elsewhere ({@link NodeTable#reached}): {@code bits[b]} holds block {@code b}'s 4,096 nodes.
 */
@AllArgsConstructor
final class NodeSet {
    final IntMap blocks;
    long[][] bits;
    int count;

    NodeSet() {
        this(new IntMap(1 << 10), new long[64][], 0);
    }

    /** Whether the node was not in the set before. */
    boolean add(int node) {
        int key = NodeTable.block(node), b = blocks.get(key);
        if (b == IntMap.MISSING) {
            b = count++;
            if (b == bits.length) {
                bits = Arrays.copyOf(bits, b * 2);
            }
            bits[b] = new long[64];
            blocks.put(key, b);
        }
        int i = NodeTable.index(node);
        long mask = 1L << i;
        if ((bits[b][i >> 6] & mask) != 0) {
            return false;
        }
        bits[b][i >> 6] |= mask;
        return true;
    }

    boolean contains(int node) {
        int b = blocks.get(NodeTable.block(node)), i = NodeTable.index(node);
        return b != IntMap.MISSING && (bits[b][i >> 6] & 1L << i) != 0;
    }
}
