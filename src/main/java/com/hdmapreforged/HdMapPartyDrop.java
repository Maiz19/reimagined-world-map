package com.hdmapreforged;

import lombok.*;
import net.runelite.client.party.messages.*;

/** A valuable drop a party member just got, sent once to the party. Values from other clients are checked. */
@AllArgsConstructor
public class HdMapPartyDrop extends PartyMemberMessage {
    /** Item id and quantity; its worth only for picking the sender's few (each receiver prices it itself). */
    private final int i, q;
    private final transient long v;
    /** Untradeable: picked first, as receivers list them (PartyMapMembers.BEST). */
    private final transient boolean u;

    /** -1 when not believable. */
    int item() {
        return i >= 0 && i < HdMapPartyState.MAX_ITEM ? i : -1;
    }

    int quantity() {
        return Math.max(1, q);
    }

    boolean untradeable() {
        return u;
    }

    long value() {
        return Math.max(0, Math.min(Integer.MAX_VALUE * 100L, v));
    }
}
