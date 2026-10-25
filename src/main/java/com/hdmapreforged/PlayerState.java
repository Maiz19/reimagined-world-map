package com.hdmapreforged;

import java.util.*;
import lombok.*;

/** What a route may use, taken on the client thread and read elsewhere. Immutable. */
@AllArgsConstructor(access = AccessLevel.PRIVATE)
final class PlayerState {
    static final PlayerState UNKNOWN = new PlayerState(ItemSnapshot.NONE, 1, false, new int[0], true, false, -1,
        false, Set.of(), true);

    @With
    final ItemSnapshot items;
    @With
    final int sailingLevel;
    /** Pandemonium done: the player can sail their own boat. */
    @With
    final boolean sailing;
    final int[] boats;
    /** Per boat of {@link #boats}: its teleport focus, 0 none, 1 (Summon Boat), 2 greater (also Teleport to Boat). */
    @With
    final int[] boatFocus;
    final boolean running;
    @With
    final boolean inHouse;
    /** In a house: where its portal leads out; outside: one's own house portal. -1 when not known. */
    final int houseExit;
    @With
    final boolean ownHouse;
    final Set<String> houseFeatures;
    /** House teleports land inside (the house option Teleport Inside, as it is at first); else at the house portal. */
    final boolean landsInside;
    /**
     * In an area whose way out leads back to where one came from (ReturnAreas: the Rune essence mine): where that is;
     * else -1.
     */
    @With
    final int returnExit;
    /** With the bank's items too ({@link #withBank}): what is carried without them; else null. */
    @With
    final ItemSnapshot carried;

    PlayerState(ItemSnapshot items, int sailingLevel, boolean sailing, int[] boats, boolean running, boolean inHouse,
        int houseExit, boolean ownHouse, Set<String> houseFeatures, boolean landsInside) {
        this(items, sailingLevel, sailing, boats.clone(), new int[boats.length], running, inHouse, houseExit, ownHouse,
            Collections.unmodifiableSet(new LinkedHashSet<>(houseFeatures)), landsInside, -1, null);
    }

    /** The same, with other items or Sailing. */
    PlayerState with(ItemSnapshot items, int sailingLevel, boolean sailing) {
        return withItems(items).withSailingLevel(sailingLevel).withSailing(sailing);
    }

    /** The same with the bank's items too, keeping what is carried apart (to say what to take out). */
    PlayerState withBank(Map<Integer, Long> bank) {
        return withItems(items.plus(bank)).withCarried(items);
    }

    /**
     * The same in one's own house ({@code inside}: a route starting on its plan), or out of any house (a route's later
     * part, on from a bank, starts outside).
     */
    PlayerState inOwnHouse(boolean inside) {
        return withInHouse(inside).withOwnHouse(inside);
    }
}
