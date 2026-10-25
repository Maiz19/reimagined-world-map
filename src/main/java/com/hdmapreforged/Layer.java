package com.hdmapreforged;

import lombok.*;

/** Icon groups, in order of precedence when icons overlap; the Icons panel lists their kinds under their titles. */
@RequiredArgsConstructor
enum Layer {
    DUNGEONS("Dungeons and passages", -0.5),
    TRANSPORTS("Transport", Double.NEGATIVE_INFINITY),
    TELEPORTS("Teleports", -2.5),
    SAILING("Sailing", -1.5),
    SKILLING("Skilling", 1.5),
    ACTIVITIES("Minigames", -1),
    SERVICES("Services", 1),
    SHOPS("Shops", Double.NEGATIVE_INFINITY),
    QUESTS("Quests", Double.NEGATIVE_INFINITY),
    GAME_ICONS("Other map icons", Double.NEGATIVE_INFINITY);

    final String title;
    /** Hidden below this zoom, so the zoomed-out map stays readable. */
    final double minZoom;
}
