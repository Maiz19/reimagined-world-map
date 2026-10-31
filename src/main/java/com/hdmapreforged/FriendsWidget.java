package com.hdmapreforged;

import static java.lang.Math.*;
import static com.hdmapreforged.MapView.*;

import java.text.*;
import java.awt.*;
import java.awt.geom.*;
import java.util.*;
import java.util.List;
import java.util.stream.*;
import lombok.*;
import net.runelite.api.coords.*;

/**
 * The friends button at the map's bottom left, opening into the party list and a member's details; painted in the
 * sidebar too ({@link #paintList}). Swing thread only.
 */
@RequiredArgsConstructor
class FriendsWidget implements Widget {
    static final Color OTHER_WORLD = new Color(255, 190, 120), FRIEND = new Color(150, 150, 160);
    static final Color WORLD = new Color(190, 190, 195);
    static final Font ROW = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
    static final int WIDTH = 256, LEFT = 12, ROW_HEIGHT = 22;
    static final NumberFormat AMOUNT = NumberFormat.getIntegerInstance();

    final PartyMap party;
    /** The map it is drawn on; null in the sidebar. */
    final MapView view;
    final MemberPages pages;
    final Runnable repaint;
    /** Repaints while drops rise. */
    final PartyOverlay.Ticker ticker = new PartyOverlay.Ticker(() -> FriendsWidget.this.repaint.run());
    boolean dropsMoving, open;
    long selected = -1;
    /** The member under the mouse on this view's map, or -1. */
    long hovered = -1;
    boolean folded;

    long selected() {
        return open ? selected : -1;
    }

    void show(long id) {
        open = true;
        selected = id;
    }

    /** The markers, then the party's members with a name and no marker: one row per member, not per name shown. */
    static List<PartyMembers.Marker> rows(List<PartyMembers.Marker> party, List<PartyMembers.Marker> group) {
        var rows = new ArrayList<PartyMembers.Marker>();
        var ids = new HashSet<Long>();
        // Not logged in: nothing to show without a name.
        Stream.concat(party.stream(), group.stream()).filter(m -> m.point != null || m.name != null)
            .filter(m -> ids.add(m.id)).forEach(rows::add);
        return rows;
    }

    public void paint(Graphics2D g, int width, int height, int bottom, Clicks clicks) {
        dropsMoving = false;
        try {
            paintWidget(g, width, bottom, clicks);
        } finally {
            ticker.painted(dropsMoving);
        }
    }

    List<PartyMembers.Marker> groupNow() {
        try {
            return party.groupMembers();
        } catch (ConcurrentModificationException e) {
            // Changed on another thread while read; the next frame has them.
            return Collections.emptyList();
        }
    }

