package com.hdmapreforged;

import java.awt.image.*;
import java.util.*;
import java.util.concurrent.*;
import net.runelite.api.*;
import net.runelite.api.worldmap.*;
import net.runelite.client.util.*;

/** The game's own world map icons, read from the client: drawn as the game's icons, and so our icons look like them. */
final class GameIcons {
    static final Map<Integer, BufferedImage> ELEMENT_SPRITES = new ConcurrentHashMap<>();

    static boolean hasElements() {
        return !ELEMENT_SPRITES.isEmpty();
    }

    static BufferedImage element(int id) {
        return id < 0 ? null : ELEMENT_SPRITES.get(id);
    }

    /** Map elements read per call (each client tick): all of them, hundreds, in one tick made a hitch. */
    static final int SLICE = 50;

    /**
     * Client thread only: reads up to {@link #SLICE} of {@code ids}, taking them off it; false until none are left, or
     * while the game has not loaded its data yet.
     */
    static boolean loadElements(Client client, Collection<Integer> ids) {
        var left = ids.iterator();
        for (int n = 0; n < SLICE && left.hasNext(); n++) {
            int id = left.next();
            if (!ELEMENT_SPRITES.containsKey(id)) {
                var config = config(client, id);
                if (config == null && waiting(client)) {
                    return false;
                }
                BufferedImage image = config == null ? null : icon(config);
                if (image != null) {
                    ELEMENT_SPRITES.put(id, image);
                }
            }
            left.remove();
        }
        return ids.isEmpty();
    }

    /** Null for an id the game does not know (or not yet). */
    static MapElementConfig config(Client client, int id) {
        try {
            return client.getMapElementConfig(id);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Still null once logged in: an id gone after a game update, not worth retrying every tick. */
    static boolean waiting(Client client) {
        return client.getGameState() != GameState.LOGGED_IN;
    }

    static BufferedImage icon(MapElementConfig config) {
        var pixels = config.getMapIcon(false);
        BufferedImage image = pixels == null ? null : pixels.toBufferedImage();
        return image != null && image.getWidth() > 0 && image.getHeight() > 0 ? image : null;
    }

    /** Fairy rings: the game draws its transport arrow for them; ours, as the game draws its icons. */
    static final BufferedImage FAIRY_RING = ImageUtil.loadImageResource(GameIcons.class, "fairy_ring.png");

    /** Null when not loaded yet, or a type we draw ourselves. */
    static BufferedImage get(PoiType type) {
        return type == PoiType.FAIRY_RING ? FAIRY_RING : element(type.element);
    }

    static void clear() {
        ELEMENT_SPRITES.clear();
    }

    /** Client thread only: the types' icons ({@link #loadElements}); false while the game has not loaded its data yet. */
    static boolean load(Client client) {
        var ids = new HashSet<Integer>();
        for (var type : PoiType.values()) {
            if (type.element >= 0) {
                ids.add(type.element);
            }
        }
        return loadElements(client, ids);
    }
}
