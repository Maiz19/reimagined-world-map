package com.hdmapreforged;

import static java.awt.RenderingHints.*;

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.*;
import java.lang.ref.*;
import java.util.*;
import java.util.List;
import lombok.*;

/**
 * Tiles pre-scaled to their drawn size: smooth scaling every frame was the most expensive part of painting. Swing
 * thread only.
 */
final class ScaledTiles {
    static final long MAX_BYTES = 64L * 1024 * 1024;
    /** Copies made per frame, in pixels (about 3 ms of smooth scaling): after a zoom, all at once took 16-30 ms. */
    static final int FRAME_PIXELS = 1 << 19;

    static final class Entry {
        /** Weak, so a tile the cache dropped is not kept alive here. */
        final WeakReference<BufferedImage> source;
        /** Soft: the JVM drops copies before it runs out of memory; the tile is then drawn from its source, as sharp. */
        final SoftReference<BufferedImage[]> scaled;
        final int width, height;
        final int sx, sy, part;
        /** The frame that last drew it; never dropped for another copy that frame, or each would be made again. */
        int frame;

        /** Of the source's part {@code part} pixels square from {@code (sx, sy)}: a coarser tile standing in for one. */
        Entry(BufferedImage source, int width, int height, int sx, int sy, int part) {
            this.source = new WeakReference<>(source);
            this.width = width;
            this.height = height;
            this.sx = sx;
            this.sy = sy;
            this.part = part;
            // A copy is kept in strips of at most 360 × 360 pixels: larger ones would pass half of a 1 MB heap region,
            // which Java's G1 keeps "humongous" in whole regions of the old generation; at zoom 3.5 to 4 they filled a
            // 768 MB heap and made the map stutter.
            int rows = Math.max(1, 360 * 360 / width);
            var strips = new BufferedImage[(height + rows - 1) / rows];
            for (int i = 0; i < strips.length; i++) {
                strips[i] = scale(source, width, height, i * rows, Math.min(rows, height - i * rows), sx, sy, part);
            }
            scaled = new SoftReference<>(strips);
        }

        long bytes() {
            return 4L * width * height;
        }
    }

    @RequiredArgsConstructor
    static final class Wanted {
        final TileCache.Key key;
        final BufferedImage source;
        final int width, height;
        final int sx, sy, part;
    }

    final LinkedHashMap<TileCache.Key, Entry> cache = new LinkedHashMap<>(64, 0.75f, true);
    int limit = 64;
    long bytes;
    int frame;
    /** Tiles this frame drew the fast way for want of a copy, farthest from the middle first. */
    final List<Wanted> wanted = new ArrayList<>();

    /** Before a frame's tiles: keeps at most {@code tiles} copies. */
    void beginFrame(int tiles) {
        limit = Math.max(16, tiles);
        frame++;
        wanted.clear();
        trim();
    }

    /**
     * Makes the copies this frame lacked, the middle first, a frame's share; true when more wait for the next frame.
     * With no room left (a view too large for {@link #MAX_BYTES}) the rest stay drawn the fast way.
     */
    boolean endFrame() {
        int pixels = FRAME_PIXELS;
        for (int i = wanted.size() - 1; i >= 0 && !full(4L * wanted.get(i).width * wanted.get(i).height); i--) {
            if (pixels <= 0) {
                wanted.clear();
                return true;
            }
            Wanted want = wanted.get(i);
            pixels -= want.width * want.height;
            Entry entry = new Entry(want.source, want.width, want.height, want.sx, want.sy, want.part);
            entry.frame = frame;
            Entry old = cache.put(want.key, entry);
            bytes += entry.bytes() - (old == null ? 0 : old.bytes());
            trim();
        }
        wanted.clear();
        return false;
    }

    /** No room for {@code more} bytes without dropping a copy this frame drew. */
    boolean full(long more) {
        return (cache.size() >= limit || bytes + more > MAX_BYTES) && !cache.isEmpty()
            && cache.values().iterator().next().frame == frame;
    }

    void clear() {
        cache.clear();
        bytes = 0;
    }

