package com.hdmapreforged;

import java.util.*;
import lombok.*;
import net.runelite.api.coords.*;

/**
 * Every place of one kind (herb patches, shark fishing spots, banks) from our icons, game icons and skill spots, as a
 * map version's places.json lists them (made by the test sources' KindIndexBuilder).
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class KindIndex {
    @RequiredArgsConstructor
    static final class Entry {
        final String name;
        final WorldPoint point;
        final String detail;
        /** Null for a listed skilling spot. */
        final Poi poi;
    }

    @RequiredArgsConstructor
    static final class Kind {
        final String label;
        /** Null for skilling spots. */
        final PoiType type;
        final String page;
        final transient List<Entry> entries = new ArrayList<>();
        /** In places.json: its entries by number, and its places as the search lists them, those close together in one
         * town merged ("Catherby, 4 spots"), each led by the entry numbered in {@link #firsts}. */
        int[] places = {}, firsts = {};
        List<Spawns.Group> groups = List.of();

        private Kind() {
            this(null, null, null);
        }
    }

    final List<Kind> kinds;

    List<Kind> all() {
        return Collections.unmodifiableList(kinds);
    }
}
