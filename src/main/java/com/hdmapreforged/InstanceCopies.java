package com.hdmapreforged;

/** Instances whose template copies a place on the map (the map version's copies): their tiles as that place's. */
final class InstanceCopies {
    /** The place's tile for a template tile ({@code x y plane}), or null when no known copy holds it. */
    static int[] place(int x, int y, int plane) {
        for (var c : RouteFiles.tables().copies) {
            if (x >= c[0] && y >= c[1] && x <= c[2] && y <= c[3]) {
                return new int[]{x - c[4], y - c[5], plane};
            }
        }
        return null;
    }

    /** The template tile for a tile of a copied place, or null when no known copy holds it: what the game draws there. */
    static int[] template(int x, int y, int plane) {
        for (var c : RouteFiles.tables().copies) {
            if (x >= c[0] - c[4] && y >= c[1] - c[5] && x <= c[2] - c[4] && y <= c[3] - c[5]) {
                return new int[]{x + c[4], y + c[5], plane};
            }
        }
        return null;
    }
}