    /**
     * The source's part {@code part} pixels square from {@code (sx, sy)} (all of it for a tile of its own level, a
     * piece of a coarser tile standing in) scaled onto {@code width} by {@code height} at {@code (x, y)}. A new copy is
     * only made when {@code settled} (the zoom is not animating), at {@link #endFrame}.
     */
    void draw(Graphics2D g, TileCache.Key key, BufferedImage source, int sx, int sy, int part, int x, int y, int width,
        int height, boolean settled, boolean smooth) {
        var transform = g.getTransform();
        double device = Math.abs(transform.getScaleX());
        int dw = (int) Math.round(width * device), dh = (int) Math.round(height * device);
        if (dw <= 0 || dh <= 0) {
            return;
        }
        // Crisp squares past the most detailed tiles (nearest neighbour): drawn from the source, the same pixels.
        // Larger than 512 × 512 would take a lot of memory and scales cheaply anyway.
        if (!smooth || dw == part && dh == part || (long) dw * dh > 512 * 512
            || transform.getShearX() != 0 || transform.getShearY() != 0) {
            g.drawImage(source, x, y, x + width, y + height, sx, sy, sx + part, sy + part, null);
            return;
        }
        Entry entry = cache.get(key);
        var shown = entry == null || entry.source.get() != source ? null : entry.scaled.get();
        if (shown == null && entry != null) {
            // Dropped by the JVM, or of an older tile: no longer counted against the limit.
            cache.remove(key);
            bytes -= entry.bytes();
        }
        if (shown == null || entry.width != dw || entry.height != dh || entry.sx != sx || entry.sy != sy || entry.part != part) {
            if (settled) {
                wanted.add(new Wanted(key, source, dw, dh, sx, sy, part));
            }
            // Meanwhile scaled the fast way, as while moving.
            Object hint = g.getRenderingHint(KEY_INTERPOLATION);
            g.setRenderingHint(KEY_INTERPOLATION, VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g.drawImage(source, x, y, x + width, y + height, sx, sy, sx + part, sy + part, null);
            if (hint != null) {
                g.setRenderingHint(KEY_INTERPOLATION, hint);
            }
            return;
        }
        entry.frame = frame;
        var copy = device == 1 ? g : (Graphics2D) g.create();
        int left = x, top = y;
        if (device != 1) {
            var place = new AffineTransform(transform);
            place.translate(x, y);
            place.scale(width / (double) dw, height / (double) dh);
            copy.setTransform(place);
            left = 0;
            top = 0;
        }
        for (var strip : shown) {
            copy.drawImage(strip, left, top, null);
            top += strip.getHeight();
        }
        if (copy != g) {
            copy.dispose();
        }
    }

    static BufferedImage scale(BufferedImage source, int width, int height) {
        return scale(source, width, height, 0, height, 0, 0, source.getWidth());
    }

    /**
     * Rows {@code top} on of the source's part ({@code part} pixels square from {@code (sx, sy)}) scaled to {@code width}
     * x {@code height}: strips give the same pixels as one copy.
     */
    static BufferedImage scale(BufferedImage source, int width, int height, int top, int rows, int sx, int sy, int part) {
        BufferedImage scaled = new BufferedImage(width, rows, source.getColorModel().hasAlpha()
            ? BufferedImage.TYPE_INT_ARGB_PRE : BufferedImage.TYPE_INT_RGB);
        var g = scaled.createGraphics();
        g.setRenderingHint(KEY_INTERPOLATION, VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(KEY_RENDERING, VALUE_RENDER_QUALITY);
        g.drawImage(source, 0, -top, width, height - top, sx, sy, sx + part, sy + part, null);
        g.dispose();
        return scaled;
    }

    void trim() {
        var it = cache.values().iterator();
        while ((cache.size() > limit || bytes > MAX_BYTES) && it.hasNext()) {
            Entry eldest = it.next();
            if (eldest.frame == frame) {
                return;
            }
            bytes -= eldest.bytes();
            it.remove();
        }
    }
}