    void paintWidget(Graphics2D g, int width, int bottom, Clicks clicks) {
        if (!party.config.partyFriendsTab()) {
            // No list to close it with: no one stays chosen (their name shown) by a click on their marker.
            selected = -1;
            return;
        }
        var rows = rows(party.members.markers(System.currentTimeMillis()), groupNow());
        if (rows.isEmpty() && !party.party.isInParty()) {
            if (party.lastParty() != null) {
                clicks.add(tab(g, bottom, clicks, "Rejoin party"), () -> party.join(view), "Join your last party again");
            }
            return;
        }
        int w = min(WIDTH, width - 24);
        var tab = tab(g, bottom, clicks, "Party · " + rows.size() + (open ? "  ▾" : "  ▴"));
        double tabTop = tab.getY();
        clicks.add(tab, () -> {
            open = !open;
            repaint.run();
        }, open ? "Close the list" : PoiType.t("t24"));
        if (!open) {
            return;
        }
        PartyMembers.Marker chosen = null;
        for (var row : rows) {
            chosen = row.id == selected && selected >= 0 ? row : chosen;
        }
        selected = chosen != null ? selected : -1;
        if (rows.isEmpty()) {
            String text = "No one else in your party yet";
            g.setFont(ROW);
            double boxWidth = max(w, g.getFontMetrics().stringWidth(text) + 20);
            var alone = new RoundRectangle2D.Double(LEFT, tabTop - 4 - ROW_HEIGHT - 6, boxWidth, ROW_HEIGHT + 6, 10, 10);
            box(g, alone, CONTROL_FILL, CONTROL_EDGE);
            g.setColor(FRIEND);
            g.drawString(text, LEFT + 10, middle(alone.getCenterY(), g.getFontMetrics()));
            return;
        }
        // Opens upwards, as many rows as fit (with room for a chosen one's folded details above, so one can switch
        // between them); the last line then says how many more.
        int fit = max(1, (int) ((tabTop - 60 - (chosen != null ? 34 + MemberPages.STATUS_H : 0)) / ROW_HEIGHT));
        var shown = rows.size() > fit ? rows.subList(0, max(1, fit - 1)) : rows;
        int more = rows.size() - shown.size(), lines = shown.size() + (more > 0 ? 1 : 0);
        double listTop = tabTop - 4 - lines * ROW_HEIGHT - 6;
        var list = new RoundRectangle2D.Double(LEFT, listTop, w, lines * ROW_HEIGHT + 6, 10, 10);
        box(g, list, CONTROL_FILL, CONTROL_EDGE);
        g.setFont(ROW);
        var metrics = g.getFontMetrics();
        for (int i = 0; i < shown.size(); i++) {
            var row = shown.get(i);
            double top = listTop + 3 + i * ROW_HEIGHT;
            var area = new RoundRectangle2D.Double(LEFT + 3, top, w - 6, ROW_HEIGHT, 8, 8);
            if (clicks.hovered(area) || row == chosen) {
                g.setColor(CONTROL_HOVER);
                g.fill(area);
            }
            clicks.add(area, () -> {
                if (row.point != null) {
                    party.follow(view, row.id);
                }
                selected = row.id;
                repaint.run();
            }, PoiType.t("fwLook", row.label()));
            line(g, row, LEFT, top + ROW_HEIGHT / 2.0, w, metrics);
        }
        if (more > 0) {
            double cy = listTop + 3 + shown.size() * ROW_HEIGHT + ROW_HEIGHT / 2.0;
            g.setColor(FRIEND);
            g.drawString("+" + more + " more", LEFT + 24, middle(cy, metrics));
        }
        if (chosen != null) {
            paintDetails(g, chosen, LEFT, w, listTop - 4, clicks);
        }
    }

    void paintDetails(Graphics2D g, PartyMembers.Marker row, double x0, int w, double bottomY, Clicks clicks) {
        int panelW = max(w, 4 * Orbs.WIDTH + 3 * 4 + 16), full = 30 + pages.height(panelW - 16) + 10;
        // Folded (or the map too low for all of it): the name and the orbs only.
        boolean cramped = bottomY - full < 44, compact = folded || cramped;
        int height = compact ? 30 + MemberPages.STATUS_H + 4 : full;
        double top = bottomY - height;
        var panel = new RoundRectangle2D.Double(x0, top, panelW, height, 12, 12);
        box(g, panel, CONTROL_FILL, CONTROL_EDGE);
        g.setFont(CONTROL_FONT);
        var title = g.getFontMetrics();
        if (row.point != null) {
            // The name looks at them again, as the panel stays open after one looks elsewhere.
            var name = new RoundRectangle2D.Double(x0 + 4, top + 4, panelW - (cramped ? 32 : 54), 22, 8, 8);
            if (clicks.hovered(name)) {
                g.setColor(CONTROL_HOVER);
                g.fill(name);
            }
            clicks.add(name, () -> party.follow(view, row.id), "Follow " + row.label());
        }
        dot(g, row.color(), x0 + 12, top + 11);
        g.setColor(Color.WHITE);
        text(g, fit(row.label(), title, panelW - 130), x0 + 26, top + 19);
        if (row.world > 0) {
            String world = "W" + row.world;
            g.setFont(ROW);
            var rowMetrics = g.getFontMetrics();
            int mine = party.myWorld;
            boolean elsewhere = mine > 0 && row.world != mine;
            double right = x0 + panelW - (cramped ? 34 : 56);
            g.setColor(elsewhere ? OTHER_WORLD : WORLD);
            text(g, world, right - rowMetrics.stringWidth(world), top + 20);
        }
        titleButton(g, clicks, title, x0 + panelW - 26, top, "×", () -> {
            selected = -1;
            repaint.run();
        }, "Close");
        if (!cramped) {
            titleButton(g, clicks, title, x0 + panelW - 48, top, folded ? "+" : "\u2013", () -> {
                folded = !folded;
                repaint.run();
            }, folded ? "Unfold" : PoiType.t("fwFold"));
        }
        if (compact) {
            pages.paintStatus(g, party.members.gear(row.id), x0 + 8, top + 28, panelW - 16);
            dropsMoving |= PartyOverlay.paintXp(g, party.xp(row.id), party::skill, x0 + panelW - 64, top + height - 8);
            return;
        }
        dropsMoving |= pages.paint(g, row.id, x0 + 8, top + 30, panelW - 16, clicks, repaint);
    }

