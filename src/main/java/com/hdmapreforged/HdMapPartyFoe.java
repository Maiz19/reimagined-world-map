package com.hdmapreforged;

import lombok.*;
import net.runelite.client.party.messages.*;

/**
 * The NPC a party member fights and its health as their game shows it (its health bar's ratio of its scale), sent when
 * it changes; none when the fight ends. Values from other clients are checked.
 */
@AllArgsConstructor
public class HdMapPartyFoe extends PartyMemberMessage {
    /** NPC id (-1: no fight), name, health ratio (-1: its health bar not shown yet) and scale. */
    private final int i;
    private final String n;
    private final int r, s;

    /** The fight ended. A new one each time: sending sets its member id. */
    static HdMapPartyFoe none() {
        return new HdMapPartyFoe(-1, null, -1, 0);
    }

    boolean fighting() {
        return i >= 0 && n != null && !n.isBlank();
    }

    int npc() {
        return i;
    }

    /** At most 40 characters, no tags. */
    String name() {
        String plain = n == null ? "" : n.replaceAll("<[^>]*>", "").trim();
        return plain.length() > 40 ? plain.substring(0, 40) : plain;
    }

    /** What is left of its health, 0 to 1; -1 when not known. */
    double health() {
        return s <= 0 || s > 255 || r < 0 ? -1 : Math.min(1, r / (double) s);
    }
}
