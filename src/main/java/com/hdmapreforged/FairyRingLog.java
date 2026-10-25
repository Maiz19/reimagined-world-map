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
    /** The code scrolled to since the log opened, and the frames it is still held there. */
    String scrolledTo;
    int holding;
    /** Frames the scroll is held after opening (the game puts back its own while it fills the list), then the user's. */
    static final int HOLD_FRAMES = 30;

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
     * Every frame while the log is open, as Shortest Path does: the game draws its list again (on opening, searching,
     * sorting) with its own colours. The scroll is held on the code only for {@link #HOLD_FRAMES} after the log opened or
     * the code changed, as the game puts back the scroll it keeps itself; then the user scrolls it.
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
        Widget first = mark(contents == null ? null : contents.getDynamicChildren(), codes);
        if (first == null) {
            return;
        }
        if (!codes.get(0).equals(scrolledTo)) {
            scrolledTo = codes.get(0);
            holding = HOLD_FRAMES;
        }
        if (holding <= 0) {
            return;
        }
        holding--;
        int scroll = Math.max(0, Math.min(first.getRelativeY(), contents.getScrollHeight() - contents.getHeight()));
        // No client script for the scrollbar's thumb: reviewers flag running them.
        contents.setScrollY(scroll);
        contents.revalidateScroll();
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
