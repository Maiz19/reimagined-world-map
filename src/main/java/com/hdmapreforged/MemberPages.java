package com.hdmapreforged;

import static com.hdmapreforged.PartyMembers.*;
import static java.lang.Math.*;
import static com.hdmapreforged.FriendsWidget.*;
import static com.hdmapreforged.MapView.*;

import java.awt.*;
import java.awt.geom.*;
import java.awt.image.*;
import java.util.*;
import lombok.*;
import net.runelite.api.*;

/**
 * A party member's details, painted the same in the map's card and the sidebar: their orbs, the page tabs, the page
 * (items, worn, skills, this session) and their experience drops, laid out for the width given. Swing thread.
 */
@RequiredArgsConstructor
final class MemberPages {
    static final String[] PAGES = {"Items", "Worn", "Skills", "Session"};
    /** The page of what they gained this session. */
    static final int SESSION = 3;
    static final Color BOOSTED = new Color(120, 220, 120), DRAINED = new Color(230, 120, 110);
    static final int SLOT_W = 48, SLOT_H = 40, SKILL_W = 74, SKILL_H = 30, TABS_H = 26;
    /** A session row: an item picture high. */
    static final int ROW_H = 34;
    /** Every page as tall as the inventory, so switching pages moves nothing. */
    static final int BODY_H = 7 * SLOT_H, STATUS_H = Orbs.HEIGHT + 6;
    /** Column and row of each equipment slot, as in the game's worn items tab. */
    static final int[][] WORN = {{1, 0}, {0, 1}, {1, 1}, {0, 2}, {1, 2}, {2, 2}, null, {1, 3}, null, {0, 4},
        {1, 4}, null, {2, 4}, {2, 1}};

    final PartyMap party;
    int page;

    /** The orbs' height: one row of four where they fit, else two rows. */
    static int statusHeight(int width) {
        return width >= 4 * Orbs.WIDTH + 12 ? STATUS_H : 2 * STATUS_H;
    }

    int height(int width) {
        return TABS_H + statusHeight(width) + BODY_H;
    }

    /** Painted at (x, top), {@code width} wide; true while experience drops rise in it. */
    boolean paint(Graphics2D g, long id, double x, double top, int width, Clicks clicks, Runnable repaint) {
        var shared = party.members.gear(id);
        double tabW = width / (double) PAGES.length;
        g.setFont(ROW);
        if (g.getFontMetrics().stringWidth("Session") > tabW - 8) {
            g.setFont(SMALL_PLACE_FONT);
        }
        var metrics = g.getFontMetrics();
        for (int k = 0; k < PAGES.length; k++) {
            var tab = new RoundRectangle2D.Double(x + k * tabW, top, tabW - 4, 20, 8, 8);
            g.setColor(k == page ? new Color(70, 76, 88, 235) : clicks.hovered(tab) ? CONTROL_HOVER : new Color(40, 43, 50, 220));
            g.fill(tab);
            g.setColor(k == page ? Color.WHITE : new Color(180, 180, 186));
            text(g, PAGES[k], tab.getCenterX() - metrics.stringWidth(PAGES[k]) / 2.0, top + 14);
            int chosen = k;
            clicks.add(tab, () -> {
                page = chosen;
                repaint.run();
            }, PAGES[k]);
        }
        paintStatus(g, shared, x, top + TABS_H, width);
        double y0 = top + TABS_H + statusHeight(width), slotW = min(SLOT_W, width / 4.0);
        g.setFont(ROW);
        if (page == SESSION) {
            paintSession(g, party.members.session(id), x + 4, y0, width - 8, slotW);
        } else if (shared == null) {
            g.setColor(FRIEND);
            wrap(g, PoiType.t("t25"), x + 4, y0 + 16, width - 8);
        } else if (page == 2) {
            double skillW = min(SKILL_W, width / 3.0), left = x + (width - 3 * skillW) / 2;
            for (int k = 0; k < HdMapPartyState.SKILLS; k++) {
                double sx = left + (k % 3) * skillW, sy = y0 + (k / 3) * SKILL_H;
                BufferedImage icon = party.skill(k);
                if (icon != null) {
                    g.drawImage(icon, (int) sx + 2, (int) sy + 3, 16, 16, null);
                }
                g.setColor(levelColor(shared.boosted[k], shared.levels[k]));
                text(g, level(shared.boosted[k], shared.levels[k]), sx + 22, sy + 16);
            }
        } else {
            double left = x + (width - (page == 0 ? 4 : 3) * slotW) / 2;
            for (int k = 0; page == 0 && k < HdMapPartyState.INVENTORY; k++) {
                slot(g, left + (k % 4) * slotW, y0 + (k / 4) * SLOT_H, slotW, shared.inventory[k], shared.quantities[k]);
            }
            for (int k = 0; page == 1 && k < WORN.length; k++) {
                if (WORN[k] != null) {
                    double sx = left + WORN[k][0] * slotW, sy = y0 + WORN[k][1] * SLOT_H;
                    g.setColor(SearchResults.LIT);
                    g.fill(new RoundRectangle2D.Double(sx + 2, sy + 1, slotW - 4, SLOT_H - 2, 8, 8));
                    slot(g, sx, sy, slotW, shared.equipment[k], 1);
                }
            }
        }
        return PartyOverlay.paintXp(g, party.xp(id), party::skill, x + width - 64, y0 + 60);
    }

