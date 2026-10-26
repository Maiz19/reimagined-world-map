package com.hdmapreforged;

import static com.hdmapreforged.PoiType.t;
import static com.hdmapreforged.Route.Outcome.*;
import static com.hdmapreforged.Tiles.*;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import lombok.*;
import lombok.experimental.*;
import lombok.extern.slf4j.*;

/**
 * When to search, so it never happens too often: one search at a time; when the player strays, automatic searches
 * with a doubling pause, at most {@link #MAX_AUTOMATIC} in a row (again once back on the way, or walked on toward the
 * target), none where a search proved the target out of reach (until a user action, or until the ways it may use
 * change) and none in instances other than the house (their positions are template copies). Called on one thread
 * (Swing); results come back through {@code callbacks}, also when a search fails.
 */
@Slf4j
@RequiredArgsConstructor
@Accessors(fluent = true)
final class RouteController {
    static final long FIRST_BACKOFF_MS = 3_000, MAX_BACKOFF_MS = 60_000;
    static final int MAX_AUTOMATIC = 6;
    /** Tiles from the route before the player counts as having left it: 10 away, as Shortest Path. */
    static final int STRAY = 9;

    final Executor searches, callbacks;
    final Pathfinder pathfinder;
    /** Builds a search for a target, or null when there is no start. */
    final IntFunction<RouteRequest> requests;
    final Consumer<RouteController> changed;
    final LongSupplier clock;

    @Getter
    int target = -1;
    @Getter
    Route route;
    /** Where the route starts, or -1: before a first teleport the player stands there, on none of its steps. */
    int from = -1;
    /** What the route's search was asked; whether it was an automatic search asked the same ways as the one before. */
    RouteRequest asked;
    /** Whether the route came from an automatic search with the same ways as the one before: only the player moved. */
    @Getter
    boolean onlyMoved;
    AtomicBoolean running;
    /** A user search asked for while another ran; started when that one ends. */
    boolean pendingUser;
    int automatic;
    long backoff = FIRST_BACKOFF_MS, nextAutomatic;
    /** Where the player was when an automatic search was last thought of, and the tick before; -1 none. */
    int searchedAt = -1, lastPlayer = -1;
    /** The last tick's move was a jump; a jump from a steady spot (a teleport) not yet searched from. */
    boolean jumped, teleported;
    /**
     * A search that tried everything without getting there, and what it was asked: from anywhere it reached, the
     * target stays out of reach while the same ways may be used.
     */
    Route unreachable;
    RouteRequest unreachableAsked;
    /** A message instead of a route, or null. */
    @Getter
    String status;
    /** A search could not start for want of a position, and when to try again. */
    boolean noStart;
    long nextStartTry;

    void setTarget(int target) {
        userAction();
        this.target = target;
        route = null;
        status = "Searching…";
        start(true);
        changed.accept(this);
    }

    /** A route planned already (a custom route's next part, from {@code from}): shown as found, without a search. */
    void show(int target, int from, Route planned) {
        userAction();
        cancelRunning();
        this.target = target;
        this.from = from;
        route = planned;
        status = null;
        changed.accept(this);
    }

    void clear() {
        userAction();
        target = -1;
        route = null;
        status = null;
        noStart = false;
        cancelRunning();
        changed.accept(this);
    }

    void cancelRunning() {
        if (running != null) {
            running.set(true);
            running = null;
        }
        pendingUser = false;
    }

    /** A setting that changes routes (or unlocks, bank) changed: search again once. */
    void settingsChanged() {
        userAction();
        if (target >= 0) {
            start(true);
        }
    }

    void userAction() {
        unreachable = null;
        unreachableAsked = null;
        asked = null;
        onlyMoved = false;
        searchedAt = -1;
        freshWaits();
    }

    /** Automatic searches may start at once again, and wait as for the first. */
    void freshWaits() {
        automatic = 0;
        backoff = FIRST_BACKOFF_MS;
        nextAutomatic = 0;
    }

    /**
     * The player went into or out of the house: the ways from here are others (its portal, box and fairy ring first), so
     * any search runs again from here at once, without the waits of a stray; only the route's own next jump from
     * where one was (its teleport into the house, its fairy ring out), landed where it lands, keeps the route.
     */
    void placeChanged(int player) {
        var next = route == null || running != null ? null
            : route.ahead(lastPlayer).steps.stream().filter(Route.Step::isJump).findFirst().orElse(null);
        if (target >= 0 && (next == null || player < 0 || !near(player, next.last(), STRAY))) {
            freshWaits();
            start(true);
        }
    }

