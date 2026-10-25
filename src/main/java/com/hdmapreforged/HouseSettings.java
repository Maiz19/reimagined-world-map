package com.hdmapreforged;

import java.util.*;
import java.util.stream.*;

/** The house panel's choices to and from the house scan's words ("box:ornate", "glory", "xeric", "portal:Varrock"). */
final class HouseSettings {
    static final String PORTAL = "portal:";
    static final String NEXUS = "nexus:";
    /** The jewellery box tiers, as in "box:ornate". */
    static final String[] BOXES = {"none", "basic", "fancy", "ornate"};
    /** Both a spirit tree and a fairy ring: a spiritual fairy tree, or each in a superior garden of its own. */
    static final String BOTH = "spirit tree+fairy ring";
    /** The superior gardens' teleports: none, fairy ring, spirit tree, both. */
    static final String[] GARDENS = {"", "fairy ring", "spirit tree", BOTH};
    /** What {@link #BOTH} is: its fairy ring and its spirit tree. */
    static final List<String> PARTS = List.of(GARDENS[1], GARDENS[2]);
    /** The Achievement Gallery's mounted amulets, as the scan names them, and their teleports' group. */
    static final String[] MOUNTED = {"glory", "xeric", "digsite"};
    static final String[] MOUNTED_GROUPS = {"Amulet of glory", "Xeric's talisman", "Digsite pendant"};

    /** Whether a house with these features has this one ("both" has a spirit tree and a fairy ring). */
    static boolean has(Set<String> features, String feature) {
        return features.contains(feature)
            || features.contains(BOTH) && (GARDENS[1].equals(feature) || GARDENS[2].equals(feature));
    }

    /** {@code box}: a tier (any case), else none; {@code mounted}: of {@link #MOUNTED}. */
    static Set<String> features(String box, Set<String> mounted, int garden, boolean obelisk, String portals,
        String nexus) {
        var features = new LinkedHashSet<String>();
        String tier = box == null ? "none" : box.toLowerCase(Locale.ROOT);
        if (!tier.equals("none") && Set.of(BOXES).contains(tier)) {
            features.add("box:" + tier);
        }
        for (String amulet : MOUNTED) {
            if (mounted != null && mounted.contains(amulet)) {
                features.add(amulet);
            }
        }
        if (garden > 0 && garden < GARDENS.length) {
            features.add(GARDENS[garden]);
        }
        if (obelisk) {
            // In a superior garden of its own: a house may have several.
            features.add("obelisk");
        }
        add(features, PORTAL, portals);
        add(features, NEXUS, nexus);
        return features;
    }

    static String box(Set<String> features) {
        return BOXES[last(features, "box:", BOXES)];
    }

    static void add(Set<String> features, String prefix, String places) {
        for (String place : places == null ? new String[0] : places.split("[,;\\n]")) {
            if (!place.trim().isEmpty()) {
                features.add(prefix + place.trim());
            }
        }
    }

    /** Which of {@link #GARDENS}. */
    static int garden(Set<String> features) {
        return last(features, "", GARDENS);
    }

    /** The last of {@code choices} after the first (none) that the features have, with {@code prefix}; else 0. */
    static int last(Set<String> features, String prefix, String[] choices) {
        for (int i = choices.length - 1; i > 0; i--) {
            if (features.contains(prefix + choices[i])) {
                return i;
            }
        }
        return 0;
    }

    /** As the panel shows them: "Varrock, Falador"; {@code prefix} {@link #PORTAL} or {@link #NEXUS}. */
    static String places(Set<String> features, String prefix) {
        return features.stream().filter(f -> f.startsWith(prefix)).map(f -> f.substring(prefix.length()))
            .collect(Collectors.joining(", "));
    }
}
