package com.hdmapreforged;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import javax.swing.*;
import lombok.*;
import net.runelite.client.callback.*;
import net.runelite.client.game.*;
import net.runelite.client.util.*;

/**
 * Item names for requirement lists, and the Grand Exchange prices the client knows; looked up on the client thread,
 * read from Swing.
 */
@RequiredArgsConstructor
final class ItemNames implements IntFunction<String>, SearchResults.Prices {
    final ClientThread clientThread;
    final ItemManager itemManager;
    final Map<Integer, String> names = new ConcurrentHashMap<>();
    final Map<Integer, Integer> prices = new ConcurrentHashMap<>();
    /**
     * Id to when it was last looked up: a name not found is tried again after 30 seconds (the game's item data may not
     * have been ready), a price after 10 minutes (prices move).
     */
    final Map<Integer, Long> looked = new ConcurrentHashMap<>();
    static final long RETRY_MS = 30_000;
    /** More than a big shop sells; the game's own image cache keeps only 128 for every plugin. */
    static final int IMAGES = 512;
    /** The pictures of the items shown last, so a list built again never asks the game again. Swing thread. */
    final Map<Integer, AsyncBufferedImage> images = new LruMap<>(16, IMAGES);

    public int price(int id) {
        Integer price = prices.get(id);
        return price == null ? -1 : price;
    }

    /** Shows the game's picture of an item in {@code label}, once loaded. */
    void picture(int id, JLabel label) {
        images.computeIfAbsent(id, itemManager::getImage).addTo(label);
    }

    public String apply(int id) {
        String name = names.get(id);
        return name != null || !looked.containsKey(id) ? name : "Item " + id;
    }

    /** Client thread. */
    String lookUp(int id) {
        try {
            var item = itemManager.getItemComposition(id);
            String name = item == null ? null : item.getName();
            return name == null || name.isEmpty() || "null".equals(name) ? null : name;
        } catch (RuntimeException e) {
            return null;
        }
    }

    boolean wanted(int id, long now) {
        Long at = looked.get(id);
        return at == null || now - at >= (names.containsKey(id) ? 10 * 60_000 : RETRY_MS);
    }

    /** Client thread. */
    int lookUpPrice(int id) {
        try {
            return (int) Math.min(Integer.MAX_VALUE, Math.max(0, itemManager.getItemPrice(id)));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    public void resolve(Collection<Integer> ids, Runnable done) {
        // Previews run without a client.
        long now = System.currentTimeMillis();
        if (clientThread == null || ids.stream().noneMatch(id -> wanted(id, now))) {
            return;
        }
        clientThread.invoke(() -> {
            long at = System.currentTimeMillis();
            for (int id : ids) {
                if (!wanted(id, at)) {
                    continue;
                }
                String name = names.containsKey(id) ? null : lookUp(id);
                if (name != null) {
                    names.put(id, name);
                }
                prices.put(id, lookUpPrice(id));
                looked.put(id, at);
            }
            SwingUtilities.invokeLater(done);
        });
    }
}
