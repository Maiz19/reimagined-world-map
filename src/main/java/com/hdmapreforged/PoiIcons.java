package com.hdmapreforged;

import static com.hdmapreforged.MapIconLayer.*;
import static java.awt.BasicStroke.*;
import static java.awt.image.BufferedImage.*;
import static java.awt.geom.AffineTransform.*;
import static java.awt.RenderingHints.*;
import static java.lang.Math.*;

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.*;
import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.*;
import javax.imageio.ImageIO;

/** Vector map icons, so they stay sharp at every zoom level and interface scale. */
final class PoiIcons {
    static final Color OUTLINE = new Color(20, 18, 16), GLYPH = new Color(255, 255, 255, 240);

    /** Rendered badges by type, size and screen scale. */
    static final Map<Long, BufferedImage> SPRITES = new ConcurrentHashMap<>();
    /** The pixels they hold together: zooming makes a size per quarter pixel, and 4000 large ones were tens of MB. */
    static final AtomicLong SPRITE_PIXELS = new AtomicLong();

    /**
     * {@link #paint} from a cached image: painting hundreds of badges shape by shape was most of a frame's cost.
     * {@code picture}: the game's ({@link Poi#picture}), a spell's sprite or an item's (our own pixel art of it, or in a
     * frame), drawn instead once it is had.
     * {@code emphasis}: 0 none, 1 the selected's ring, 2 the hovered's or a group member's.
     */
    static void paintCached(Graphics2D g, PoiType type, int picture, double cx, double cy, double size, int emphasis,
        boolean locked) {
        var transform = g.getTransform();
        double device = device(transform);
        // Quarter pixels of badge size, so zooming changes the image in small steps.
        int quarter = (int) round(size * 4);
        var sprite = picture(picture);
        long key = (long) (sprite == null ? 0 : (picture < 0 ? ~picture | 0x20000 : picture) & 0x3ffff) << 46
            | (long) type.ordinal() << 40
            | (locked ? 1L << 39 : 0) | (long) quarter << 16 | round(device * 10) << 2 | emphasis;
        double drawnSize = quarter / 4.0;
        // Room around the badge for the emphasis ring.
        double extent = drawnSize + (ceil(drawnSize * 0.35) + 2) * 2;
        BufferedImage cached = SPRITES.get(key);
        if (cached == null) {
            // At most 4000 sprites and 4 million pixels.
            if (SPRITES.size() > 4000 || SPRITE_PIXELS.get() > 4_000_000) {
                SPRITES.clear();
                SPRITE_PIXELS.set(0);
            }
            cached = sprite(extent, extent, device, sg -> {
                if (sprite != null && (picture < 0 || sprite.getWidth() == ICON_PIXELS)) {
                    // A game sprite, or our own picture of an item: pixel art of a game icon's size.
                    paintGameIcon(sg, sprite, extent / 2, extent / 2, drawnSize, RINGS[emphasis]);
                } else if (sprite != null) {
                    paintPicture(sg, sprite, extent / 2, extent / 2, drawnSize, RINGS[emphasis]);
                } else {
                    paint(sg, type, extent / 2, extent / 2, drawnSize, RINGS[emphasis], locked);
                }
            });
            SPRITES.put(key, cached);
            SPRITE_PIXELS.addAndGet((long) cached.getWidth() * cached.getHeight());
        }
        blit(g, transform, cached, cx - extent / 2, cy - extent / 2);
    }

    /** The game's pictures by {@link Poi#picture} for the Icons panel, as the game has them; {@link #NONE} while asked for. */
    static final Map<Integer, BufferedImage> PICTURES = new ConcurrentHashMap<>();
    static final BufferedImage NONE = new BufferedImage(1, 1, TYPE_INT_ARGB);
    /** Asks the game for a picture (an item's, or a sprite's as ~id), calling back with it on any thread. */
    static BiConsumer<Integer, Consumer<BufferedImage>> load = (id, done) -> { };
    /** Each picture had, so the maps are drawn again. */
    static Runnable loaded = () -> { };

