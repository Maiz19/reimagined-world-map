package com.hdmapreforged;

import java.awt.*;
import java.util.*;
import net.runelite.api.coords.*;

/** One's grave while it stands, drawn over the other icons; clicked, its card offers a route there. Swing thread. */
final class GraveMarker implements MapView.Overlay, MapView.HitLayer {
    final MapView view;
    WorldPoint at;
    Poi grave;

    GraveMarker(MapView view) {
        this.view = view;
        view.addOverlay(this);
        view.hitLayers.add(this);
    }

    /** Null when no grave stands. */
    void set(WorldPoint at) {
        if (Objects.equals(at, this.at)) {
            return;
        }
        if (grave != null && view.selected() == grave) {
            view.select(null);
        }
        this.at = at;
        grave = null;
        view.repaint();
    }

    /** Made once the map list knows the grave's map (it may stand before the map's data came), again for a new list. */
    Poi grave() {
        var on = at == null ? null : view.mapOf(at);
        if (grave == null || grave.map != on) {
            grave = on == null ? null : new Poi(PoiType.GRAVE, "Your grave", at, on, null, Needs.NONE, "Grave", null, null);
        }
        return grave;
    }

    public void paint(Graphics2D g, Projection p) {
        Point center = center(p);
        if (center != null) {
            boolean selected = view.selected() == grave;
            double size = view.iconSize(p.zoom()) * (selected ? 1.25 : 1.1);
            PoiIcons.paintCached(g, PoiType.GRAVE, 0, center.x, center.y, size, selected ? 1 : 0, false);
        }
    }

    public Poi hit(Point point, Projection p) {
        Point center = center(p);
        return center != null && center.distance(point) <= view.iconSize(p.zoom()) * 0.6 ? grave : null;
    }

    Point center(Projection p) {
        var shown = grave();
        if (shown == null || !shown.isOn(p.map())) {
            return null;
        }
        var at = p.shown(shown.location);
        return new Point((int) p.screenX(at.getX() + 0.5), (int) p.screenY(at.getY() + 0.5));
    }
}