    /** A member's dot, name and world on the line centred at cy, {@code w} wide from x. */
    void line(Graphics2D g, PartyMembers.Marker row, double x, double cy, int w, FontMetrics metrics) {
        dot(g, row.color(), x + 10, cy - 4);
        String world = row.world > 0 ? "W" + row.world : "";
        int worldWidth = metrics.stringWidth(world), mine = party.myWorld;
        g.setColor(row.world > 0 && mine > 0 && row.world != mine ? OTHER_WORLD : WORLD);
        text(g, world, x + w - 10 - worldWidth, middle(cy, metrics));
        g.setColor(row.point != null ? Color.WHITE : FRIEND);
        text(g, fit(row.label(), metrics, w - 38 - worldWidth), x + 24, middle(cy, metrics));
    }

    /**
     * The sidebar's party, {@code w} wide from the top: each member's row with where they are and their latest drops
     * (named on hover), the one clicked with its details under it; the height painted.
     */
    int paintList(Graphics2D g, int w, Clicks clicks, MapPanel side) {
        dropsMoving = false;
        boolean in = party.party.isInParty();
        var rows = in ? party.rows() : List.<PartyMembers.Marker>of();
        double y = 0;
        if (!in && party.lastParty() != null) {
            clicks.add(button(g, clicks, 0, 0, w, 28, "Rejoin party"), () -> party.join(side), PoiType.t("rejoinTip"));
            y = 34;
        }
        g.setFont(ROW);
        var metrics = g.getFontMetrics();
        if (rows.isEmpty()) {
            g.setColor(WORLD);
            return (int) (MemberPages.wrap(g, PoiType.t(in ? "mpAlone" : "t21"), 0, y + metrics.getAscent(), w) + 6);
        }
        for (var row : rows) {
            var got = party.members.session(row.id);
            var drops = got == null ? List.<PartyMembers.Loot>of() : got.latest.subList(0, min(3, got.latest.size()));
            var area = new RoundRectangle2D.Double(0, y, w, drops.isEmpty() ? 42 : 74, 8, 8);
            box(g, area, clicks.hovered(area) || row.id == selected ? CONTROL_HOVER : CONTROL_FILL, CONTROL_EDGE);
            g.setFont(ROW);
            line(g, row, 0, y + 13, w, metrics);
            g.setFont(SMALL_PLACE_FONT);
            g.setColor(WORLD);
            text(g, fit(side.where(row), g.getFontMetrics(), w - 30), 24, y + 33);
            for (int k = 0; k < drops.size(); k++) {
                var drop = drops.get(k);
                var at = new RoundRectangle2D.Double(20 + k * 50, y + 38, 40, 32, 6, 6);
                var picture = party.item(drop.item, drop.quantity);
                if (picture != null) {
                    g.drawImage(picture, (int) at.x, (int) at.y, null);
                }
                if (drop.name == null) {
                    // Its worth over the picture's foot, as the game writes amounts over items.
                    g.setFont(SMALL_FONT);
                    g.setColor(Color.WHITE);
                    text(g, PartyOverlay.coins(drop.value), at.x + 1, at.y + 30);
                }
                clicks.add(at, () -> { }, drop.name != null ? drop.name : party.itemName(drop.item));
            }
            dropsMoving |= PartyOverlay.paintXp(g, party.xp(row.id), party::skill, w - 64, area.getMaxY() - 5);
            boolean open = row.id == selected;
            clicks.add(area, () -> {
                selected = open ? -1 : row.id;
                repaint.run();
            }, open ? "Close" : PoiType.t("mpWhat", row.label()));
            y = area.getMaxY() + 4;
            if (open && row.point != null) {
                clicks.add(button(g, clicks, 0, y, 90, 28, "On map"), () -> {
                    side.showOnMap.accept(row.point);
                    party.follow(row.id);
                }, PoiType.t("mpOpen"));
                if (row.world > 0 && party.myWorld > 0 && row.world != party.myWorld) {
                    g.setFont(ROW);
                    g.setColor(OTHER_WORLD);
                    text(g, "World " + row.world, 100, middle(y + 14, metrics));
                    clicks.add(new RoundRectangle2D.Double(96, y, w - 96, 28, 8, 8), () -> { }, PoiType.t("t22"));
                }
                y += 34;
            }
            if (open) {
                dropsMoving |= pages.paint(g, row.id, 0, y, w, clicks, repaint);
                y += pages.height(w) + 8;
            }
        }
        ticker.painted(dropsMoving);
        return (int) y;
    }