    /** What a member got this session: the shared drops' worth and the best of them, experience by skill. */
    void paintSession(Graphics2D g, Session got, double x, double y, double w, double slotW) {
        g.setColor(FRIEND);
        if (got == null) {
            wrap(g, "Nothing gained yet this session.", x, y + 18, (int) w);
            return;
        }
        var metrics = g.getFontMetrics();
        g.setColor(Color.WHITE);
        text(g, "Drops " + PartyOverlay.coins(got.value), x, y + 18);
        String xp = "XP +" + AMOUNT.format(Arrays.stream(got.xp).sum());
        text(g, xp, x + w - metrics.stringWidth(xp), y + 18);
        // The best drops a row each, an item picture high; under them the skills most gained, three to a row.
        for (int k = 0; k < got.top.size(); k++) {
            var drop = got.top.get(k);
            slot(g, x - 4, y + 22 + k * ROW_H, slotW, drop.item, drop.quantity);
            g.setColor(drop.name != null ? PartyOverlay.UNTRADEABLE : WORLD);
            String worth = drop.name != null ? drop.name : PartyOverlay.coins(drop.value);
            text(g, fit(worth, metrics, (int) (w - slotW)), x + slotW, y + 46 + k * ROW_H);
        }
        int[] best = got.best(6);
        double cell = w / 3;
        for (int k = 0; k < best.length; k++) {
            BufferedImage icon = party.skill(best[k]);
            double sx = x + k % 3 * cell, sy = y + 32 + SESSION_DROPS * ROW_H + k / 3 * 20;
            if (icon != null) {
                g.drawImage(icon, (int) sx, (int) sy, 14, 14, null);
            }
            g.setColor(WORLD);
            long gained = got.xp[best[k]];
            text(g, "+" + (gained < 10_000 ? AMOUNT.format(gained) : PartyOverlay.coins(gained)), sx + 16, sy + 12);
        }
    }

    /** Hitpoints, prayer, run energy, special attack: in one row where they fit, else two. */
    void paintStatus(Graphics2D g, Gear shared, double x, double y, int width) {
        if (shared == null) {
            return;
        }
        int perRow = statusHeight(width) == STATUS_H ? 4 : 2;
        double gap = (width - perRow * Orbs.WIDTH) / (perRow - 1.0);
        int hp = Skill.HITPOINTS.ordinal(), prayer = Skill.PRAYER.ordinal();
        int[][] orbs = {{shared.boosted[hp], shared.levels[hp]}, {shared.boosted[prayer], shared.levels[prayer]},
            {shared.run, 100}, {shared.special, 100}};
        for (int k = 0; k < orbs.length; k++) {
            Orbs.paint(g, (int) (x + k % perRow * (Orbs.WIDTH + gap)), (int) y + 2 + k / perRow * STATUS_H,
                Orbs.Kind.values()[k], orbs[k][0], orbs[k][1], party::sprite);
        }
    }

    /** {@code text} in lines no wider than {@code width}, from the baseline at y down. */
    static void wrap(Graphics2D g, String text, double x, double y, int width) {
        var metrics = g.getFontMetrics();
        var line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (line.length() > 0 && metrics.stringWidth(line + " " + word) > width) {
                text(g, line.toString(), x, y);
                y += metrics.getHeight();
                line.setLength(0);
            }
            line.append(line.length() > 0 ? " " : "").append(word);
        }
        text(g, line.toString(), x, y);
    }

    void slot(Graphics2D g, double x, double y, double w, int id, int quantity) {
        BufferedImage image = id < 0 ? null : party.item(id, max(1, quantity));
        if (image != null) {
            g.drawImage(image, (int) (x + (w - image.getWidth()) / 2), (int) (y + (SLOT_H - image.getHeight()) / 2.0), null);
        }
    }

    /** A skill as shown, boosted over real when they differ. */
    static String level(int now, int real) {
        return now == real ? String.valueOf(real) : now + "/" + real;
    }

    static Color levelColor(int now, int real) {
        return now > real ? BOOSTED : now < real ? DRAINED : Color.WHITE;
    }
}