    /** Null while not had (asked for once): our own pixel art of it from {@code pictures/} if we have one, else the game's. */
    static BufferedImage picture(int id) {
        if (id == 0) {
            return null;
        }
        if (PICTURES.putIfAbsent(id, NONE) == null) {
            var own = PoiIcons.class.getResourceAsStream("pictures/" + id + ".png");
            if (own != null) {
                try (own) {
                    PICTURES.put(id, ImageIO.read(own));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                return PICTURES.get(id);
            }
            load.accept(id, image -> {
                if (image == null) {
                    return;
                }
                PICTURES.put(id, image);
                loaded.run();
            });
        }
        var had = PICTURES.get(id);
        return had == NONE ? null : had;
    }

    /** Draws a screen-resolution sprite on whole screen pixels: at fractions Java2D resamples it each draw (slow). */
    static void blit(Graphics2D g, BufferedImage sprite, double x, double y) {
        blit(g, g.getTransform(), sprite, x, y);
    }

    /** {@code transform}: the graphics' own. */
    static void blit(Graphics2D g, AffineTransform transform, BufferedImage sprite, double x, double y) {
        double tx = transform.getTranslateX(), ty = transform.getTranslateY();
        if (transform.getType() <= TYPE_TRANSLATION && tx == rint(tx) && ty == rint(ty)) {
            // Screen scale 1 on whole pixels: drawn in place, without swapping the transform (costly).
            g.drawImage(sprite, (int) round(x), (int) round(y), null);
            return;
        }
        if ((transform.getType() & (TYPE_GENERAL_ROTATION | TYPE_QUADRANT_ROTATION
            | TYPE_GENERAL_TRANSFORM | TYPE_FLIP)) != 0) {
            double device = device(transform);
            var place = new AffineTransform(transform);
            place.translate(x, y);
            place.scale(1 / device, 1 / device);
            g.drawImage(sprite, place, null);
            return;
        }
        long sx = round(transform.getScaleX() * x + tx), sy = round(transform.getScaleY() * y + ty);
        g.setTransform(getTranslateInstance(sx, sy));
        g.drawImage(sprite, 0, 0, null);
        g.setTransform(transform);
    }

    /** A white dot with a coloured rim, marking where a line ends; from a cached image. */
    static void paintDot(Graphics2D g, double cx, double cy, Color rim) {
        double device = device(g.getTransform());
        long key = (1L << 62) | ((long) (rim.getRGB() & 0xffffffffL) << 8) | (int) round(device * 10);
        double extent = 13;
        blit(g, SPRITES.computeIfAbsent(key, k -> sprite(extent, extent, device, sg -> {
            var dot = new Ellipse2D.Double(extent / 2 - 4.5, extent / 2 - 4.5, 9, 9);
            sg.setColor(Color.WHITE);
            sg.fill(dot);
            sg.setColor(rim);
            sg.setStroke(new BasicStroke(2f));
            sg.draw(dot);
        })), cx - extent / 2, cy - extent / 2);
    }

    /**
     * A game sprite with the game's sign for what one cannot use yet, in its own pixels as the game draws it: red on the
     * pixels just inside its outline, all round, and a band three pixels wide from top right to bottom left. Once per
     * sprite.
     */
    static BufferedImage locked(BufferedImage icon) {
        return LOCKED_SPRITES.computeIfAbsent(icon, k -> {
            int w = icon.getWidth(), h = icon.getHeight();
            var out = new BufferedImage(w, h, TYPE_INT_ARGB);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int rgb = icon.getRGB(x, y);
                    boolean red = drawn(icon, x, y) && !edge(icon, x, y) && (abs(x + y - (w + h - 2) / 2.0) <= 1
                        || edge(icon, x + 1, y) || edge(icon, x - 1, y) || edge(icon, x, y + 1) || edge(icon, x, y - 1));
                    out.setRGB(x, y, red ? LOCKED.getRGB() : rgb);
                }
            }
            return out;
        });
    }

    /** Whether the pixel is on the sprite's outline: drawn, beside one that is not. */
    static boolean edge(BufferedImage icon, int x, int y) {
        return drawn(icon, x, y)
            && !(drawn(icon, x + 1, y) && drawn(icon, x - 1, y) && drawn(icon, x, y + 1) && drawn(icon, x, y - 1));
    }

    static boolean drawn(BufferedImage icon, int x, int y) {
        return x >= 0 && y >= 0 && x < icon.getWidth() && y < icon.getHeight() && icon.getRGB(x, y) >>> 24 != 0;
    }

    static final Map<BufferedImage, BufferedImage> LOCKED_SPRITES = Collections.synchronizedMap(new WeakHashMap<>());
    static final Color LOCKED = new Color(230, 20, 20);

    /** Screen pixels per unit, clamped to a sane range; shared with {@link TextSprites}. */
    static double device(AffineTransform transform) {
        return max(1, min(4, abs(transform.getScaleX())));
    }

    /** A picture of {@code width} by {@code height} units, {@code device} pixels a unit (rounded up), drawn antialiased. */
    static BufferedImage sprite(double width, double height, double device, Consumer<Graphics2D> draw) {
        BufferedImage sprite = new BufferedImage((int) ceil(width * device), (int) ceil(height * device),
            TYPE_INT_ARGB);
        var sg = sprite.createGraphics();
        sg.setRenderingHint(KEY_ANTIALIASING, VALUE_ANTIALIAS_ON);
        sg.scale(device, device);
        draw.accept(sg);
        sg.dispose();
        return sprite;
    }

    static void clearCache() {
        SPRITES.clear();
        PICTURES.clear();
        // Keyed by the icons cleared here, which would stay reachable through their rings.
        OUTLINES.clear();
    }

    /** The game's own map icon for the type if it has one, else our badge for it, pixel art of the same size. */
    static void paint(Graphics2D g, PoiType type, double cx, double cy, double size, Color ring, boolean locked) {
        BufferedImage game = GameIconSprites.get(type), icon = game != null ? game : badge(type);
        paintGameIcon(g, locked ? locked(icon) : icon, cx, cy, size, ring);
    }

    static final Map<PoiType, BufferedImage> BADGES = new ConcurrentHashMap<>();

    /** A type without a game icon, as pixel art of a game icon's size: a disc in its colour, outlined, its glyph within. */
    static BufferedImage badge(PoiType type) {
        return BADGES.computeIfAbsent(type, t -> {
            int size = (int) ICON_PIXELS;
            var badge = new BufferedImage(size, size, TYPE_INT_ARGB);
            var g = badge.createGraphics();
            g.drawImage(disc(size, t.color, OUTLINE), 0, 0, null);
            g.setRenderingHint(KEY_ANTIALIASING, VALUE_ANTIALIAS_ON);
            g.setRenderingHint(KEY_STROKE_CONTROL, VALUE_STROKE_PURE);
            g.setColor(GLYPH);
            g.setStroke(new BasicStroke(1.2f, CAP_ROUND, JOIN_ROUND));
            draw(g, GLYPHS.getOrDefault(t.name(), "O -0.4 -0.4 0.8 0.8"), size / 2.0, size / 2.0, size / 2.0 * 0.62);
            g.dispose();
            return badge;
        });
    }

    /** {@code ring}: the colour of a pixel ring round the icon, or null. */
    static void paintGameIcon(Graphics2D g, BufferedImage icon, double cx, double cy, double size, Color ring) {
        // Always the size of a game icon: a game icon's 15 pixels in whole steps, a spell's sprite (more pixels) also
        // smoothly in between.
        double scale = size / max(icon.getWidth(), icon.getHeight());
        if (ring != null) {
            pixels(g, outline(icon, ring, scale), cx, cy, scale);
        }
        pixels(g, icon, cx, cy, scale);
    }

    /**
     * An item's picture in a game icon's round frame: the frame and ring as pixel art of a game icon's size, the picture
     * in its own pixels enlarged by whole steps as far as the frame has room (smaller, smoothed).
     */
    static void paintPicture(Graphics2D g, BufferedImage image, double cx, double cy, double size, Color ring) {
        int base = (int) ICON_PIXELS;
        double fit = size / base, scale = fit >= 1 ? max(1, round(fit)) : fit;
        if (ring != null) {
            pixels(g, outline(frame(), ring, scale), cx, cy, scale);
        }
        pixels(g, frame(), cx, cy, scale);
        pixels(g, image, cx, cy, (base - 2) * scale / max(image.getWidth(), image.getHeight()));
    }

    static final Color FRAME = new Color(0xbfbfbf);
    static final Map<BufferedImage, BufferedImage> FRAMES = Collections.synchronizedMap(new WeakHashMap<>());

    /** The game's round frame as its own icons have it (a bank's, painted grey within); our disc while none is loaded. */
    static BufferedImage frame() {
        BufferedImage model = GameIconSprites.get(PoiType.BANK);
        model = model != null ? model : GameIconSprites.get(PoiType.ANVIL);
        if (model == null) {
            return disc((int) ICON_PIXELS, FRAME, Color.BLACK);
        }
        return FRAMES.computeIfAbsent(model, m -> {
            var frame = new BufferedImage(m.getWidth(), m.getHeight(), TYPE_INT_ARGB);
            for (int y = 0; y < m.getHeight(); y++) {
                for (int x = 0; x < m.getWidth(); x++) {
                    frame.setRGB(x, y, !drawn(m, x, y) ? 0 : edge(m, x, y) ? m.getRGB(x, y) : FRAME.getRGB());
                }
            }
            return frame;
        });
    }

    /** The rings of {@link #paintCached}'s emphasis: none, the selected's yellow, the hovered's white. */
    static final Color[] RINGS = {null, PlayerMarker.YELLOW, new Color(255, 255, 255, 210)};
    static final Color RING_EDGE = new Color(0, 0, 0, 150);
    static final Map<List<Object>, BufferedImage> OUTLINES = Collections.synchronizedMap(new HashMap<>());

    /**
     * A ring in {@code color} following an icon's outline two pixels out (round for a round icon, a triangle for a
     * tablet's), a dark pixel on either side, in the icon's own pixels and centred as it: drawn at the icon's
     * {@code scale}, and two pixels wide below scale 2, where one would be hard to see.
     */
    static BufferedImage outline(BufferedImage icon, Color color, double scale) {
        int width = scale < 2 ? 2 : 1;
        return OUTLINES.computeIfAbsent(List.of(icon, color.getRGB(), width), k -> {
            int pad = 3 + width;
            var ring = new BufferedImage(icon.getWidth() + 2 * pad, icon.getHeight() + 2 * pad, TYPE_INT_ARGB);
            for (int y = 0; y < ring.getHeight(); y++) {
                for (int x = 0; x < ring.getWidth(); x++) {
                    double d = pad + 1;
                    for (int iy = max(0, y - pad - pad); iy < min(icon.getHeight(), y + 1); iy++) {
                        for (int ix = max(0, x - pad - pad); ix < min(icon.getWidth(), x + 1); ix++) {
                            d = drawn(icon, ix, iy) ? min(d, hypot(x - pad - ix, y - pad - iy)) : d;
                        }
                    }
                    ring.setRGB(x, y, d > 2 && d <= 2 + width ? color.getRGB() : d > 1 && d <= 3 + width ? RING_EDGE.getRGB() : 0);
                }
            }
            return ring;
        });
    }

    /** Pixel art centred on (cx, cy), {@code scale} times its size: enlarged crisp, in whole pixels; smaller, smoothed. */
    static void pixels(Graphics2D g, BufferedImage image, double cx, double cy, double scale) {
        double w = image.getWidth() * scale, h = image.getHeight() * scale;
        var copy = (Graphics2D) g.create();
        copy.setRenderingHint(KEY_INTERPOLATION, scale >= 1
            ? VALUE_INTERPOLATION_NEAREST_NEIGHBOR : VALUE_INTERPOLATION_BILINEAR);
        copy.drawImage(image, new AffineTransform(scale, 0, 0, scale, round(cx - w / 2), round(cy - h / 2)), null);
        copy.dispose();
    }

    static final Map<List<Object>, BufferedImage> DISCS = new ConcurrentHashMap<>();

    /**
     * A pixel-art disc {@code size} pixels across, as the game draws its icons' frames: {@code edge} on its outermost
     * ring of pixels, {@code fill} within (null: a ring alone). Made once per size and colours.
     */
    static BufferedImage disc(int size, Color fill, Color edge) {
        return DISCS.computeIfAbsent(List.of(size, fill == null ? 0 : fill.getRGB(), edge.getRGB()), k -> {
            var disc = new BufferedImage(size, size, TYPE_INT_ARGB);
            double c = (size - 1) / 2.0, r = size / 2.0;
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    double d = hypot(x - c, y - c);
                    if (d > r - 1 && d <= r) {
                        disc.setRGB(x, y, edge.getRGB());
                    } else if (d <= r - 1 && fill != null) {
                        disc.setRGB(x, y, fill.getRGB());
                    }
                }
            }
            return disc;
        });
    }

    /** Draws a glyph from {@code poi_glyphs.txt} in units of {@code s} around {@code (x, y)}; the file lists the ops. */
    static void draw(Graphics2D g, String ops, double x0, double y0, double s0) {
        // The origin and unit; U moves them until the path is painted.
        double x = x0, y = y0, s = s0;
        Path2D pen = new Path2D.Double();
        for (String op : ops.split(";")) {
            var t = op.trim().split(" ");
            var a = new double[8];
            for (int i = 1; i < t.length; i++) {
                a[i] = Double.parseDouble(t[i]);
            }
            double px = x + s * a[1], py = y + s * a[2], w = s * a[3], h = s * a[4];
            boolean fill = Character.isUpperCase(t[0].charAt(0));
            switch (t[0]) {
                case "g":
                    pen = new GeneralPath();
                    break;
                case "m":
                    pen.moveTo(px, py);
                    break;
                case "l":
                    pen.lineTo(px, py);
                    break;
                case "q":
                    pen.quadTo(px, py, x + w, y + h);
                    break;
                case "b":
                    pen.curveTo(px, py, x + w, y + h, x + s * a[5], y + s * a[6]);
                    break;
                case "z":
                    pen.closePath();
                    break;
                case "P":
                case "p":
                    shape(g, pen, fill);
                    pen = new Path2D.Double();
                    x = x0;
                    y = y0;
                    s = s0;
                    break;
                case "U":
                    x = px;
                    y = py;
                    s = w;
                    break;
                case "R":
                    g.fill(new Rectangle2D.Double(px, py, w, h));
                    break;
                case "O":
                case "o":
                    shape(g, new Ellipse2D.Double(px, py, w, h), fill);
                    break;
                case "L":
                    g.draw(new Line2D.Double(px, py, x + w, y + h));
                    break;
                case "A":
                case "a":
                    shape(g, new Arc2D.Double(px, py, w, h, a[5], a[6], (int) a[7]), fill);
                    break;
                case "W":
                    g.fill(new RoundRectangle2D.Double(px, py, w, h, s * a[5], s * a[5]));
                    break;
                case "c":
                    g.fill(PlayerMarker.circle(px, py, w));
                    break;
                case "C":
                    g.setColor(OUTLINE);
                    break;
                case "K":
                    g.setColor(new Color((int) a[1], (int) a[2], (int) a[3]));
                    break;
                case "w":
                    g.setStroke(a[2] == 1 ? new BasicStroke((float) a[1], CAP_ROUND, JOIN_ROUND)
                        : new BasicStroke((float) a[1]));
                    break;
                default:
                    throw new IllegalArgumentException(op);
            }
        }
    }

    /** Upper-case ops fill, lower-case ones outline. */
    static void shape(Graphics2D g, Shape shape, boolean fill) {
        if (fill) {
            g.fill(shape);
        } else {
            g.draw(shape);
        }
    }

    static final Map<String, String> GLYPHS = new HashMap<>();

    static {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
            PoiIcons.class.getResourceAsStream("poi_glyphs.txt"), StandardCharsets.UTF_8))) {
            // A blank line or one without its tab is skipped, not a class that fails to load.
            reader.lines().map(line -> line.split("\t", 2))
                .filter(cells -> cells.length == 2 && !cells[0].startsWith("#"))
                .forEach(cells -> GLYPHS.put(cells[0], cells[1]));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A small image of an icon, for buttons and lists. */
    static BufferedImage image(PoiType type, int size) {
        return sprite(size + 2, size + 2, 1, g -> {
            g.setRenderingHint(KEY_STROKE_CONTROL, VALUE_STROKE_PURE);
            paint(g, type, (size + 2) / 2.0 - 0.5, (size + 2) / 2.0 - 1, size - 1, null, false);
        });
    }

    static BufferedImage icon(int size, String name) {
        return sprite(size, size, 1, g -> draw(g, GLYPHS.get(name), 0, 0, 1));
    }
}
