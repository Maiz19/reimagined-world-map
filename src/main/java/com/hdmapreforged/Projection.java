package com.hdmapreforged;

import net.runelite.api.coords.*;

/** Where world points appear in the frame being painted; add 0.5 to a tile for its middle. */
interface Projection {
    double screenX(double worldX);

    double screenY(double worldY);

    /** Log2 of screen pixels per game tile. */
    double zoom();

    BaseMap map();

    int plane();

    int width();

    int height();

    boolean shows(WorldPoint point);

    /** Where a game point is drawn on the map in view (the Kalphite Lair is drawn beside the desert caves). */
    default WorldPoint shown(WorldPoint point) {
        return point;
    }
}
