package com.hdmapreforged;

/**
 * Where the player stood before coming into an area whose way out leads back there (ReturnAreas: the Rune essence mine,
 * the Shipyard), so a route from inside knows where the exit leads. Not known when the player was inside already (logged
 * in there). Client thread only.
 */
final class ReturnTracker {
    int lastOutside = -1;
    ReturnAreas.Area inside;
    int exit = -1;

    void update(int node) {
        var area = node < 0 ? null : ReturnAreas.at(node);
        if (area != null) {
            if (inside != area) {
                // Came in (or into another such area): from where one stood just before, if one was outside then.
                exit = inside == null && lastOutside >= 0 ? lastOutside : -1;
                inside = area;
            }
            return;
        }
        inside = null;
        exit = -1;
        if (node >= 0) {
            lastOutside = node;
        }
    }

    /** Where the exit of the area the player is in leads (where they came from), or -1. */
    int exit() {
        return inside == null ? -1 : exit;
    }
}
