package com.hdmapreforged;

import static com.hdmapreforged.MapView.*;

import lombok.*;
import java.awt.*;
import java.awt.geom.*;
import java.util.function.*;

/** The custom routes button above the floor buttons: toggles the custom routes panel. Swing thread only. */
@RequiredArgsConstructor
final class RoutesButton implements Widget {
    static final int LEFT = 12;
    static final Color OPEN = new Color(60, 66, 44, 235);

    final Runnable toggle;
    final BooleanSupplier open, shown;

    public void paint(Graphics2D g, int width, int height, int bottom, Clicks clicks) {
        if (!shown.getAsBoolean()) {
            return;
        }
        g.setFont(CONTROL_FONT);
        var metrics = g.getFontMetrics();
        int w = floorGroupWidth(metrics);
        var button = new RoundRectangle2D.Double(LEFT, bottom - CONTROL, w, CONTROL, 10, 10);
        box(g, button, open.getAsBoolean() ? OPEN : clicks.hovered(button) ? CONTROL_HOVER : CONTROL_FILL, CONTROL_EDGE);
        // Three stops joined by a dashed line, then the word.
        int textWidth = metrics.stringWidth("Routes");
        double x = LEFT + (w - textWidth - 20) / 2.0, y = bottom - CONTROL / 2.0;
        var path = new Path2D.Double();
        path.moveTo(x, y + 5);
        path.lineTo(x + 6, y - 5);
        path.lineTo(x + 12, y + 3);
        g.setColor(PlayerMarker.YELLOW);
        g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[]{2.5f, 2f}, 0));
        g.draw(path);
        g.setColor(Color.WHITE);
        for (var p : new double[][]{{x, y + 5}, {x + 6, y - 5}, {x + 12, y + 3}}) {
            g.fill(new Ellipse2D.Double(p[0] - 2, p[1] - 2, 4, 4));
        }
        text(g, "Routes", x + 20, middle(y, metrics));
        clicks.add(button, toggle, "Custom routes: your own routes with several stops, to order, save and run");
    }
}
