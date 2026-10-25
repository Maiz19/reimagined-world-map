package com.hdmapreforged;

import net.runelite.api.coords.*;
import net.runelite.client.party.messages.*;
import net.runelite.client.util.*;

/** Where a party member is. The class name is the message type on the party server; short field names keep it small. */
public class HdMapPartyLocation extends PartyMemberMessage {
    static final int MAX_COORDINATE = 16383, MAX_NAME = 12, MAX_RAW_NAME = 64;

    private final int x, y, p;
    /** World, 0 when unknown. */
    private final int w;
    private final String n;
    /** In a house (the point is its portal); else left out, as null. */
    private final Boolean h;

    HdMapPartyLocation(WorldPoint point, int world, String name, boolean house) {
        h = house ? true : null;
        x = point == null ? -1 : point.getX();
        y = point == null ? -1 : point.getY();
        p = point == null ? 0 : point.getPlane();
        w = world;
        n = name;
    }

    static HdMapPartyLocation offline() {
        return new HdMapPartyLocation(null, 0, null, false);
    }

    WorldPoint point() {
        return plausible(x, y, p) ? new WorldPoint(x, y, p) : null;
    }

    static boolean plausible(WorldPoint point) {
        return point != null && plausible(point.getX(), point.getY(), point.getPlane());
    }

    private static boolean plausible(int x, int y, int plane) {
        return x >= 0 && y >= 0 && x <= MAX_COORDINATE && y <= MAX_COORDINATE && plane >= 0 && plane <= 3;
    }

    boolean house() {
        return Boolean.TRUE.equals(h);
    }

    int world() {
        return w > 0 && w < 1000 ? w : 0;
    }

    String name() {
        return cleaned(n, false);
    }

    /**
     * Tags stripped (and, for a name from RuneLite's own party data, any stray {@code <} as well, so none of it is
     * ever taken as HTML in a Swing label); non-breaking spaces made plain, trimmed. Null if empty, too long raw, or
     * too long after.
     */
    static String cleaned(String raw, boolean stripBrackets) {
        if (raw == null || raw.length() > MAX_RAW_NAME) {
            return null;
        }
        String name = Text.removeTags(raw);
        if (stripBrackets) {
            name = name.replace('<', ' ');
        }
        name = name.replace('\u00a0', ' ').trim();
        return name.isEmpty() || name.length() > MAX_NAME ? null : name;
    }
}
