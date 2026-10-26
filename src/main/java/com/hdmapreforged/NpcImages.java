package com.hdmapreforged;

import java.awt.*;
import java.awt.image.*;
import java.util.*;
import java.util.List;
import java.util.function.*;
import javax.swing.*;
import lombok.*;

/**
 * NPC pictures from our map server, for the search panels and cards of both maps: scaled once to the two sizes shown
 * and kept. A label waiting for a picture gets it when it comes, so no list is built again for it. A label asks only
 * once it is painted, so the rows of a long list cost nothing until scrolled to, and a few are asked for at a time:
 * a row scrolled past before its turn waits until it is painted again. Swing thread.
 */
@RequiredArgsConstructor
final class NpcImages {
    /** A row's picture; headers and shopkeepers show {@link #LARGE}. */
    static final int SMALL = SearchResults.PICTURE, LARGE = 2 * SMALL;
    /** The rows of a few screens of both maps' lists, so a list built again has those in view at once. */
    static final int KEPT = 160;
    /** Asked for at once at most: scrolling through hundreds of rows never sends hundreds of requests. */
    static final int AT_ONCE = 4;
    /** A picture the server did not send is asked for again after this long. */
    static final long RETRY_MS = 60_000;

    /** Where a picture comes from, called back on any thread, with null when there is none. */
    final BiConsumer<Integer, Consumer<BufferedImage>> source;
    /** Each NPC's picture at {@link #SMALL} and {@link #LARGE}, those shown last kept. */
    final Map<Integer, Icon[]> icons = new LruMap<>(16, KEPT);
    /** Asked for and not answered yet, or waiting their turn, with the labels waiting for each. */
    final Map<Integer, List<JLabel>> waiting = new HashMap<>();
    /** Waiting their turn, the last painted first: a list paints its rows from the last up, so its top row is first. */
    final Deque<Integer> queued = new ArrayDeque<>();
    int asking;
    /** Whether {@link #next} runs once all the labels painted together have asked. */
    boolean nextLater;
    /** When a picture the server did not send was asked for. */
    final Map<Integer, Long> missing = new HashMap<>();

    /**
     * The NPC's picture in {@code label}, at the label's preferred width: now when kept, else an empty square that asks
     * for it once painted.
     */
    void show(int npc, JLabel label) {
        var known = icons.get(npc);
        if (known != null) {
            put(known, label);
            return;
        }
        label.setIcon(new Pending(npc, label.getPreferredSize().width));
    }

    /** A label painted: its picture waits its turn; not when the server had none just now. */
    void want(int npc, JLabel label) {
        var known = icons.get(npc);
        if (known != null) {
            put(known, label);
            return;
        }
        Long failed = missing.get(npc);
        if (failed != null && System.currentTimeMillis() - failed < RETRY_MS) {
            return;
        }
        var labels = waiting.get(npc);
        if (labels == null) {
            waiting.put(npc, labels = new ArrayList<>());
            queued.push(npc);
        }
        labels.add(label);
        if (!nextLater) {
            nextLater = true;
            SwingUtilities.invokeLater(this::next);
        }
    }

    /** Asks for the next while fewer than {@link #AT_ONCE} are; those out of view now ask again once painted again. */
    void next() {
        nextLater = false;
        while (asking < AT_ONCE && !queued.isEmpty()) {
            int npc = queued.pop();
            var labels = waiting.get(npc);
            if (labels.stream().anyMatch(label -> label.isShowing() && !label.getVisibleRect().isEmpty())) {
                asking++;
                source.accept(npc, image -> SwingUtilities.invokeLater(() -> arrived(npc, image)));
                continue;
            }
            waiting.remove(npc);
            labels.forEach(label -> {
                if (label.getIcon() instanceof Pending) {
                    ((Pending) label.getIcon()).asked = false;
                }
            });
        }
    }

    void arrived(int npc, BufferedImage image) {
        asking--;
        var labels = waiting.remove(npc);
        if (image == null) {
            missing.put(npc, System.currentTimeMillis());
        } else {
            missing.remove(npc);
            // Drawn smoothly: far quicker than Image.SCALE_SMOOTH, and halving is exact.
            BufferedImage large = ScaledTiles.scale(image, LARGE, LARGE);
            Icon[] both = {new ImageIcon(ScaledTiles.scale(large, SMALL, SMALL)), new ImageIcon(large)};
            icons.put(npc, both);
            labels.forEach(label -> put(both, label));
        }
        next();
    }

    static void put(Icon[] both, JLabel label) {
        label.setIcon(both[label.getPreferredSize().width == LARGE ? 1 : 0]);
    }

    /** An empty square of the picture's size, so the picture comes without a new layout; asks for it once painted. */
    @RequiredArgsConstructor
    final class Pending implements Icon {
        final int npc, size;
        boolean asked;

        public void paintIcon(Component c, Graphics g, int x, int y) {
            if (!asked && c instanceof JLabel) {
                asked = true;
                // Not while painting: the picture may be put on the label at once.
                SwingUtilities.invokeLater(() -> want(npc, (JLabel) c));
            }
        }

        public int getIconWidth() {
            return size;
        }

        public int getIconHeight() {
            return size;
        }
    }
}
