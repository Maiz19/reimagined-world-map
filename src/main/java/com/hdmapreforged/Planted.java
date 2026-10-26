package com.hdmapreforged;

import java.util.*;
import java.util.function.*;

/**
 * The spirit trees one plants (Port Sarim, Etceteria, Brimhaven, Hosidius, the Farming Guild) that stand grown for this
 * account. The game says so only near the patch: its farming varbit, read where RuneLite's Time Tracking reads it
 * (FarmingWorld: the patch's regions and varbit), is 20 for a grown tree one travels by ("Spirit Tree[Travel,...]",
 * PatchImplementation.SPIRIT_TREE); any other value (growing, diseased, dead, to check, weeds) is none. Kept per account
 * (RS profile key {@link #KEY}); a patch not visited since is not known, so not used.
 */
final class Planted {
    static final String KEY = "plantedSpiritTrees";
    /** The patch state of a grown spirit tree one can travel by. */
    static final int GROWN = 20;
    /** As the travel table's stops are named: the patch's varbit, then its regions (list "spirit tree patches"). */
    static final RouteFiles.Table<Map<String, int[]>> PATCHES = new RouteFiles.Table<>(() -> Pathfinder.pairs(
        "spirit tree patches", Pathfinder::ints));

    volatile Set<String> grown = Collections.emptySet();

    /** A stop of the travel table that needs a tree planted ("Only after planting a spirit tree here"). */
    static boolean planted(Poi stop) {
        return stop.type == PoiType.SPIRIT_TREE && stop.note != null && stop.note.startsWith("Only after planting");
    }

    Set<String> grown() {
        return grown;
    }

    void load(String saved) {
        grown = saved == null || saved.isBlank() ? Collections.emptySet()
            : Collections.unmodifiableSet(new TreeSet<>(Arrays.asList(saved.split(","))));
    }

    String text() {
        return String.join(",", grown);
    }

    /** In {@code region}: reads its patch, if it has one. Whether what is known changed. Client thread. */
    boolean update(int region, IntUnaryOperator varbit) {
        for (var patch : PATCHES.get().entrySet()) {
            int[] read = patch.getValue();
            for (int i = 1; i < read.length; i++) {
                if (read[i] == region) {
                    String name = patch.getKey();
                    boolean stands = varbit.applyAsInt(read[0]) == GROWN;
                    if (stands == grown.contains(name)) {
                        return false;
                    }
                    var next = new TreeSet<>(grown);
                    if (stands) {
                        next.add(name);
                    } else {
                        next.remove(name);
                    }
                    grown = Collections.unmodifiableSet(next);
                    return true;
                }
            }
        }
        return false;
    }
}
