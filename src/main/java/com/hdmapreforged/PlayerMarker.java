package com.hdmapreforged;

import static java.awt.BasicStroke.*;
import static java.lang.Math.*;

import java.awt.*;
import java.awt.geom.*;

/** The local player on the map as a yellow target, or an arrow at the view's edge when out of sight. */
final class PlayerMarker {
    static final Color YELLOW = new Color(255, 214, 64), SHADOW = new Color(0, 0, 0, 170);
    static final Font FONT = new Font(Font.SANS_SERIF, Font.BOLD, 11);

    /** {@code otherFloor}: drawn fainter and dashed; {@code home}: in a box saying so, where the house stands. */
    static void paint(Graphics2D g, TextSprites text, double x, double y, double zoom, boolean otherFloor, boolean home) {
        double ring = 12 + max(0, min(3, zoom)) * 1.5;
        if (home) {
            box(g, x, y, ring, YELLOW);
            label(g, text, "Home", x, y - ring - 16);
        }
        var dash = otherFloor ? new float[]{4f, 3f} : null;
        // A glow, so it reads on bright and dark areas alike.
        g.setColor(new Color(255, 214, 64, otherFloor ? 30 : 60));
        g.fill(circle(x, y, ring + 5));
        for (int pass = 0; pass < 2; pass++) {
            g.setColor(pass == 0 ? SHADOW : YELLOW);
            g.setStroke(new BasicStroke(pass == 0 ? 5f : 2.6f, CAP_BUTT, JOIN_ROUND, 10f, dash, 0f));
            g.draw(circle(x, y, ring));
        }
        double in = ring - 5, out = ring + 5;
        for (int pass = 0; pass < 2; pass++) {
            g.setStroke(new BasicStroke(pass == 0 ? 2.4f : 1.4f, CAP_ROUND, JOIN_ROUND));
            g.setColor(pass == 0 ? SHADOW : YELLOW);
            for (int i = 0; i < 4; i++) {
                double a = PI / 2 * i;
                g.draw(new Line2D.Double(x + cos(a) * in, y + sin(a) * in, x + cos(a) * out,
                    y + sin(a) * out));
            }
        }
        g.setColor(otherFloor ? new Color(255, 214, 64, 150) : YELLOW);
        g.fill(circle(x, y, 5.5));
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(2f));
        g.draw(circle(x, y, 5.5));
        label(g, text, otherFloor ? "You (other floor)" : "You", x, y + ring + 8);
    }

    /** The box round a marker in a house, at its portal: room above for its label. */
    static void box(Graphics2D g, double x, double y, double ring, Color color) {
        var box = new RoundRectangle2D.Double(x - ring - 12, y - ring - 26, ring * 2 + 24, ring * 2 + 44, 10, 10);
        g.setColor(SHADOW);
        g.fill(box);
        g.setColor(color);
        g.setStroke(new BasicStroke(1.5f));
        g.draw(box);
    }

    /** Radius {@code r} round {@code (x, y)}; the party's markers and the route's ends draw with it too. */
    static Ellipse2D circle(double x, double y, double r) {
        return new Ellipse2D.Double(x - r, y - r, r * 2, r * 2);
    }

    /**
     * An arrow at {@code (x, y)} pointing at {@code angle} (radians, screen coordinates), {@code scale} times its size;
     * {@code label} may be null.
     */
    static void pointer(Graphics2D g, TextSprites text, double x, double y, double angle, double scale, Color color,
        String label) {
        var arrow = new Path2D.Double();
        arrow.moveTo(12, 0);
        arrow.lineTo(-8, -9);
        arrow.lineTo(-4, 0);
        arrow.lineTo(-8, 9);
        arrow.closePath();
        var at = AffineTransform.getTranslateInstance(x, y);
        at.rotate(angle);
        at.scale(scale, scale);
        Shape shape = at.createTransformedShape(arrow);
        g.setColor(SHADOW);
        g.setStroke(new BasicStroke(4f, CAP_ROUND, JOIN_ROUND));
        g.draw(shape);
        g.setColor(color);
        g.fill(shape);
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(1.2f));
        g.draw(shape);
        if (label != null) {
            label(g, text, label, x - cos(angle) * 22, y - sin(angle) * 22 + 4);
        }
    }

    static void label(Graphics2D g, TextSprites text, String label, double cx, double top) {
        g.setFont(FONT);
        var metrics = g.getFontMetrics();
        text.draw(g, label, FONT, (float) (cx - metrics.stringWidth(label) / 2.0), (float) (top + metrics.getAscent() / 2.0),
            YELLOW);
    }
}
