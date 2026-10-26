package com.hdmapreforged;

import java.awt.*;
import java.util.*;
import java.util.List;
import lombok.*;
import net.runelite.api.Player;
import net.runelite.api.coords.*;
import net.runelite.client.events.*;

/**
 * The route's ground tiles and texts via HD World Markers' public plugin messages ({@code tiles}, {@code lines},
 * {@code texts} and {@code clear}; its guide's "For plugin developers"). It clears them on logout, world hop, connection
 * loss and a profile change: they are sent again after.
 */
final class MarkersBridge {
    static final String NAMESPACE = "hd-world-markers";
    static final String OWNER = "hd-map-reforged-route";
    static final String PLUGIN_NAME = "HD World Markers";
    /** It takes at most this many tiles from one sender. */
    static final int MAX_TILES = 1000;

    /** A tile to mark, optionally labelled. */
    @AllArgsConstructor
    static final class Tile {
        final WorldPoint point;
        final Color color;
        /** Null: a faint fill after the colour's transparency. */
        final Color fill;
        final int width;
        final String label;

        Tile(WorldPoint point, Color color, String label) {
            this(point, color, null, 2, label);
        }
    }

    static PluginMessage tiles(List<Tile> tiles) {
        var list = new ArrayList<Map<String, Object>>();
        for (Tile tile : tiles.subList(0, Math.min(MAX_TILES, tiles.size()))) {
            var entry = new HashMap<String, Object>();
            entry.put("point", tile.point);
            entry.put("color", tile.color);
            // Follows the colour's transparency, so fading tiles fade as a whole.
            entry.put("fill", tile.fill != null ? tile.fill : new Color(tile.color.getRed(), tile.color.getGreen(),
                tile.color.getBlue(), tile.color.getAlpha() * 50 / 255));
            entry.put("width", tile.width);
            if (tile.label != null) {
                entry.put("label", tile.label);
            }
            list.add(entry);
        }
        return message("tiles", list);
    }

    /**
     * The route's walks as lines through the tiles' centres ({@code lines}; points next to each other are joined, a
     * jump breaks the line), each with an arrowhead every so many tiles from its end ({@code arrows}, 0: none); an empty
     * list takes them away.
     */
    static PluginMessage lines(List<List<WorldPoint>> lines, List<Color> colors, List<Integer> arrows, int width) {
        var list = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < lines.size(); i++) {
            var entry = new HashMap<String, Object>();
            entry.put("points", lines.get(i));
            entry.put("color", colors.get(i));
            entry.put("width", width);
            if (arrows.get(i) > 0) {
                entry.put("arrows", arrows.get(i));
            }
            list.add(entry);
        }
        return message("lines", list);
    }

    /**
     * The text over the player's head ({@code texts}, following the player); its own owner, so clearing the tiles leaves
     * it. A null text takes it away.
     */
    static PluginMessage head(String text, Color color, Player player) {
        var data = new HashMap<String, Object>();
        data.put("owner", OWNER + "-text");
        data.put("texts", text == null ? List.of() : List.of(Map.of("text", text, "color", color, "player", player)));
        return new PluginMessage(NAMESPACE, "texts", data);
    }

    static PluginMessage clear() {
        return message("clear", null);
    }

    /** Ours, with {@code list} under its name (none: null). */
    static PluginMessage message(String name, Object list) {
        var data = new HashMap<String, Object>();
        data.put("owner", OWNER);
        if (list != null) {
            data.put(name, list);
        }
        return new PluginMessage(NAMESPACE, name, data);
    }
}
