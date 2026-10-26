package com.hdmapreforged;

import java.util.*;
import lombok.*;

/**
 * A planner passage that is an Agility shortcut. It knows nothing of the shortcut's level, so RouteSource adds it per
 * request only when the requirements are met.
 */
@RequiredArgsConstructor
final class ShortcutPassage {
    final Edge edge;
    /**
     * Its shortcuts.tsv rows' Needs columns (skills, items, quests, varbits), first row first: any one met will do (a
     * grapple shortcut with its grapple, or bare-handed at a higher Agility level).
     */
    final List<String[]> ways;

    /** Every shortcuts.tsv row's Needs columns, as the map version's passages.tsv has them (its W rows). */
    static final RouteFiles.Table<List<String[]>> WAYS = new RouteFiles.Table<>(() -> Pathfinder.table(
        RouteFiles.DIR + "passages.tsv", c -> c.length > 5 && c[0].equals("W") ? Arrays.copyOfRange(c, 1, 5) : null));

    /** What the requirements read must watch. */
    static List<String[]> allWays() {
        return WAYS.get();
    }
}
