package com.hdmapreforged;

import java.awt.*;
import java.awt.image.*;
import java.util.*;
import java.util.List;

/** Outlined map text, rendered once then copied (each label is nine antialiased strings). Swing thread only. */
final class TextSprites {
    static final Color OUTLINE = new Color(0, 0, 0, 200);

    /** By text, font, colour and device scale (in tenths); at most 1500. */
    final Map<List<Object>, BufferedImage> cache = new LruMap<>(256, 1500);

    /** Like {@code g.drawString} with the given font, outlined. */
    void draw(Graphics2D g, String text, Font font, float x, float y, Color color) {
        var transform = g.getTransform();
        double device = PoiIcons.device(transform);
        List<Object> key = List.of(text, font, color.getRGB(), (int) Math.round(device * 10));
        g.setFont(font);
        var metrics = g.getFontMetrics();
        int ascent = metrics.getAscent();
        BufferedImage sprite = cache.computeIfAbsent(key, k -> PoiIcons.sprite(metrics.stringWidth(text) + 4,
            metrics.getHeight() + 4, device, sg -> {
                sg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                sg.setFont(font);
                outlined(sg, text, 2, 2 + ascent, color);
            }));
        PoiIcons.blit(g, transform, sprite, x - 2, y - ascent - 2);
    }

    static void outlined(Graphics2D g, String text, float x, float y, Color color) {
        g.setColor(OUTLINE);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                if (dx != 0 || dy != 0) {
                    g.drawString(text, x + dx, y + dy);
                }
            }
        }
        g.setColor(color);
        g.drawString(text, x, y);
    }
}
