package com.hdmapreforged;

import java.util.*;
import java.util.regex.*;
import lombok.*;
import net.runelite.api.*;
import net.runelite.api.events.*;
import net.runelite.api.gameval.*;
import net.runelite.api.widgets.Widget;
import net.runelite.client.eventbus.*;

/**
 * The route's next fairy ring in the ring's travel log, as Shortest Path shows its own: its code in green, scrolled to.
 * Only the log's look changes; nothing is dialled or clicked.
 */
@RequiredArgsConstructor
final class FairyRingLog {
    static final int GREEN = 0x00FF00;
    /** "Fairy ring: CKS", "House fairy ring: BKS", "Fairy ring: AIR, DLR, DJQ, then AJS". */
    static final Pattern RING = Pattern.compile("airy ring: ((?:[A-Z]{3}(?:, then |, )?)+)");
    static final Pattern CODE = Pattern.compile("[A-Z]{3}");

    final RouteFeature routes;
    boolean open;
    /** The code scrolled to since the log opened, the scroll set for it, and whether it is still held (not the user's). */
    String scrolledTo;
    int scrolledY;
    boolean holding;

    /** The codes of the route's first fairy ring step, in the order dialled; empty without one. */
    static List<String> codes(Route route) {
        for (var step : route == null ? List.<Route.Step>of() : route.steps) {
            Matcher ring = step.name == null ? null : RING.matcher(step.name);
            if (ring != null && ring.find()) {
                var codes = new ArrayList<String>();
                Matcher code = CODE.matcher(ring.group(1));
                while (code.find()) {
                    codes.add(code.group());
                }
                return codes;
            }
        }
        return List.of();
    }

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event) {
        if (event.getGroupId() == InterfaceID.FAIRYRINGS_LOG) {
            open = true;
            scrolledTo = null;
        }
    }

    @Subscribe
    public void onWidgetClosed(WidgetClosed event) {
        open &= event.getGroupId() != InterfaceID.FAIRYRINGS_LOG;
    }

    /**
     * Every frame while the log is open, as Shortest Path does: the game draws its list again (on opening, by its search
     * box's timer, on searching and sorting) with its own colours, and puts back the scroll it keeps itself (the top,
     * or where the user scrolled). So the scroll goes to the code again each time the list was drawn anew, until the user
     * scrolls; again for another code or when the log opens again.
     */
    @Subscribe
    public void onPostClientTick(PostClientTick event) {
        List<String> codes = open ? codes(routes.shown) : List.of();
        if (codes.isEmpty()) {
            return;
        }
        Client client = routes.client;
        Widget contents = client.getWidget(InterfaceID.FairyringsLog.CONTENTS);
        // Favourites' codes in green too; the list scrolls to the first code's row.
        Widget faves = client.getWidget(InterfaceID.FairyringsLog.FAVES);
        mark(faves == null ? null : faves.getStaticChildren(), codes);
        Widget[] rows = contents == null ? null : contents.getDynamicChildren();
        // None green: drawn anew since the last frame, with the game's scroll.
        boolean drawn = rows == null || Arrays.stream(rows).noneMatch(row -> row != null && row.getTextColor() == GREEN);
        Widget first = mark(rows, codes);
        if (first == null) {
            return;
        }
        if (!codes.get(0).equals(scrolledTo)) {
            scrolledTo = codes.get(0);
            holding = drawn = true;
        } else if (!drawn && contents.getScrollY() != scrolledY) {
            holding = false; // the user's own scroll
        }
        if (!holding || !drawn) {
            return;
        }
        // No client script for the scrollbar's thumb: reviewers flag running them.
        contents.setScrollY(Math.max(0, Math.min(first.getRelativeY(), contents.getScrollHeight() - contents.getHeight())));
        contents.revalidateScroll();
        // As the client keeps it: a scroll it changed after is the user's.
        scrolledY = contents.getScrollY();
    }

    /** Colours the codes among these widgets green; the first code's widget, or null. */
    static Widget mark(Widget[] widgets, List<String> codes) {
        Widget first = null;
        for (Widget widget : widgets == null ? new Widget[0] : widgets) {
            // The game writes the codes spaced out: "B K R".
            int at = widget == null ? -1 : codes.indexOf(String.valueOf(widget.getText()).replace(" ", ""));
            if (at >= 0) {
                widget.setTextColor(GREEN);
                first = at == 0 ? widget : first;
            }
        }
        return first;
    }
}
