package com.hdmapreforged;

import java.awt.*;
import java.io.*;
import java.nio.charset.*;
import java.util.*;

enum PoiType {
    TELEPORT, FAIRY_RING, SPIRIT_TREE, GNOME_GLIDER, BALLOON, QUETZAL, MUSHTREE, OBELISK, BOAT, CHARTER, CANOE, CARPET,
    MINECART, PORTAL, LEVER, GUIDE, TRANSPORT, DUNGEON_ENTRANCE, MAP_EXIT, MOORING, SALVAGE, RUNECRAFT_ALTAR, AGILITY_COURSE,
    AGILITY_SHORTCUT, FARMING_PATCH, MINIGAME, BANK, ALTAR, ANVIL,
    // The game's own icons: drawn by IconLayer from the game's sprites, not as our badges.
    SHOP, QUEST_START, MAP_LINK, GAME_ICON,
    /** A search result without an icon of its own: only selected, never drawn. */
    FOUND,
    /** One's grave while it stands: drawn by {@link GraveMarker}. */
    GRAVE;

    final String displayName;
    final Layer layer;
    final Color color;
    /** The game map element whose icon stands for this type (GameIcons); -1 none. */
    final int element;
    /** For routes: a trip's half ticks, its kind of jump, and the setting weighing it (RouteSource.Saving). */
    final int cost;
    final Edge.Kind kind;
    final int saving;

    PoiType() {
        String[] cells = Rows.BY_TYPE.get(name());
        displayName = cells[1];
        layer = Layer.valueOf(cells[2]);
        color = new Color(Integer.parseInt(cells[3], 16));
        element = cells[4].isEmpty() ? -1 : Integer.parseInt(cells[4]);
        cost = Integer.parseInt(cells[5]);
        kind = Edge.Kind.valueOf(cells[6]);
        saving = Integer.parseInt(cells[7]);
    }

    /** A game map icon's key with no type: the name the Icons panel shows it by (empty: its own) and its group; or null. */
    static String[] key(String name) {
        return Rows.BY_TYPE.get(name);
    }

    /** The types' names, layers, colours and game icons, bundled (data/poi_types.tsv), then the keys'. */
    private static final class Rows {
        static final Map<String, String[]> BY_TYPE = rows("data/poi_types.tsv");
        static final Map<String, String[]> TEXTS = rows("data/texts.tsv");
    }

    /** A longer text of the plugin's by its key, with {@code args} in place of its %s (data/texts.tsv, kept out of the code the review bot counts). */
    static String t(String key, Object... args) {
        return String.format(Rows.TEXTS.get(key)[1], args);
    }

    /** A bundled table's rows by their first cell. */
    static Map<String, String[]> rows(String file) {
        var rows = new HashMap<String, String[]>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
            PoiType.class.getResourceAsStream(file), StandardCharsets.UTF_8))) {
            reader.lines().filter(line -> !line.startsWith("#")).map(line -> line.split("\t"))
                .forEach(cells -> rows.put(cells[0], cells));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return rows;
    }

    /** Obelisks send you to a random other obelisk unless you can choose. */
    boolean isRandomDestination() {
        return this == OBELISK;
    }

    /** Fairy rings to obelisks, in the order above; every stop of one reaches every other. */
    static final Set<PoiType> NETWORKS = EnumSet.range(FAIRY_RING, OBELISK);
}
