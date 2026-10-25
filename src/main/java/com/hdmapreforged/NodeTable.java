package com.hdmapreforged;

import java.util.*;

/**
 * Search state per node, the best cost and how it was reached, in blocks of 64 × 64 nodes made when first touched: no
 * table growing by copies, and no array large enough to weigh on the garbage collector (a flood touches about 700).
 */
final class NodeTable {
    /** How the start was reached. A step from the neighbour in direction d is {@code -1 - d}, a jump its edge index. */
    static final int START = -9, SIZE = 64 * 64;
    final IntMap blocks = new IntMap(1 << 11);
    int[][] costs = new int[64][], vias = new int[64][];
    int count;
    /** The nodes reached. */
    int size;
    /** The block last looked up (most lookups are its neighbours'). */
    int lastKey = -1, last;

    /** A node's block: 64 × 64 tiles of a floor, or 64 × 64 sea blocks. */
    static int block(int node) {
        return Tiles.isSea(node) ? 1 << 18 | (node >> 18 & 63) << 6 | (node >> 6 & 63) : node >>> 20 << 8 | (node >> 6 & 255);
    }

    /** A node's place in its block. */
    static int index(int node) {
        return (node >> (Tiles.isSea(node) ? 12 : 14) & 63) << 6 | (node & 63);
    }

    /** The node's block, made when {@code make}; else -1 when there is none. */
    int at(int node, boolean make) {
        int key = block(node);
        if (key == lastKey) {
            return last;
        }
        int b = blocks.get(key);
        if (b == IntMap.MISSING) {
            if (!make) {
                return -1;
            }
            b = count++;
            if (b == costs.length) {
                costs = Arrays.copyOf(costs, b * 2);
                vias = Arrays.copyOf(vias, b * 2);
            }
            costs[b] = new int[SIZE];
            Arrays.fill(costs[b], Integer.MAX_VALUE);
            vias[b] = new int[SIZE];
            blocks.put(key, b);
        }
        lastKey = key;
        last = b;
        return b;
    }

    /** {@link Integer#MAX_VALUE} when not reached. */
    int cost(int node) {
        int b = at(node, false);
        return b < 0 ? Integer.MAX_VALUE : costs[b][index(node)];
    }

    /** Of a reached node only. */
    int via(int node) {
        return vias[at(node, false)][index(node)];
    }

    void put(int node, int cost, int via) {
        int b = at(node, true), i = index(node);
        size += costs[b][i] == Integer.MAX_VALUE ? 1 : 0;
        costs[b][i] = cost;
        vias[b][i] = via;
    }

    /** The nodes reached. */
    NodeSet reached() {
        long[][] bits = new long[count][];
        for (int b = 0; b < count; b++) {
            bits[b] = new long[64];
            for (int i = 0; i < SIZE; i++) {
                bits[b][i >> 6] |= costs[b][i] != Integer.MAX_VALUE ? 1L << i : 0;
            }
        }
        return new NodeSet(blocks, bits, count);
    }
}