    /** {@code player} packed or -1; starts a search when the player left the route. */
    void playerMoved(int player, boolean automaticAllowed) {
        int before = lastPlayer;
        lastPlayer = player;
        // Teleported (or up or down) from a steady spot: off the route there it is searched at once, also when a search
        // ran then; a position jumping about every tick keeps its waits.
        boolean jump = before >= 0 && player >= 0 && !near(player, before, STRAY);
        if (jump) {
            teleported = !jumped;
        }
        jumped = jump;
        if (target >= 0 && running == null && route == null && noStart && player >= 0 && automaticAllowed) {
            // Asked for where the position was unknown: retried now and then once it is known.
            long now = clock.getAsLong();
            if (now >= nextStartTry) {
                nextStartTry = now + FIRST_BACKOFF_MS;
                status = "Searching…";
                start(true);
                changed.accept(this);
            }
            return;
        }
        if (jump && teleported && running != null && target >= 0 && automaticAllowed && player >= 0) {
            // Teleported (into the house, say) while a search from the old spot runs: it is for where one no longer
            // is, so it is replaced at once by one from here (the house's own ways first).
            start(true);
            return;
        }
        if (target < 0 || running != null || !automaticAllowed || player < 0 || route == null) {
            return;
        }
        if (onRoute(player)) {
            // Back on the way: long trips get their automatic searches again.
            backoff = FIRST_BACKOFF_MS;
            automatic = 0;
            teleported = false;
            return;
        }
        long now = clock.getAsLong();
        if (teleported) {
            teleported = false;
            freshWaits();
        }
        if (searchedAt >= 0 && before >= 0 && near(player, before, 4) && !near(player, searchedAt, STRAY)
            && Pathfinder.folded(player, target) < Pathfinder.folded(searchedAt, target)) {
            // Walked on toward the target past where the last search began (its route came too late to be on): a new
            // stray, no wait. Walking elsewhere keeps the waits and the cap, as does jumping about (a glitching position).
            freshWaits();
        }
        if (automatic >= MAX_AUTOMATIC || now < nextAutomatic) {
            return;
        }
        searchedAt = player;
        nextAutomatic = now + backoff;
        var request = requests.apply(target);
        // A search that tried everything reached where this one would start, with the same ways.
        if (request != null && unreachable != null && unreachableAsked.sameWays(request)
            && unreachable.reached(pathfinder.startNode(request.start))) {
            // Out of reach from here with these ways (proved): looked at again after the wait, not searched.
            return;
        }
        automatic++;
        backoff = Math.min(MAX_BACKOFF_MS, backoff * 2);
        start(request, false);
    }

    static final int ARRIVED = 3;

    /** Within {@link #ARRIVED} tiles of the route's end (or the tile asked for), on the same floor. */
    boolean arrived(int player) {
        if (player < 0 || route == null || isSea(player)) {
            return false;
        }
        // A route that cannot get there ends where it gets stuck: no arrival.
        int end = route.outcome != FOUND ? -1 : route.end >= 0 ? route.end : target;
        return end >= 0 && Tiles.near(player, end, ARRIVED) || target >= 0 && Tiles.near(player, target, ARRIVED);
    }

    boolean onRoute(int player) {
        // With no steps: already there, or no start found, which a later position may fix. Its start: before a first
        // teleport.
        if (route.end >= 0 && near(player, route.end, STRAY) || from >= 0 && near(player, from, STRAY)) {
            return true;
        }
        for (var step : route.steps) {
            if (step.isJump()) {
                if (near(player, step.last(), STRAY) || step.first() != step.last() && near(player, step.first(), STRAY)) {
                    return true;
                }
                continue;
            }
            for (int point : step.points) {
                if (near(player, point, isSea(point) ? STRAY + CELL : STRAY)) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean near(int a, int b, int radius) {
        return Route.sameLevel(a, b) && distance(a, b) <= radius;
    }

    void start(boolean user) {
        if (running != null) {
            if (user) {
                // The newest user request wins: start it when the old one ends.
                running.set(true);
                pendingUser = true;
            }
            return;
        }
        start(requests.apply(target), user);
    }

    void start(RouteRequest request, boolean user) {
        noStart = request == null;
        if (request == null) {
            status = t("noPosition");
            changed.accept(this);
            return;
        }
        var cancel = new AtomicBoolean();
        running = cancel;
        int searched = target;
        try {
            searches.execute(() -> {
                Route result = null;
                try {
                    result = BankDetour.find(pathfinder, request, cancel::get, Integer.MAX_VALUE);
                } catch (Throwable e) {
                    log.warn("Route search to {} failed", format(searched), e);
                } finally {
                    Route done = result != null ? result : new Route(NONE, List.of(), 0, searched, -1,
                        false, false, 0);
                    callbacks.execute(() -> finished(cancel, searched, request, user, done));
                }
            });
        } catch (RejectedExecutionException e) {
            running = null;
        }
    }

    void finished(AtomicBoolean search, int searched, RouteRequest request, boolean user, Route result) {
        if (running == search) {
            running = null;
        }
        if (pendingUser && running == null) {
            pendingUser = false;
            if (target >= 0) {
                start(true);
                return;
            }
        }
        if (search.get() || searched != target || result.outcome == CANCELLED) {
            return;
        }
        onlyMoved = !user && asked != null && asked.sameWays(request);
        route = result;
        from = request.start;
        asked = request;
        if (result.exhausted && result.outcome != FOUND) {
            unreachable = result;
            unreachableAsked = request;
        }
        status = null;
        changed.accept(this);
    }
}
