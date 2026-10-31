package com.hdmapreforged;

import static com.hdmapreforged.Tiles.CELL;

import java.io.*;
import java.util.*;
import lombok.*;

/**
 * Where a boat can sail: ground floor water in CELL×CELL blocks, open sea when mostly water (boats keep off rivers
 * and shores). Each block takes the hazard level of the nearest named sea (approximate near borders). Made once per
 * map version by the map server (the version's sea.bin), with its seas (its tables' seas). Immutable.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class SeaMap {
    /** No sea here. */
    static final byte NONE = -2;
    /** Sea no boat can cross yet. */
    static final byte CLOSED = -1;

    /** A sea the map version names (from the wiki's list). */
    static final class Area {
        String name;
        int x, y;
        String hazard;
        /** 0 for none, -1 when it cannot be crossed. */
        int level;
    }

    /** Per region, per 16×16 block: the level needed, NONE or CLOSED; null when no sea. */
    final byte[][] blocks = new byte[1 << 15][];
    final List<Area> areas;

    /**
     * Per region with sea, sea.bin holds its number (two bytes) and its 256 blocks. A map version without one (up to
     * cache-2727.40) or without its seas has no sea: no sailing, but the planner still loads.
     */
    static SeaMap load() {
        var sea = new SeaMap(RouteFiles.tables().seas);
        try (var in = new DataInputStream(RouteFiles.require(RouteFiles.DIR + "sea.bin"))) {
            for (int high; (high = in.read()) >= 0; ) {
                byte[] cells = new byte[256];
                int region = high << 8 | in.readUnsignedByte();
                in.readFully(cells);
                sea.blocks[region] = cells;
            }
        } catch (IOException | RuntimeException e) {
            // What was read.
        }
        return sea;
    }

    Area nearest(int x, int y) {
        return areas.stream().min(Comparator.comparingLong(a -> (long) (a.x - x) * (a.x - x) + (long) (a.y - y) * (a.y - y)))
            .orElse(null);
    }

    /** The level a block needs, NONE or CLOSED. */
    int level(int cellX, int cellY) {
        if (cellX < 0 || cellY < 0 || cellX >= 1 << 12 || cellY >= 1 << 12) {
            return NONE;
        }
        int region = (cellX >> 4) << 8 | (cellY >> 4);
        if (region >= 1 << 15) {
            return NONE;
        }
        var cells = blocks[region];
        return cells == null ? NONE : cells[(cellX & 15) << 4 | (cellY & 15)];
    }

    boolean sailable(int cellX, int cellY, int sailingLevel) {
        int level = level(cellX, cellY);
        return level >= 0 && sailingLevel >= level;
    }

    /** The nearest sailable block within {@code radius} tiles, as a packed sea node, or -1. */
    int nearestBlock(int x, int y, int radius, int sailingLevel) {
        int best = -1;
        long bestDistance = Long.MAX_VALUE;
        for (int cx = (x - radius) / CELL; cx <= (x + radius) / CELL; cx++) {
            for (int cy = (y - radius) / CELL; cy <= (y + radius) / CELL; cy++) {
                if (!sailable(cx, cy, sailingLevel)) {
                    continue;
                }
                long dx = cx * CELL + CELL / 2 - x, dy = cy * CELL + CELL / 2 - y, d = dx * dx + dy * dy;
                if (d < bestDistance && d <= (long) radius * radius) {
                    bestDistance = d;
                    best = Tiles.sea(cx, cy);
                }
            }
        }
        return best;
    }
}
