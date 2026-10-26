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
        PoiIcons.glyph(g, "ROUTES", x, y);
        text(g, "Routes", x + 20, middle(y, metrics));
        clicks.add(button, toggle, PoiType.t("t29"));
    }
}