    /** A rounded button with {@code label} in the map's control font. */
    static RoundRectangle2D button(Graphics2D g, Clicks clicks, double x, double y, double w, double h, String label) {
        var shape = new RoundRectangle2D.Double(x, y, w, h, 10, 10);
        box(g, shape, clicks.hovered(shape) ? CONTROL_HOVER : CONTROL_FILL, CONTROL_EDGE);
        g.setFont(CONTROL_FONT);
        var metrics = g.getFontMetrics();
        g.setColor(Color.WHITE);
        text(g, label, x + (w - metrics.stringWidth(label)) / 2.0, middle(y + h / 2.0, metrics));
        return shape;
    }

    static RoundRectangle2D tab(Graphics2D g, int bottom, Clicks clicks, String label) {
        g.setFont(CONTROL_FONT);
        return button(g, clicks, LEFT, bottom - 2 * CONTROL - 6, floorGroupWidth(g.getFontMetrics()), CONTROL, label);
    }

    static void dot(Graphics2D g, Color color, double x, double y) {
        g.setColor(color != null ? color : FRIEND);
        g.fill(new Ellipse2D.Double(x, y, 8, 8));
    }

    static void titleButton(Graphics2D g, Clicks clicks, FontMetrics title, double x, double top,
        String mark, Runnable action, String tip) {
        var button = new RoundRectangle2D.Double(x, top + 5, 20, 20, 8, 8);
        if (clicks.hovered(button)) {
            g.setColor(CONTROL_HOVER);
            g.fill(button);
        }
        g.setColor(Floating.LIGHT);
        g.setFont(CONTROL_FONT);
        text(g, mark, button.getCenterX() - title.stringWidth(mark) / 2.0, top + 20);
        clicks.add(button, action, tip);
    }

    static String fit(String text, FontMetrics metrics, int width) {
        if (metrics.stringWidth(text) <= width) {
            return text;
        }
        String cut = text;
        while (cut.length() > 1 && metrics.stringWidth(cut + "…") > width) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut + "…";
    }
}
