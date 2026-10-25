package com.hdmapreforged;

import static java.lang.Math.*;
import static com.hdmapreforged.MapView.*;

import java.text.*;
import net.runelite.api.*;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.*;
import java.util.*;
import java.util.List;
import java.util.function.*;
import lombok.*;
import net.runelite.api.coords.*;

/** The friends button at the map's bottom left, opening into the party list and a member's details. Swing thread only. */
@RequiredArgsConstructor
final class FriendsWidget implements Widget {
    @RequiredArgsConstructor
    static final class Member {
        final long id;
        final String name;
    }

    @RequiredArgsConstructor
    static final class Row {
        final String name;
        final int world;
        final WorldPoint point;
        final Color color;
        /** The party member's id. */
        final long id;
    }

    interface Images {
        BufferedImage item(int id, int quantity);

        BufferedImage skill(int ordinal);

        default BufferedImage sprite(int id) {
            return null;
        }
    }

    static final Color OTHER_WORLD = new Color(255, 190, 120), FRIEND = new Color(150, 150, 160);
    static final Color WORLD = new Color(190, 190, 195);
    static final Font ROW = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
    static final int WIDTH = 256, LEFT = 12, ROW_HEIGHT = 22;
    static final NumberFormat AMOUNT = NumberFormat.getIntegerInstance();

    final Supplier<List<PartyMapMembers.Marker>> party;
    final Supplier<List<Member>> group;
    final BooleanSupplier inGroup;
    final IntSupplier myWorld;
    final Supplier<Boolean> enabled;
    final Consumer<Row> focus;
    final Runnable repaint;
    /** Repaints while drops rise; made by the caller with the same repaint. */
    final PartyMapOverlay.Ticker ticker;
    boolean dropsMoving, open;
    long selected = -1;
    boolean folded;
    /** A member's details, as the sidebar paints them too; null until the party map gives it. */
    MemberPages pages;


    long selected() {
        return open ? selected : -1;
    }

    BooleanSupplier canJoin = () -> false;
    Runnable join = () -> { };

    void show(long id) {
        open = true;
        selected = id;
    }

    static List<Row> rows(List<PartyMapMembers.Marker> party, List<Member> group) {
        var rows = new ArrayList<Row>();
        // One row per member, not per name shown: two not named yet are two "Party member" rows, as on the map.
        var ids = new HashSet<Long>();
        for (var marker : party) {
            if (ids.add(marker.id)) {
                rows.add(new Row(marker.name != null ? marker.name : "Party member", marker.world, marker.point,
                    PartyMapOverlay.color(marker.id), marker.id));
            }
        }
        for (Member member : group) {
            // Not logged in: nothing to show.
            if (member.name != null && ids.add(member.id)) {
                rows.add(new Row(member.name, 0, null, null, member.id));
            }
        }
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

    List<Member> groupNow() {
        try {
            return group.get();
        } catch (ConcurrentModificationException e) {
            // Changed on another thread while read; the next frame has them.
            return Collections.emptyList();
        }
    }

    void paintWidget(Graphics2D g, int width, int bottom, Clicks clicks) {
        if (!enabled.get()) {
            // No list to close it with: no one stays chosen (their name shown) by a click on their marker.
            selected = -1;
            return;
        }
        var rows = rows(party.get(), groupNow());
        if (rows.isEmpty() && !inGroup.getAsBoolean()) {
            if (canJoin.getAsBoolean()) {
                clicks.add(tab(g, bottom, clicks, "Rejoin party"), join, "Join your last party again");
            }
            return;
        }
        int w = min(WIDTH, width - 24);
        var tab = tab(g, bottom, clicks, "Party · " + rows.size() + (open ? "  ▾" : "  ▴"));
        double tabTop = tab.getY();
        clicks.add(tab, () -> {
            open = !open;
            repaint.run();
        }, open ? "Close the list" : "Your party: click someone to see where they are");
        if (!open) {
            return;
        }
        Row chosen = null;
        for (Row row : rows) {
            chosen = row.id == selected && selected >= 0 && pages != null ? row : chosen;
        }
        selected = chosen != null ? selected : -1;
        if (rows.isEmpty()) {
            String text = "No one else in your party yet";
            g.setFont(ROW);
            double boxWidth = max(w, g.getFontMetrics().stringWidth(text) + 20);
            var alone = new RoundRectangle2D.Double(LEFT, tabTop - 4 - ROW_HEIGHT - 6, boxWidth,
                ROW_HEIGHT + 6, 10, 10);
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
        int mine = myWorld.getAsInt();
        for (int i = 0; i < shown.size(); i++) {
            Row row = shown.get(i);
            double top = listTop + 3 + i * ROW_HEIGHT;
            var area = new RoundRectangle2D.Double(LEFT + 3, top, w - 6, ROW_HEIGHT, 8, 8);
            if (clicks.hovered(area) || row == chosen) {
                g.setColor(CONTROL_HOVER);
                g.fill(area);
            }
            clicks.add(area, () -> {
                if (row.point != null) {
                    focus.accept(row);
                }
                selected = row.id;
                repaint.run();
            }, "Look at " + row.name + " and what they carry");
            double cy = top + ROW_HEIGHT / 2.0;
            dot(g, row.color, LEFT + 10, cy - 4);
            String world = row.world > 0 ? "W" + row.world : "";
            int worldWidth = metrics.stringWidth(world);
            g.setColor(row.world > 0 && mine > 0 && row.world != mine ? OTHER_WORLD : WORLD);
            g.drawString(world, LEFT + w - 10 - worldWidth, middle(cy, metrics));
            g.setColor(row.point != null ? Color.WHITE : FRIEND);
            String name = fit(row.name, metrics, w - 38 - worldWidth);
            g.drawString(name, LEFT + 24, middle(cy, metrics));
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

    void paintDetails(Graphics2D g, Row row, double x0, int w, double bottomY, Clicks clicks) {
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
            clicks.add(name, () -> focus.accept(row), "Follow " + row.name);
        }
        dot(g, row.color, x0 + 12, top + 11);
        g.setColor(Color.WHITE);
        text(g, fit(row.name, title, panelW - 130), x0 + 26, top + 19);
        if (row.world > 0) {
            String world = "W" + row.world;
            g.setFont(ROW);
            var rowMetrics = g.getFontMetrics();
            int mine = myWorld.getAsInt();
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
            }, folded ? "Unfold" : "Fold up: the name and the orbs only");
        }
        if (compact) {
            pages.paintStatus(g, pages.gear.apply(row.id), x0 + 8, top + 28, panelW - 16);
            dropsMoving |= PartyMapOverlay.paintXp(g, pages.drops.apply(row.id), pages.images::skill,
                x0 + panelW - 64, top + height - 8);
            return;
        }
        dropsMoving |= pages.paint(g, row.id, x0 + 8, top + 30, panelW - 16, clicks, repaint);
    }

    static RoundRectangle2D tab(Graphics2D g, int bottom, Clicks clicks, String label) {
        g.setFont(CONTROL_FONT);
        var metrics = g.getFontMetrics();
        int tabWidth = floorGroupWidth(metrics);
        double tabTop = bottom - 2 * CONTROL - 6;
        var tab = new RoundRectangle2D.Double(LEFT, tabTop, tabWidth, CONTROL, 10, 10);
        box(g, tab, clicks.hovered(tab) ? CONTROL_HOVER : CONTROL_FILL, CONTROL_EDGE);
        g.setColor(Color.WHITE);
        text(g, label, LEFT + (tabWidth - metrics.stringWidth(label)) / 2.0, middle(tabTop + CONTROL / 2.0, metrics));
        return tab;
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
