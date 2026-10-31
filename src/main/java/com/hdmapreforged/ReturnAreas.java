package com.hdmapreforged;

/**
 * Areas whose way out leads back to where one came from (the map version's return areas): the Rune essence mine's
 * portals return one to the wizard who sent one there, the Shipyard's portal to the shipwright.
 */
final class ReturnAreas {
    static final class Area {
        String name;
        int minX, minY, maxX, maxY, plane;
        /** The exit objects' tiles, packed. */
        int[] exits;
        /** The step's name ("Use Portal"). */
        String exitName;

        boolean contains(int node) {
            return node >= 0 && !Tiles.isSea(node) && Tiles.z(node) == plane && Tiles.x(node) >= minX
                && Tiles.x(node) <= maxX && Tiles.y(node) >= minY && Tiles.y(node) <= maxY;
        }
    }

    /** The area holding the tile, or null. */
    static Area at(int node) {
        return RouteFiles.tables().returns.stream().filter(area -> area.contains(node)).findFirst().orElse(null);
    }
}
