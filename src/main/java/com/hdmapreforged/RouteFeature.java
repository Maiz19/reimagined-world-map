package com.hdmapreforged;

import static net.runelite.client.ui.ColorScheme.*;
import static com.hdmapreforged.PoiType.t;
import static java.awt.RenderingHints.*;
import static java.util.Collections.*;
import static java.util.stream.Collectors.*;
import static com.hdmapreforged.RouteSource.*;
import static com.hdmapreforged.Tour.*;
import static com.hdmapreforged.BankDetour.*;
import static com.hdmapreforged.Route.Step.Kind.*;
import static com.hdmapreforged.SpBridge.*;
import static com.hdmapreforged.Tiles.*;
import static com.hdmapreforged.PlayerMarker.circle;
import static com.hdmapreforged.RoutePanels.*;
import static com.hdmapreforged.RouteFeature.RouteText.*;
import static com.hdmapreforged.SearchResults.*;
import static com.hdmapreforged.MapConfig.*;
import static com.hdmapreforged.Route.Outcome.*;
import static java.awt.BasicStroke.*;
import static java.util.Locale.ROOT;
import static javax.swing.SwingUtilities.invokeLater;
import static net.runelite.api.gameval.VarbitID.*;
import static java.lang.Math.*;
import static java.lang.System.currentTimeMillis;
import static net.runelite.api.Perspective.*;
import static net.runelite.api.CollisionDataFlag.*;

import com.hdmapreforged.Route.*;
import com.hdmapreforged.Route.Step.*;
import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.regex.*;
import java.util.stream.*;
import javax.inject.*;
import javax.swing.*;
import javax.swing.Timer;
import lombok.*;
import lombok.extern.slf4j.*;
import net.runelite.api.*;
import net.runelite.api.Point;
import net.runelite.api.coords.*;
import net.runelite.api.events.*;
import net.runelite.api.gameval.*;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.SpriteID;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.client.callback.*;
import net.runelite.client.chat.*;
import net.runelite.client.config.*;
import net.runelite.client.eventbus.*;
import net.runelite.client.events.*;
import net.runelite.client.plugins.*;
import net.runelite.client.ui.*;
import net.runelite.client.ui.overlay.*;
import net.runelite.client.ui.overlay.worldmap.*;

/**
 * "Path to here": our own route search; only shows a way, never walks, clicks or sends anything.
 * Threads: player state on the client thread; searches on their own thread, extra work on the route extras thread;
 * the controller and map drawing on Swing.
 */
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public final class RouteFeature implements TourPanel.Actions {
    /** Per account: the house's features ("box:ornate,glory,portal:Varrock") and its plan. */
    static final String HOUSE_KEY = "routeHouse";
    static final String PLAN_KEY = "housePlan";
    /** VarbitID's RUNE_POUCH_TYPE_1 to 6 and RUNE_POUCH_QUANTITY_1 to 6 (RouteItemsTest checks them). */
    static final int[] POUCH_TYPES = {29, 1622, 1623, 14285, 15373, 15374},
        POUCH_AMOUNTS = {1624, 1625, 1626, 14286, 15375, 15376};
    static final Set<Integer> RUNE_POUCHES = Set.of(12791, 24416, 27281, 27509);
    static final int STATE_TICKS = 3, QUEST_TICKS = 100;

    static final Color WALK = new Color(255, 233, 28), JUMP = new Color(190, 120, 255);
    static final Set<String> LOOK_KEYS = Set.of("routeColor", "routeJumpColor", "routeTileFill",
        "routeTileBorder", "routeTileWidth", "routeMinimap", "routeItems", "routeClearOnArrival",
        "routeFollowPlugins", "routeStyle", "routeLineWidth");

    static final Color[] LEG_COLORS = {
        new Color(90, 220, 130), new Color(255, 130, 200), new Color(255, 160, 70), new Color(120, 230, 255),
        new Color(200, 255, 110), new Color(255, 255, 255), new Color(180, 150, 255)};

    static final Color CANNOT = new Color(255, 112, 112), SHADOW = new Color(0, 0, 0, 130);

    Color walk() {
        return opaque(config.routeColor(), WALK);
    }

    Color jump() {
        return opaque(config.routeJumpColor(), JUMP);
    }

    static Color opaque(Color c, Color fallback) {
        return c == null ? fallback : new Color(c.getRed(), c.getGreen(), c.getBlue());
    }

    static WorldPoint worldPoint(int node) {
        return new WorldPoint(x(node), y(node), z(node));
    }

    static int pack(WorldPoint p) {
        return Tiles.pack(p.getX(), p.getY(), p.getPlane());
    }

    static Color alpha(Color c, float alpha) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), max(0, min(255, round(alpha))));
    }

    /** A route tile for HD World Markers; {@code alpha}: how visible (fading); null where it cannot be drawn. */
    MarkersBridge.Tile hdTile(int point, float alpha, Color walk) {
        var at = markerPoint(point);
        return at == null ? null : new MarkersBridge.Tile(at, alpha(walk, tileBorder * alpha),
            alpha(walk, tileFill * alpha), tileBorder == 0 ? 0 : tileWidth, null);
    }

    /**
     * Where HD World Markers draws a route tile. A tile of one's house plan is sent as the one instance tile of the
     * scene as loaded now (WorldPoint.fromLocal): a template point would land in every room built alike (the ring's
     * garden and the tree's). Null for a plan tile outside one's own house. Client thread.
     */
    WorldPoint markerPoint(int point) {
        var view = client.getTopLevelWorldView();
        if (!HousePlan.contains(point)) {
            // In an instance copying a place: the template's tile, which HD World Markers finds in the instance.
            var template = view != null && view.isInstance()
                ? InstanceCopies.template(x(point), y(point), z(point)) : null;
            return template != null ? new WorldPoint(template[0], template[1], template[2]) : worldPoint(point);
        }
        var local = view == null || !house.own() ? null : yourHouse.local(view, point);
        return local == null ? null : WorldPoint.fromLocal(view, local.getX(), local.getY(), z(point));
    }

    /** The tiles' look, read once for each message. Client thread. */
    int tileBorder, tileFill, tileWidth;
    static final Color END = new Color(255, 90, 70);
    /** A destination on the sea, where one's boat stops rather than one stands. */
    static final Color SEA_END = new Color(70, 160, 255);

    final Client client;
    final MapConfig config;
    final ConfigManager configManager;
    /** The planted spirit trees grown for this account; read near their patches. */
    final Planted planted = new Planted();
    final OverlayManager overlayManager;
    final net.runelite.client.game.ItemManager itemManager;
    final EventBus eventBus;
    final ChatMessageManager chatMessageManager;
    final HouseTracker house = new HouseTracker();
    /** Where the player came from into the Rune essence mine or the Shipyard, whose exits lead back there. */
    final ReturnTracker returns = new ReturnTracker();
    final RouteHouse yourHouse = new RouteHouse(this, house);
    final FairyRingLog fairyRingLog = new FairyRingLog(this);
    // Nothing on the game's own world map: projecting a long route there every frame is too heavy.
    final HdMinimap hdMinimap = new HdMinimap();
    final List<Overlay> overlays = List.of(new GameOverlay(), new MinimapOverlay(), new ItemsOverlay(), hdMinimap);
    /** The Clue Scroll plugin's spots for the clue now and other plugins' world map icons, as the game's map shows them. */
    volatile List<WorldMapPoint> marks = List.of();
    static final Set<String> CORE_POINTS = Set.of("net.runelite.client.plugins.worldmap.MapPoint",
        "net.runelite.client.plugins.party.PartyWorldMapPoint");
    static final String CLUE_POINT = "net.runelite.client.plugins.cluescrolls.ClueScrollWorldMapPoint";
    final WorldMapPointManager worldMapPoints;

    volatile ExecutorService searches, background;
    volatile MapScreen[] screens = new MapScreen[0];
    volatile RouteController controller;
    volatile RouteSource source;
    volatile Pathfinder walker;
    void repaint() {
        forScreens(screen -> screen.view.repaint());
    }

    void forScreens(Consumer<MapScreen> action) {
        List.of(screens).forEach(action);
    }
    volatile int[] shipwrights = new int[0];

    void setIcons(List<MapData.Icon> icons) {
        this.icons = icons;
        shipwrights = tiles(icons, "Shipwright");
        // The game's icons on the water (the Barracuda Trials' starts), which are sailed to.
        seaSpots = tiles(icons, "Barracuda Trial");
    }

    /** Where the game's icons of a key on its map stand, on the ground floor. */
    static int[] tiles(List<MapData.Icon> icons, String kind) {
        return icons.stream().filter(icon -> kind.equalsIgnoreCase(icon.kind) && icon.location.getPlane() == 0)
            .mapToInt(icon -> pack(icon.location)).distinct().toArray();
    }
    /** The map version's icons, for the HD minimap. */
    volatile List<MapData.Icon> icons = List.of();
    /** The game's icons on the water, as tiles. */
    volatile int[] seaSpots = new int[0];
    volatile PlayerState state = PlayerState.UNKNOWN;
    /** A tile, a sea block, -1 in the house, -2 unknown. Written on the client thread. */
    volatile int start = -2;
    /** The route ahead now, or null; {@link #drawn}: of it what the game shows (up to its next teleport). */
    volatile Route shown, drawn;

    void show(Route route) {
        // The same cut while the route stays: HD World Markers is sent its tiles again only for another.
        drawn = route == null ? null : route == shown && drawn != null ? drawn : route.untilTeleport();
        shown = route;
    }
    boolean bringUp;
    Boolean pandemonium;
    int ticks, lastQuest = -QUEST_TICKS;

    final PluginManager pluginManager;
    volatile Consumer<IntFunction<JComponent>> sidebar = section -> { };
    volatile Consumer<WorldPoint> sidebarFocus = point -> { };
    volatile Runnable sidebarTours = () -> { };
    final ClientThread clientThread;
    /** The target handed to Shortest Path, or null. */
    volatile WorldPoint handedOver, lastLocation;
    /** Whether the current route was asked for by another plugin. */
    volatile boolean fromPlugin;

    boolean spPlanner() {
        return config.routePlanner() == RoutePlanner.SHORTEST_PATH;
    }

    /** Path style Lines, or Lines with arrows: the walks are drawn as lines through the tiles. */
    boolean lines() {
        return config.routeStyle() != RouteStyle.TILES;
    }

    boolean arrows() {
        return config.routeStyle() == RouteStyle.ARROWS;
    }

    boolean shortestPathOn() {
        return shortestPathName() != null;
    }

    /** The plugin on that answers Shortest Path's messages (Shortest Path, or GPS), or null. */
    String shortestPathName() {
        return pluginOn(PLUGIN_NAMES);
    }

    /** The first of these plugins that is on, or null. */
    String pluginOn(List<String> names) {
        return pluginManager.getPlugins().stream()
            .filter(p -> names.contains(p.getName()) && pluginManager.isPluginEnabled(p)).map(Plugin::getName).findFirst()
            .orElse(null);
    }

    /** Client thread. */
    boolean warned;

    /** Both planners on is not meant to be: said once after a start, and again when either is switched on. */
    void warnBoth(boolean again) {
        clientThread.invokeLater(() -> {
            warned &= !again;
            var other = warned || client.getGameState() != GameState.LOGGED_IN || spPlanner() ? null
                : shortestPathName();
            if (other == null) {
                return;
            }
            warned = true;
            // RuneLite gives every plugin each message: neither can take another plugin's directions for itself.
            chatMessageManager.queue(QueuedMessage.builder().type(ChatMessageType.CONSOLE)
                .value(t("warnBoth", other, other)).build());
        });
    }

    @Subscribe
    public void onPluginChanged(PluginChanged event) {
        if (event.isLoaded() && PLUGIN_NAMES.contains(event.getPlugin().getName())) {
            warnBoth(true);
        }
    }

    /** Whether HD World Markers draws the ground tiles now (checked every 10 ticks). */
    volatile boolean hdTiles;
    boolean hdHome;
    Route[] sentLegs;
    int lastHdCheck = Integer.MIN_VALUE / 2;
    /** Volatile: a change of the route's look clears it on Swing, the client thread sends and sets it. */
    volatile Route sentAhead;
    /** The fades as last sent ({@link #fadeSteps}). */
    long sentFade = 1;
    /** When fading alone last sent the tiles. */
    long fadeSent;
    /**
     * How far from the player route tiles go to HD World Markers: as far as our own overlay draws, beyond any
     * scene, also an HD renderer's expanded one; it draws what lies in the scene, the nearest 1000 first.
     */
    static final int MARKER_REACH = Constants.EXTENDED_SCENE_SIZE;
    /** The scene's base when tiles were last sent (one's house moves with it). Client thread. */
    long sentBase;
    /** Whether tiles were sent since the last clear. */
    boolean sentTiles;
    /** Whether the walks went as lines last time (so lines are taken away when tiles come back). */
    boolean sentLines;
    int sentFrom = -1;

    /**
     * Shift + right-click on the ground, or on the sea from one's boat. RuneLite entries; nothing is sent to the game.
     */
    @Subscribe
    public void onMenuOpened(MenuOpened event) {
        if (spPlanner() || !client.isKeyPressed(KeyCode.KC_SHIFT)) {
            return;
        }
        var view = client.getTopLevelWorldView();
        // On one's boat the sea's option is Set heading, not Walk here.
        Tile tile = view == null || Arrays.stream(event.getMenuEntries())
            .noneMatch(e -> e.getType() == MenuAction.WALK || e.getType() == MenuAction.SET_HEADING)
            ? null : view.getSelectedSceneTile();
        if (tile == null) {
            return;
        }
        var at = tile.getLocalLocation();
        var clicked = yourHouse.plan != null && house.own() ? worldPoint(yourHouse.planNode(at, view.getPlane()))
            : WorldPoint.fromLocalInstance(client, at);
        if (clicked == null) {
            return;
        }
        // In an instance copying a place on the map (the Watchtower's top floor): that place's tile, as the player's.
        var copied = InstanceCopies.place(clicked.getX(), clicked.getY(), clicked.getPlane());
        var point = copied == null ? clicked : new WorldPoint(copied[0], copied[1], copied[2]);
        if (routing()) {
            menuEntry("Clear route", this::clearRoute);
        }
        menuEntry("Route here", () -> routeTo(point));
    }

    void menuEntry(String option, Runnable action) {
        client.getMenu().createMenuEntry(1).setOption(option).setTarget("").setType(MenuAction.RUNELITE)
            .onClick(e -> invokeLater(action));
    }

    /** HD World Markers does not animate what it was sent: it gets fading tiles again at each of this many steps. */
    static final int FADE_STEPS = 4;

    @Subscribe
    public void onClientTick(ClientTick event) {
        // Also a new cut of the route (a teleport taken: the tick's send ran before it), at the next frame.
        if (hdTiles && (drawn != sentAhead || fadeSteps(fading, appearing, currentTimeMillis()) != sentFade)) {
            updateHdTiles(sentFrom);
        }
    }

    /** Which step each fade is at (one done counts as none): a new value means a visible change. */
    static long fadeSteps(Map<Integer, Long> fading, Map<Integer, Long> appearing, long now) {
        long steps = 1;
        for (long since : fading.values()) {
            long step = (now - since) * FADE_STEPS / FADE_MS;
            steps = step < FADE_STEPS ? steps * 31 + step : steps;
        }
        for (long since : appearing.values()) {
            long step = (now - since) * FADE_STEPS / FADE_IN_MS;
            steps = step < FADE_STEPS ? steps * 31 + step + FADE_STEPS : steps;
        }
        return steps;
    }

    /** HD World Markers dropped every plugin's tiles (logout, world hop, lost connection, profile change): send again. */
    void tileMarkersCleared() {
        clientThread.invokeLater(this::unsent);
    }

    /** Client thread: nothing counts as sent. */
    void unsent() {
        sentAhead = null;
        sentLegs = null;
        sentFrom = -1;
        sentTiles = sentLines = false;
        sentHead = null;
        lastHdCheck = Integer.MIN_VALUE / 2;
    }

    /** The text over the head HD World Markers was sent, with its colour; null none. Client thread. */
    String sentHead;

    /** Client thread: the text over the player's head goes to HD World Markers when it changes; null takes it away. */
    void head(String text, Color color, Player player) {
        String now = text == null ? null : text + color;
        if (!Objects.equals(now, sentHead)) {
            sentHead = now;
            eventBus.post(MarkersBridge.head(text, color, player));
        }
    }

    void updateHdTiles(int player) {
        boolean home = house.own();
        if (ticks - lastHdCheck >= 10 || home != hdHome) {
            hdHome = home;
            lastHdCheck = ticks;
            // Also in one's house: its tiles go as the scene's own instance tiles (markerPoint).
            boolean on = pluginOn(List.of(MarkersBridge.PLUGIN_NAME)) != null;
            hdTexts = on && drawsTexts();
            if (!on && hdTiles) {
                clearHd();
                sentAhead = null;
                sentTiles = sentLines = false;
            }
            hdTiles = on;
        }
        if (!hdTiles) {
            return;
        }
        Route route = drawn;
        // Moved far, or the position became known or unknown; not every tick while it stays unknown (the house).
        boolean moved = player != sentFrom && (player < 0 || sentFrom < 0 || distance(player, sentFrom) > 16);
        // The scene loaded again (a teleport in, building mode): the house's instance tiles lie elsewhere now.
        var scene = client.getTopLevelWorldView();
        long base = scene == null ? 0 : (long) scene.getBaseX() << 32 | scene.getBaseY() & 0xffffffffL;
        moved |= home && base != sentBase;
        sentBase = base;
        Running running = tour;
        var legs = running == null ? null : running.legs;
        long now = currentTimeMillis(), fade = fadeSteps(fading, appearing, now);
        // Fading alone sends again at most once a fade step (each tile's own steps would send several times a second,
        // the whole list each time).
        if (route == sentAhead && legs == sentLegs && !moved
            && (fade == sentFade || now - fadeSent < FADE_MS / FADE_STEPS)) {
            return;
        }
        fadeSent = now;
        sentFade = fade;
        sentAhead = route;
        sentLegs = legs;
        sentFrom = player;
        var later = laterLegs();
        if (route == null && fading.isEmpty() && later.isEmpty()) {
            if (sentTiles) {
                eventBus.post(MarkersBridge.clear());
            }
            sentTiles = sentLines = false;
            return;
        }
        tileBorder = config.routeTileBorder();
        tileFill = config.routeTileFill();
        tileWidth = (int) max(1, round(config.routeTileWidth()));
        // As a line: the walks go as lines, the tiles keep only the teleports' named tiles.
        boolean asLines = lines();
        var lines = new ArrayList<List<WorldPoint>>();
        var lineColors = new ArrayList<Color>();
        var lineArrows = new ArrayList<Integer>();
        int every = arrows() ? ARROW_EVERY : 0;
        if (asLines) {
            eachLine(route, (points, color) -> {
                var line = new ArrayList<WorldPoint>();
                // One past the end closes the last line.
                for (int i = 0; i <= points.length; i++) {
                    var at = i < points.length && reach(points[i], player) ? markerPoint(points[i]) : null;
                    // Out of reach or not drawable: the line breaks there (HD World Markers joins only neighbours).
                    if (at != null) {
                        line.add(at);
                    } else if (!line.isEmpty()) {
                        lines.add(line);
                        lineColors.add(color);
                        lineArrows.add(every);
                        line = new ArrayList<>();
                    }
                }
            });
            // The pieces fading behind the player: an arrow passed keeps its own (one on its first tile).
            Color walked = current();
            eachFade(route, now, (piece, alpha) -> {
                var a = markerPoint(piece[0]);
                var b = markerPoint(piece[1]);
                if (a != null && b != null) {
                    lines.add(List.of(a, b));
                    lineColors.add(alpha(walked, walked.getAlpha() * alpha));
                    lineArrows.add(piece[2]);
                }
            });
        }
        if (asLines || sentLines) {
            eventBus.post(MarkersBridge.lines(lines, lineColors, lineArrows, config.routeLineWidth()));
            sentLines = asLines;
        }
        // The teleports' named tiles first: HD World Markers takes 1000.
        var tiles = new ArrayList<MarkersBridge.Tile>();
        int blocked = blockedFrom(route);
        var steps = route == null ? List.<Step>of() : route.steps;
        for (int index = 0; index < steps.size(); index++) {
            Step step = steps.get(index);
            int at = namedAt(step);
            var marked = onItsTile(step) && reach(at, player) ? markerPoint(at) : null;
            if (marked != null) {
                tiles.add(new MarkersBridge.Tile(marked, blocked >= 0 && index >= blocked ? CANNOT : jump(),
                    said(whole(route), step, false)));
            }
        }
        if (!asLines) {
            eachTile(route, player, MARKER_REACH, (point, color, strength) -> tiles.add(hdTile(point, strength, color)));
        }
        tiles.removeIf(Objects::isNull);
        eventBus.post(MarkersBridge.tiles(tiles));
        sentTiles = true;
    }

    /** Takes everything off HD World Markers. Client thread. */
    void clearHd() {
        head(null, null, null);
        eventBus.post(MarkersBridge.clear());
    }

    static boolean reach(int point, int player) {
        return player < 0 || distance(point, player) <= MARKER_REACH;
    }

    /** The last message we sent Shortest Path, not taken for another plugin's. */
    volatile PluginMessage sent;

    /**
     * Directions other plugins (Quest Helper, clues) send to Shortest Path: its route is drawn whichever planner is
     * chosen, and ours plans them too (setting). We only ask it to tell its transports, after it took the message:
     * a config in it replaces its overrides.
     */
    @Subscribe(priority = -1)
    public void onPluginMessage(PluginMessage message) {
        // Read once: a Clear on Swing may come between.
        var over = handedOver;
        if (is(message, "transports") && over != null) {
            spJumps = SpBridge.jumps(message);
            walkShortestPath(spJumps, over);
            invokeLater(() -> showSp(false));
            return;
        }
        boolean path = is(message, "path");
        List<WorldPoint> targets = path ? SpBridge.targets(message) : List.of();
        var target = SpBridge.nearest(targets, lastLocation);
        if (message == sent || (path ? target == null : !is(message, "clear"))) {
            return;
        }
        var other = shortestPathName();
        directions = path ? message : null;
        if (target == null && handedOver != null) {
            forgetHandOver();
            invokeLater(() -> showSp(false));
        } else if (target != null && other != null) {
            handOver(target, SpBridge.path(null, SpBridge.config(message)), false);
        }
        if (spPlanner() || !config.routeFollowPlugins()) {
            return;
        }
        var wilderness = path ? noWild(message) : null;
        invokeLater(() -> follow(targets, wilderness));
    }

    /** Another plugin's last directions (a path message), kept for a change of planner; null after a clear. */
    volatile PluginMessage directions;

    /**
     * Swing: our planner plans another plugin's directions; of several targets (a clue's possible spots) to the one
     * reached soonest, as Shortest Path plans. No targets: their route, if shown, is cleared.
     */
    void follow(List<WorldPoint> targets, Boolean wilderness) {
        var c = controller;
        if (c != null && fromPlugin && c.target() >= 0 && targets.equals(followed)
            && Objects.equals(wilderness, pluginWilderness)) {
            // The same directions again (a plugin sends them as it goes): the route stands; asked again they would
            // count as a new ask, its waits and proof dropped and searched anew.
            return;
        }
        followed = List.copyOf(targets);
        if (tour != null) {
            // A running custom route goes on; the directions stay kept for after it.
            return;
        }
        int asked = pluginAsked.incrementAndGet();
        if (c != null && !targets.isEmpty()) {
            fastest(targets, true, () -> pluginAsked.get() != asked || controller != c, wilderness, best -> {
                if (tour != null) {
                    // A custom route started while this was looked for: it goes on.
                    return;
                }
                int to = node(best);
                // A new place shows its steps on the open map, as one asked for here does.
                bringUp = c.target() != to;
                fromPlugin = true;
                pluginWilderness = wilderness;
                routeAvoid.clear();
                c.setTarget(to);
            });
        } else if (c != null && fromPlugin) {
            fromPlugin = false;
            c.clear();
        }
    }

    /**
     * Swing: after a change of planner, another plugin's last directions go to the planner now chosen (the other
     * plugin sends them once, and the change cleared the route of the planner before).
     */
    void followAgain() {
        var last = directions;
        List<WorldPoint> targets = last == null ? List.of() : SpBridge.targets(last);
        if (targets.isEmpty()) {
            return;
        }
        if (spPlanner()) {
            if (shortestPathOn()) {
                handOver(SpBridge.nearest(targets, lastLocation), SpBridge.again(last), false);
            }
        } else if (config.routeFollowPlugins()) {
            follow(targets, noWild(last));
        }
    }

    /** The places of the plugin's directions followed last. */
    volatile List<WorldPoint> followed = List.of();
    /** Which plugin's directions are current; an older look for their fastest target stops. */
    final AtomicInteger pluginAsked = new AtomicInteger();
    /** The noWild the plugin's directions gave, used while the route is theirs ({@link #fromPlugin}); null none. */
    volatile Boolean pluginWilderness;

    /** As the planner, Shortest Path's route has the card; else it is only drawn, under ours. */
    void showSp(boolean up) {
        if (spPlanner()) {
            showHandedOver(handedOver == null ? null : this::handedOverPanel, up);
        } else {
            repaint();
        }
    }

    volatile List<Jump> spJumps = List.of();
    /** Its walks, looked for here on foot between the points it told (it tells no tiles); per its message. */
    volatile List<int[]> spWalks = List.of();
    final AtomicInteger spAsked = new AtomicInteger();

    /**
     * From the player to its first transport, between its transports, from the last to its target: walking only (no
     * shortcut or transport of ours), as its own path walks between the transports it takes.
     */
    void walkShortestPath(List<Jump> jumps, WorldPoint target) {
        int asked = spAsked.incrementAndGet();
        var from = lastLocation;
        Pathfinder pf = walker;
        var work = background;
        var map = source;
        if (from == null || pf == null || work == null || map == null) {
            return;
        }
        // From where our planner has the player: in one's house, on its plan. Its route's stretches between its
        // transports, each {from, to}. Across a transport with an end off the map (its stand-in for one's house, a tile
        // of an instance) nothing is walked: Shortest Path draws that part itself.
        int at = start >= 0 ? start : pack(from);
        var legs = new ArrayList<int[]>();
        boolean leftOut = false;
        for (var j : jumps) {
            int origin = place(map, j.from), landing = place(map, j.to);
            if (origin < 0 || landing < 0) {
                leftOut = true;
                continue;
            }
            if (!leftOut) {
                legs.add(new int[]{at, origin});
            }
            at = landing;
            leftOut = false;
        }
        if (!leftOut) {
            legs.add(new int[]{at, pack(target)});
        }
        inBackground(work, () -> {
            var walks = new ArrayList<int[]>();
            for (var leg : legs) {
                if (asked != spAsked.get()) {
                    break;
                }
                try {
                    var way = pf.find(new RouteRequest(leg[0], leg[1], List.of(), List.of(), 1, true,
                        RouteRequest.DEFAULT_NODE_LIMIT), () -> asked != spAsked.get());
                    if (way.outcome == FOUND) {
                        way.steps.stream().filter(s -> s.kind == Kind.WALK).forEach(s -> walks.add(s.points));
                    }
                } catch (RuntimeException e) {
                }
            }
            return walks;
        }, walks -> {
            if (asked == spAsked.get()) {
                spWalks = walks;
                repaint();
            }
        });
    }

    /**
     * Runs {@code job} on {@code pool}; its result goes to {@code back} on Swing, null when it threw (logged). False
     * when the pool takes no more work.
     */
    <T> boolean inBackground(ExecutorService pool, Callable<T> job, Consumer<T> back) {
        return TileCache.execute(pool, () -> {
            T result = null;
            try {
                result = job.call();
            } catch (Throwable e) {
                log.warn("A route job failed", e);
            }
            var made = result;
            invokeLater(() -> back.accept(made));
        });
    }

    /**
     * Where a point of its route is on the map's own ground, as our planner places it (a spot on an object or a wall
     * moves to the nearest open tile); -1 for a house plan or a tile of an instance, which the map has no ground for.
     */
    static int place(RouteSource map, WorldPoint point) {
        return HousePlan.contains(point.getX(), point.getY()) ? -1 : map.snap(point);
    }

    void paintHandedOver(Graphics2D g, MapView p) {
        var target = handedOver;
        if (target == null) {
            return;
        }
        g.setRenderingHint(KEY_ANTIALIASING, VALUE_ANTIALIAS_ON);
        float[] jump = {9f, 6f};
        // Beside our route (both planners on): white and dashed, to tell the two apart.
        boolean beside = !spPlanner();
        Color walked = beside ? Color.WHITE : PlayerMarker.YELLOW;
        // Its teleports and transports as it told them, and the walks between them as found here on foot.
        for (var walk : spWalks) {
            shadowed(g, path(p, walk), 2.5f, beside ? new float[]{6f, 5f} : null, walked);
        }
        for (var j : spJumps) {
            if (p.shows(j.from) && p.shows(j.to)) {
                shadowed(g, new Line2D.Double(x(p, pack(j.from)), y(p, pack(j.from)), x(p, pack(j.to)),
                    y(p, pack(j.to))), 2.5f, jump, beside ? Color.WHITE : new Color(180, 120, 255));
            }
        }
        if (p.shows(target)) {
            double x = x(p, pack(target)), y = y(p, pack(target));
            g.setColor(new Color(0, 0, 0, 160));
            g.fill(circle(x, y, 7));
            g.setColor(walked);
            g.fill(circle(x, y, 5));
        }
    }

    /** Drawn over a dark line two pixels wider. */
    static void shadowed(Graphics2D g, Shape shape, float width, float[] dash, Color color) {
        g.setStroke(new BasicStroke(width + 2f, CAP_ROUND, JOIN_ROUND, 10f, dash, 0f));
        g.setColor(SHADOW);
        g.draw(shape);
        g.setStroke(new BasicStroke(width, CAP_ROUND, JOIN_ROUND, 10f, dash, 0f));
        g.setColor(color);
        g.draw(shape);
    }

    JComponent handedOverPanel(int width) {
        JPanel panel = bordered();
        var target = handedOver;
        if (target == null) {
            return panel;
        }
        Running running = tour;
        if (running != null) {
            panel.add(tourHeader(running, width));
        }
        var name = shortestPathName();
        boolean on = name != null;
        panel.add(label("Route by " + (on ? name : "Shortest Path"), width, Font.BOLD, Color.WHITE));
        panel.add(label(on ? t("spPlanned", name) : t("spOff"), width, on ? LIGHT_GRAY_COLOR : NOTE));
        int number = 1;
        for (var j : spJumps) {
            panel.add(label(number++ + ". " + j.name, width, new Color(200, 170, 255)));
        }
        panel.add(button("Clear path", this::handOverClear));
        return panel;
    }

    /** Any thread: Shortest Path is sent {@code ask}, and its route to {@code target} is drawn. */
    void handOver(WorldPoint target, PluginMessage ask, boolean up) {
        forgetHandOver();
        handedOver = target;
        // Shortest Path reads the player's position when a message arrives: client thread.
        clientThread.invokeLater(() -> eventBus.post(sent = ask));
        invokeLater(() -> showSp(up));
    }

    /** Shortest Path plans it as the planner (true), or also when both are on: drawn under ours, to compare. */
    boolean toSp(WorldPoint target) {
        boolean planner = spPlanner();
        if (planner || shortestPathOn()) {
            handOver(target, SpBridge.path(target, Map.of()), true);
        }
        return planner;
    }

    void showHandedOver(IntFunction<JComponent> panel, boolean up) {
        forScreens(screen -> screen.showRoute(panel, up)); // repaints
        sidebar.accept(panel);
    }

    void handOverClear() {
        forgetHandOver();
        clientThread.invokeLater(() -> eventBus.post(sent = clear()));
        showSp(false);
    }

    void forgetHandOver() {
        handedOver = null;
        spJumps = List.of();
        spWalks = List.of();
        spAsked.incrementAndGet();
    }

    void start(MapScreen... on) {
        int generation = loads.incrementAndGet();
        screens = on;
        // At the lowest priority a busy client starved searches for seconds.
        searches = Executors.newSingleThreadExecutor(HdMapReforgedPlugin.threads("Reimagined World Map route",
            Thread.NORM_PRIORITY - 1));
        background = Executors.newSingleThreadExecutor(HdMapReforgedPlugin.threads("Reimagined World Map route extras",
            Thread.MIN_PRIORITY));
        for (var screen : on) {
            screen.view.addOverlay(this::paintMap);
            // The destination's arrow.
            screen.view.addWidget((g, w, h, bottom, clicks) -> paintDestination(screen.view, g, clicks));
            screen.view.menus.add(this::contribute);
            screen.card.setRouter(this::routeTo);
            screen.view.router = this::routeTo;
            screen.setStopAdder(this::addStop);
            // Of these places the map shows the one the planner gets to soonest (with the player's teleports and
            // transport), without starting a route; the nearest as the crow flies when no route can be planned now. The
            // maps show the place, as a click on it in the list does.
            screen.npcs.setFastest(places -> {
                int asked = fastestAsked.incrementAndGet();
                fastest(places, !spPlanner(), () -> fastestAsked.get() != asked, null,
                    place -> forScreens(each -> each.npcs.lookAtPoint(place)));
            });
            screen.setTours(this);
        }
        overlays.forEach(overlayManager::add);
        eventBus.register(this);
        eventBus.register(yourHouse);
        eventBus.register(fairyRingLog);
        // The map version's route data, when it came before this start.
        loadPlanner();
        // Started while logged in: this account's house, as at login.
        clientThread.invokeLater(() -> {
            if (client.getGameState() == GameState.LOGGED_IN && generation == loads.get()) {
                yourHouse.loadHouse();
            }
        });
        warnBoth(false);
    }

    /**
     * Loads the planner from the map version's route.zip (the map server's, {@link RouteFiles}), again for each new one,
     * so the route data is fixed without a plugin update.
     */
    void loadPlanner() {
        int generation = loads.get();
        var on = searches;
        if (on == null || !RouteFiles.available()) {
            return;
        }
        inBackground(on, () -> {
            var pathfinder = new Pathfinder(CollisionMap.load(), SeaMap.load());
            var made = new RouteSource(pathfinder.map, pathfinder.sea, pathfinder.shortcutPassages, pathfinder::pocket);
            if (generation == loads.get()) {
                // A request snaps every place's spot to open ground (a tenth of a second the first time): once here,
                // thrown away, so the first route asked for on Swing finds them done. From where the player is (the
                // walk out of the Wilderness kept too), else from any spot on land.
                int at = max(start, 0);
                try {
                    request(made, at, at, false, false, List.of(), state, null);
                } catch (RuntimeException e) {
                    log.warn("Could not prepare the route data", e);
                }
            }
            return Map.entry(pathfinder, made);
        }, loaded -> {
            if (loaded == null || generation != loads.get() || searches == null) {
                return; // failed, or stopped (maybe restarted) while loading
            }
            var pathfinder = loaded.getKey();
            var map = pathfinder.map;
            yourHouse.setCollision(map);
            source = loaded.getValue();
            for (var screen : screens) {
                // Entering a dungeon fits the map to its walkable area.
                screen.view.areaBounds = (p -> WalkableArea.bounds(map, p.getX(), p.getY(), p.getPlane(),
                    AREA_LIMIT));
                // A click on ground drawn on another floor than it is walked on asks for that floor.
                screen.view.ground = p -> map.walkable(p.getX(), p.getY(), p.getPlane());
            }
            walker = pathfinder;
            // A new map version's tables: the route on the way is planned again with them, not dropped.
            var before = controller;
            Optional.ofNullable(before).ifPresent(RouteController::cancelRunning);
            controller = new RouteController(searches, SwingUtilities::invokeLater, pathfinder,
                target -> withBank(request(start, target, false), start, target, leftOut(),
                    fromPlugin ? pluginWilderness : null),
                c -> changed(), System::currentTimeMillis);
            if (before != null && before.target() >= 0) {
                controller.setTarget(before.target());
            }
        });
    }

    /** Counts starts and stops, so a load finishing after a stop is dropped. */
    final AtomicInteger loads = new AtomicInteger();

    /** The plugin makes a new RouteFeature for each start: only what reaches outside it is undone here. */
    void stop() {
        loads.incrementAndGet();
        eventBus.unregister(this);
        eventBus.unregister(yourHouse);
        eventBus.unregister(fairyRingLog);
        overlays.forEach(overlayManager::remove);
        // First: the clear fades the route out and may send it to HD World Markers, both taken back below.
        var running = controller;
        if (running != null) {
            running.clear();
        }
        // Work still on its threads finds it is not wanted.
        controller = null;
        fadeTimer.stop();
        itemReroute.stop();
        fading.clear();
        appearing.clear();
        gone.clear();
        if (hdTiles) {
            hdTiles = false;
            clientThread.invokeLater(this::clearHd);
        }
        for (var pool : new ExecutorService[]{searches, background}) {
            if (pool != null) {
                pool.shutdownNow();
            }
        }
        searches = background = null;
        fastestAsked.incrementAndGet();
        spAsked.incrementAndGet();
        // The plugin keeps this until its next start: not the collision map, the planner or the screens.
        screens = new MapScreen[0];
        source = null;
        walker = null;
        yourHouse.setCollision(null);
        if (handedOver != null) {
            // Else Shortest Path keeps drawing it, with no Clear path here after a restart.
            clientThread.invokeLater(() -> eventBus.post(clear()));
        }
        // Nor the sidebar's panel (the big map's screen and its places hang on it), the item pictures, other plugins'
        // marks, the custom route or this account's house pictures and plan: none of it stays while the plugin is off.
        sidebar = section -> { };
        sidebarFocus = point -> { };
        sidebarTours = () -> { };
        sidebarNow = null;
        itemNames = null;
        named = null;
        marks = List.of();
        tour = null;
        clientThread.invokeLater(yourHouse::forget);
    }

    // ---- client thread ----

    /** In one's house, its portal outside: where "nearest" counts from. */
    WorldPoint outside() {
        int exit = house.exit();
        return exit < 0 ? null : worldPoint(exit);
    }

    WorldPoint nearFrom(int node) {
        return node < 0 ? null : HousePlan.contains(node) ? outside() : worldPoint(node);
    }

    WorldPoint home() {
        int at = yourHouse.inHouse();
        int portal = at >= 0 ? at : !house.inside() ? -1 : house.exit() >= 0 ? house.exit() : house.portal();
        return portal < 0 ? null : worldPoint(portal);
    }

    void tick(WorldPoint location) {
        ticks++;
        var found = new ArrayList<WorldMapPoint>();
        // Other plugins' icons (Slayer task spots, Quest Helper) and the Clue Scroll plugin's spot for the clue now:
        // the list is not public, removeIf only reads it here. RuneLite's own World Map and Party icons are left out.
        worldMapPoints.removeIf(point -> {
            if (CLUE_POINT.equals(point.getClass().getName())) {
                // The clue's picture as the game's map shows it, with a tooltip saying what it is.
                found.add(WorldMapPoint.builder().worldPoint(point.getWorldPoint()).image(point.getImage())
                    .imagePoint(point.getImagePoint()).tooltip(t("rfClue")).snapToEdge(true).build());
            } else if (!CORE_POINTS.contains(point.getClass().getName())) {
                found.add(point);
            }
            return false;
        });
        if (!found.equals(marks)) {
            marks = found;
            repaint();
        }
        var before = lastLocation;
        lastLocation = location;
        if (closedBank) {
            closedBank = false;
            bankClosed();
        }
        bankShut = false;
        Player player = client.getLocalPlayer();
        if (player == null || client.getGameState() != GameState.LOGGED_IN) {
            return;
        }
        var view = player.getWorldView();
        var top = client.getTopLevelWorldView();
        boolean instance = top != null && top.isInstance();
        int node = location == null ? -1 : pack(location);
        // By a planted spirit tree's patch: whether a grown tree stands there now, kept for this account.
        if (location != null && planted.update(location.getRegionID(), client::getVarbitValue)) {
            configManager.setRSProfileConfiguration(GROUP, Planted.KEY, planted.text());
            invokeLater(this::settingsChanged);
        }
        house.update(node, instance, client.getVarbitValue(POH_HOUSE_LOCATION),
            client.getVarbitValue(POH_BUILDING_MODE) == 1);
        returns.update(node);
        if (house.inside()) {
            yourHouse.placeHouse(top);
        }
        if (house.takeScan()) {
            yourHouse.scanHouse();
        }
        if (house.takeChanged()) {
            yourHouse.writeHouse(house.features());
        }
        // Into the house once its rooms are placed (the tile on the plan is right then); out as soon as one is.
        yourHouse.placed &= house.inside();
        boolean inside = house.inside() && yourHouse.placed, entered = inside != wasInside;
        if (entered) {
            wasInside = inside;
            yourHouse.enteredAt = inside ? ticks : -1;
            yourHouse.firstReading = RouteHouse.NONE;
            state = capture();
        }
        // On a ship not one's own (a charter come in at a port): from the dock beside it, where one steps off.
        int from = location == null ? -2 : house.inside() ? yourHouse.inHouse()
            : view == null || view.isTopLevel() ? node : client.getVarbitValue(SAILING_PLAYER_IS_ON_PLAYER_BOAT) == 1
            ? seaAt(location.getX(), location.getY()) : Tiles.pack(location.getX(), location.getY(), 0);
        start = from;
        // Not in other instances (their tiles are copies); in one's own house its plan tells where one is, in an instance
        // copying a place on the map (instance_copies.tsv) that place.
        inCopy = instance && from >= 0 && InstanceCopies.template(x(from), y(from), 0) != null;
        boolean replans = !instance || house.inside() && from >= 0 || inCopy;
        updateHdTiles(from);
        // At once also on coming into (or leaving) the Rune essence mine or the Shipyard: their exits lead back; and after
        // a teleport, which is searched from at once.
        if (ticks % STATE_TICKS == 0 || state == PlayerState.UNKNOWN || returns.exit() != state.returnExit
            || before != null && location != null && before.distanceTo(location) > RouteController.STRAY) {
            state = capture();
        }
        invokeLater(() -> {
            var c = controller;
            if (c != null) {
                if (entered) {
                    c.placeChanged(from);
                }
                Running running = tour;
                if (running != null && running.target >= 0 && (Tiles.near(from, running.target, RouteController.ARRIVED)
                    || c.target() == running.target && c.arrived(from))) {
                    nextStop(from);
                    return;
                }
                if (config.routeClearOnArrival() && c.arrived(from)) {
                    fadeOut(walkedAhead(ahead));
                    c.clear();
                    return;
                }
                c.playerMoved(from >= 0 ? from : -1, replans);
                updateAhead(c, from);
            }
        });
    }

    /** In an instance copying a place on the map (InstanceCopies): routes and the overlays as on the map. */
    volatile boolean inCopy;

    /** Whether the player was in a house at the last tick. */
    boolean wasInside;

    volatile Route ahead;
    /** The sidebar's "Now" box. Swing thread. */
    JComponent sidebarNow;
    int lastAheadSteps = -1, lastAheadPoints = -1;

    /** Passed tiles fade at once: a step or two behind the player, not a trail. */
    static final long FADE_MS = 600;
    final ConcurrentHashMap<Integer, Long> fading = new ConcurrentHashMap<>();
    final Timer fadeTimer = new Timer(40, e -> fadeStep());

    final ConcurrentHashMap<Integer, Long> appearing = new ConcurrentHashMap<>();
    static final long FADE_IN_MS = 400;

    float fadeIn(int point, long now) {
        Long since = appearing.get(point);
        return since == null ? 1f : max(0.05f, min(1f, (now - since) / (float) FADE_IN_MS));
    }

    void fadeInTiles(Set<Integer> tiles) {
        if (tiles.isEmpty() || tiles.size() > 1000) {
            return;
        }
        long t = currentTimeMillis();
        for (int point : tiles) {
            // Only one fading out a moment ago (walked back) comes back from where it was; a new tile is there at once.
            Long out = fading.remove(point);
            if (out != null) {
                appearing.put(point, t - (long) (fadeAlpha(out, t) * FADE_IN_MS));
            }
        }
        fadeTimer.start(); // no-op while running
    }

    static float fadeAlpha(long since, long now) {
        long elapsed = now - since;
        return elapsed <= 0 ? 1f : max(0f, 1f - elapsed / (float) FADE_MS);
    }

    void fadeStep() {
        long now = currentTimeMillis();
        // Tiles fade all the while a route is walked: a map paints again only round the dots it shows, not whole.
        for (var screen : screens) {
            Rectangle dots = screen.view.isShowing() ? fadingDots(screen.view, fading.keySet()) : null;
            if (dots != null) {
                screen.view.repaint(dots);
            }
        }
        fading.values().removeIf(since -> now - since >= FADE_MS);
        gone.removeIf(line -> now - line.since >= FADE_MS);
        appearing.values().removeIf(since -> now - since >= FADE_IN_MS);
        if (fading.isEmpty() && appearing.isEmpty() && gone.isEmpty()) {
            fadeTimer.stop();
        }
    }

    /** Where a map shows these passed tiles' dots (appearing tiles fade in the game only), or null. */
    static Rectangle fadingDots(MapView p, Set<Integer> tiles) {
        int r = (int) ceil(dotRadius(p)) + 2;
        Rectangle dots = null;
        for (int point : tiles) {
            if (visible(p, point)) {
                Rectangle dot = new Rectangle((int) x(p, point) - r, (int) y(p, point) - r, 2 * r, 2 * r);
                dots = dots == null ? dot : dots.union(dot);
            }
        }
        return dots;
    }

    static double dotRadius(MapView p) {
        return max(1.5, pow(2, p.zoom()) * 0.35);
    }

    static void eachWalked(Route route, IntConsumer tile) {
        route.steps.stream().filter(step -> !step.isJump()).flatMapToInt(step -> IntStream.of(tilesOf(step)))
            .forEach(tile);
    }

    static Set<Integer> walked(Route route) {
        var tiles = new HashSet<Integer>();
        if (route != null) {
            eachWalked(route.untilTeleport(), tiles::add);
        }
        return tiles;
    }

    static Set<Integer> minus(Set<Integer> a, Set<Integer> b) {
        var left = new HashSet<Integer>(a);
        left.removeAll(b);
        return left;
    }

    void fadeOut(Set<Integer> tiles) {
        long t = currentTimeMillis();
        if (!tiles.isEmpty() && tiles.size() <= 1000) {
            tiles.forEach(point -> fading.putIfAbsent(point, t));
            fadeTimer.start();
        }
    }

    Route aheadOf, aheadWalkedOf;
    Set<Integer> aheadWalked;

    /** {@link #walked}, cached: each tick asks for the same route again. */
    Set<Integer> walkedAhead(Route route) {
        if (route != aheadWalkedOf || aheadWalked == null) {
            aheadWalkedOf = route;
            aheadWalked = unmodifiableSet(walked(route));
        }
        return aheadWalked;
    }

    static long jumps(Route route) {
        return route.steps.stream().filter(Step::isJump).count();
    }

    void updateAhead(RouteController c, int player) {
        Route route = effective(c), now = route == null ? null : route.ahead(player), before = ahead;
        // As many steps left, and as many points left of the first: the same cut of a route as last tick.
        if (route != null && route == aheadOf && now != null && before != null && now.steps.size() == before.steps.size()
            && (now.steps.isEmpty() || now.steps.get(0).points.length == before.steps.get(0).points.length)) {
            now = before;
        }
        var walkedBefore = walkedAhead(before);
        var walkedNow = now == before ? walkedBefore : walked(now);
        if (route != null && route == aheadOf && before != null && now != null && player >= 0 && !isSea(player)
            && !now.steps.isEmpty() && jumps(now) >= jumps(before) && distance(player, now.steps.get(0).first()) > 2) {
            // Off the way: the route stays as it was, nothing passed (walking away beside it is not walking it) and
            // nothing back, until the player is on it again or a new search's route fades in. A teleport or
            // transport just taken is passed, wherever it put the player.
            return;
        }
        aheadOf = route;
        if (before != null && before != now) {
            fadeOut(minus(walkedBefore, walkedNow));
            fadeInTiles(minus(walkedNow, walkedBefore));
        }
        ahead = now;
        aheadWalkedOf = now;
        // Not wrapped again: on Java 11 the wrappers would nest every tick until the stack overflows.
        aheadWalked = walkedNow == walkedBefore ? walkedBefore : unmodifiableSet(walkedNow);
        show(now);
        int steps = now == null ? -1 : now.steps.size();
        int points = now == null || now.steps.isEmpty() ? -1 : now.steps.get(0).points.length;
        if (steps != lastAheadSteps && lastAheadSteps >= 0 && steps >= 0) {
            showSteps(c, false);
        } else if (points != lastAheadPoints && steps >= 0) {
            // The sidebar's "Now" counts down the tiles left of this walk: only its two texts change.
            var box = sidebarNow;
            if (box != null && box.getParent() != null && !now.steps.isEmpty()) {
                updateNow(box, now);
            } else {
                sidebar.accept(width -> steps(c, width, sidebarFocus, true));
            }
        }
        if (steps != lastAheadSteps || points != lastAheadPoints) {
            lastAheadSteps = steps;
            lastAheadPoints = points;
            repaint();
        }
    }

    PlayerState capture() {
        var carried = new HashMap<Integer, Long>();
        var unlimited = new HashSet<Integer>();
        count(client.getItemContainer(InventoryID.INV), carried);
        // The pouch in the inventory (only it counted yet).
        boolean pouch = carried.keySet().stream().anyMatch(RUNE_POUCHES::contains);
        var worn = client.getItemContainer(InventoryID.WORN);
        if (worn != null) {
            count(worn, carried);
            for (var slot : List.of(EquipmentInventorySlot.WEAPON, EquipmentInventorySlot.SHIELD)) {
                Item item = worn.getItem(slot.getSlotIdx());
                if (item != null && item.getId() >= 0) {
                    unlimited.addAll(ItemSnapshot.runesFromWeapon(client.getItemDefinition(item.getId()).getName()));
                }
            }
        }
        // The game keeps what the pouch holds wherever it is: carried, or taken from the bank as the pouch.
        var held = new HashMap<Integer, Long>();
        var runes = client.getEnum(EnumID.RUNEPOUCH_RUNE);
        for (int i = 0; i < POUCH_TYPES.length; i++) {
            int type = client.getVarbitValue(POUCH_TYPES[i]), amount = client.getVarbitValue(POUCH_AMOUNTS[i]);
            int id = type > 0 && runes != null ? runes.getIntValue(type) : -1;
            if (id > 0 && amount > 0) {
                (pouch ? carried : held).merge(id, (long) amount, Long::sum);
            }
        }
        pouchRunes = held;
        var named = new HashMap<String, Long>();
        held.forEach((id, n) -> named.merge(client.getItemDefinition(id).getName(), n, Long::sum));
        pouchNamed = named;
        if (pandemonium == null || ticks - lastQuest >= QUEST_TICKS) {
            lastQuest = ticks;
            pandemonium = Quest.PANDEMONIUM.getState(client) == QuestState.FINISHED;
        }
        // Where each boat lies and its teleport focus; boats at one port count once, with the best focus there.
        var boats = new LinkedHashMap<Integer, Integer>();
        // The five boats' varbits lie 38 apart, each boat's teleport focus (0 none, 1, 2 greater) 10 after its port.
        for (int port = SAILING_BOAT_1_PORT; port <= SAILING_BOAT_5_PORT; port += 38) {
            Integer tile = RouteFiles.tables().ports.get(client.getVarbitValue(port));
            if (tile != null) {
                boats.merge(tile, client.getVarbitValue(port + 10), Math::max);
            }
        }
        return new PlayerState(ItemSnapshot.of(carried, unlimited), client.getRealSkillLevel(Skill.SAILING),
            pandemonium, boats.keySet().stream().mapToInt(Integer::intValue).toArray(), true,
            house.inside(), house.inside() ? house.exit() : house.portal(), house.own(), house.features(),
            client.getVarbitValue(POH_TELE_TOGGLE) == 0)
            .withReturnExit(returns.exit()).withBoatFocus(boats.values().stream().mapToInt(Integer::intValue).toArray());
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event) {
        if (event.getContainerId() == InventoryID.BANK) {
            var items = new HashMap<Integer, Long>();
            count(event.getItemContainer(), items);
            // Sent after all: as it is.
            bankShut = false;
            rememberBank(items);
            if (atBank && !bankLooked && shown != null) {
                // Once as it opens, with what it holds: a bank step shows while one can still take its items.
                bankLooked = true;
                invokeLater(() -> {
                    itemReroute.setRepeats(false);
                    itemReroute.restart();
                });
            }
            return;
        }
        var before = state;
        state = capture();
        var carried = new HashMap<Integer, Long>();
        count(client.getItemContainer(InventoryID.INV), carried);
        count(client.getItemContainer(InventoryID.WORN), carried);
        if (bankShut || depositBox) {
            // Banked or taken as the bank closed: the game no longer sends the bank, so it is what left one's hands
            // (the rune pouch put away just before the close, its runes then in the bank). At a deposit box each
            // deposit, as it is made.
            var bank = new HashMap<>(bankItems);
            lastCarried.forEach((id, n) -> bank.merge(id, n, Long::sum));
            carried.forEach((id, n) -> bank.merge(id, -n, Long::sum));
            rememberBank(bank);
        }
        lastCarried = carried;
        // What a route may use changed (a staff banked, a teleport's runes or jewellery picked up): the route is looked
        // for again once the items have settled, and after the bank is closed, so it takes the quicker way, no longer
        // one without what it needs, and a bank step names only what is still to take. Other items (logs, drops) change
        // nothing.
        var used = routeItemAmounts;
        boolean changed = shown != null && (used == null ? !state.items.sameAs(before.items)
            : !state.items.sameFor(before.items, used));
        if (changed) {
            // Not while the bank is open: once it is closed, with what was taken out.
            if (atBank) {
                changedAtBank = true;
            } else if (bankShut || closedBank) {
                // Looked at on this tick, with the bank as it is now.
                closedBank = true;
            } else {
                invokeLater(() -> {
                    itemReroute.setRepeats(false);
                    itemReroute.restart();
                });
            }
        }
    }

    /** Whether the bank or a deposit box is open, and what a route may use changed meanwhile. Client thread. */
    boolean atBank, changedAtBank;
    /** A deposit box is open: each deposit goes to the bank, which the game does not send. Client thread. */
    boolean depositBox;
    /** The route was looked for again with the bank just opened. Client thread. */
    boolean bankLooked;

    @Subscribe
    public void onWidgetLoaded(WidgetLoaded event) {
        if (bank(event.getGroupId()) && !atBank) {
            bankLooked = false;
        }
        atBank |= bank(event.getGroupId());
        depositBox |= event.getGroupId() == InterfaceID.BANK_DEPOSITBOX;
    }

    static boolean bank(int group) {
        return group == InterfaceID.BANKMAIN || group == InterfaceID.BANK_DEPOSITBOX;
    }

    @Subscribe
    public void onWidgetClosed(WidgetClosed event) {
        if (bank(event.getGroupId())) {
            atBank = false;
            depositBox = false;
            bankShut = true;
            if (changedAtBank) {
                changedAtBank = false;
                // Items banked or taken just before the close come with the next tick: one look then.
                closedBank = true;
            }
        }
    }

    /** The bank closed with items changed: looked at on the next tick. Client thread, then Swing. */
    boolean closedBank;
    /** The bank closed since the last tick; what one carried at the last item change. Client thread. */
    boolean bankShut;
    Map<Integer, Long> lastCarried = Map.of();

    void bankClosed() {
        if (atBank) {
            // Opened again meanwhile: looked at when it closes.
            changedAtBank = true;
            return;
        }
        // On at once when what the bank step names is all carried (the route was planned with it); else, with
        // only part taken, the bank step stays. Either way searched again, the items having settled.
        int at = start;
        var names = itemNames;
        var carried = lastCarried;
        var kept = new HashMap<String, Long>();
        if (names != null) {
            carried.forEach((id, n) -> kept.merge(String.valueOf(names.apply(id)).toLowerCase(ROOT), n, Long::sum));
        }
        boolean pouch = carried.keySet().stream().anyMatch(RUNE_POUCHES::contains);
        invokeLater(() -> {
            var c = controller;
            // The whole route: the part ahead, cut at the stand, may no longer have the bank step.
            Route whole = aheadOf, on = allTaken(whole, kept, pouch) ? onFromBank(whole, at) : null;
            if (c != null && on != null) {
                // All taken: the route on was planned with them, nothing to search again.
                c.show(c.target(), at, on);
                return;
            }
            settingsChanged();
        });
    }

    /** Whether everything the route's bank step names ("Rune pouch, 3 × Air rune") is carried; false when it cannot tell. */
    static boolean allTaken(Route route, Map<String, Long> carried, boolean pouch) {
        Step bank = route == null ? null : route.first(BANK);
        if (bank == null || bank.said.taken == null) {
            return false;
        }
        for (String item : bank.said.taken) {
            var m = AMOUNT.matcher(item);
            if (!m.matches()) {
                return false;
            }
            // "runes" or "items for …" name nothing to count.
            if (!(m.group(2).equals("Rune pouch") ? pouch
                : !m.group(2).equals("runes") && !m.group(2).startsWith("items for ")
                && enough(m, carried))) {
                return false;
            }
        }
        return true;
    }

    /** Standing at the route's bank step: the route on from it (planned with the bank's items); else null. */
    static Route onFromBank(Route route, int player) {
        Step bank = route == null ? null : route.first(BANK);
        // At any of its booths, not only the one planned.
        if (bank == null || player < 0 || !Tiles.near(player, bank.first(), RouteController.STRAY)) {
            return null;
        }
        var on = List.copyOf(route.steps.subList(route.steps.indexOf(bank) + 1, route.steps.size()));
        return new Route(route.outcome, on, on.stream().mapToInt(s -> s.cost).sum(), route.target, route.end,
            route.exhausted, route.limited, route.nodes);
    }

    /** Milliseconds what is carried must stay the same before the route is looked for again. */
    static final int ITEM_REROUTE_MS = 600;
    final Timer itemReroute = new Timer(ITEM_REROUTE_MS, e -> settingsChanged());

    /** None for a container not there. */
    static void count(ItemContainer container, Map<Integer, Long> into) {
        for (Item item : container == null ? new Item[0] : container.getItems()) {
            if (item.getId() >= 0) {
                into.merge(item.getId(), (long) item.getQuantity(), Long::sum);
            }
        }
    }

    /** The house's part is {@link RouteHouse#onGameStateChanged}. */
    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        if (event.getGameState() == GameState.LOGGED_IN) {
            pandemonium = null;
            warnBoth(false);
            // The game starts the minimap zoom afresh in a new session or world: the saved one goes back on.
            hdMinimap.zoomBack = false;
        } else if (event.getGameState() == GameState.LOGIN_SCREEN) {
            start = -2;
            state = PlayerState.UNKNOWN;
            atBank = changedAtBank = false;
            depositBox = false;
            // A bank open at logout closes then: none of it carries over to the next account.
            bankShut = closedBank = false;
            lastCarried = Map.of();
            returns.reset();
        }
    }

    String get(String key) {
        return configManager.getConfiguration(GROUP, key);
    }

    void set(String key, Object value) {
        configManager.setConfiguration(GROUP, key, value);
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event) {
        String key = event.getKey();
        if (!GROUP.equals(event.getGroup()) || !key.startsWith("route") || HOUSE_KEY.equals(key)
            || "routePlannerChosen".equals(key)) {
            return;
        }
        if ("routePlanner".equals(key)) {
            warnBoth(true);
            // The other planner's route would stay with no way to clear it. Our own hand-over (the first route
            // choosing Shortest Path) is kept.
            invokeLater(() -> {
                var c = controller;
                stopTour();
                if (routing()) {
                    c.clear();
                }
                if (handedOver != null && !spPlanner()) {
                    handOverClear();
                }
                if (handedOver != null) {
                    showHandedOver(this::handedOverPanel, false);
                } else {
                    changed();
                }
                followAgain();
            });
            return;
        }
        if ("routeFollowPlugins".equals(key)) {
            invokeLater(this::followAgain);
        }
        if (LOOK_KEYS.contains(key)) {
            sentAhead = null;
        }
        invokeLater(LOOK_KEYS.contains(key) ? this::changed : this::settingsChanged);
    }

    void settingsChanged() {
        Optional.ofNullable(controller).ifPresent(RouteController::settingsChanged);
    }

    /** Whether our planner has a target now. */
    boolean routing() {
        var c = controller;
        return c != null && c.target() >= 0;
    }

    public void toast(String text) {
        forScreens(screen -> screen.view.showToast(text));
    }

    // ---- Swing ----

    /** With {@code anything}, every requirement counts as met. Swing or the extras thread. */
    RouteRequest request(int begin, int target, boolean anything) {
        return request(begin, target, anything, false, leftOut());
    }

    /** What the running custom route leaves out. */
    List<String> leftOut() {
        Running r = tour;
        return r == null ? List.of() : r.avoid;
    }

    /** Also without {@code leftOut}: what a custom route leaves out. */
    RouteRequest request(int begin, int target, boolean anything, boolean everyItem, List<String> leftOut) {
        return request(begin, target, anything, everyItem, leftOut, state, fromPlugin ? pluginWilderness : null);
    }

    /**
     * For {@code player} (the player with their bank's items); {@code wilderness}: another plugin's noWild for
     * its directions, null for the setting.
     */
    RouteRequest request(int begin, int target, boolean anything, boolean everyItem, List<String> leftOut,
        PlayerState player, Boolean wilderness) {
        return request(source, begin, target, anything, everyItem, leftOut, player, wilderness);
    }

    /** The same with {@code from}'s ways: also a map version's before it is taken (its snaps worked out). */
    RouteRequest request(RouteSource from, int begin, int target, boolean anything, boolean everyItem,
        List<String> leftOut, PlayerState player, Boolean wilderness) {
        var on = screens;
        if (from == null || begin < -1 || on.length == 0) {
            return null;
        }
        var view = on[0].view;
        var options = new Options(config.routeTeleports(), config.routeSailing(),
            shipwrights, anything || config.routeIgnoreLevels(),
            anything || everyItem || config.routeIgnoreItems(), avoided(leftOut), anything ? Saving.NONE
                : new Saving(config.routeSaveTeleport(), config.routeSaveShortcut(),
                    config.routeSaveTransport(), config.routeSaveCanoe(), config.routeSaveCarpet(), config.routeSaveShip()),
            anything, wilderness != null ? wilderness : config.routeAvoidWilderness(), config.routeSailToLand());
        nameItems();
        from.itemNames = itemNames;
        from.plantedTrees = planted.grown();
        return from.request(begin, target, view.pois(), view.unlocks(), player, options, RouteRequest.DEFAULT_NODE_LIMIT);
    }

    /**
     * {@code plain} with a way past a bank to weigh (setting): every bank (the detour tries those quickest to reach),
     * and the same search with the items of this account's bank as last seen. Unchanged without them. {@code leftOut}
     * and {@code wilderness} as {@code plain} was asked with.
     */
    RouteRequest withBank(RouteRequest plain, int begin, int target, List<String> leftOut, Boolean wilderness) {
        var bank = new HashMap<>(bankItems);
        var pouch = new HashMap<String, Long>();
        if (bank.keySet().stream().anyMatch(RUNE_POUCHES::contains)) {
            pouchRunes.forEach((id, n) -> bank.merge(id, n, Long::sum));
            pouch.putAll(pouchNamed);
        }
        var on = screens;
        if (plain == null || begin < -1 || bank.isEmpty() || !config.routeBankTeleports() || config.routeIgnoreItems()
            || on.length == 0) {
            return plain;
        }
        var banks = Poi.flatten(on[0].view.pois()).stream().filter(p -> p.type == PoiType.BANK)
            .map(p -> bankStands(pack(p.location))).toArray(int[][]::new);
        // On from the bank: out of the house, with the bank's items; not from a start on its plan, which counts as in
        // it (no teleport back in to its fairy ring). The detour starts these ways only at the bank.
        var player = state.inOwnHouse(false);
        int from = HousePlan.contains(begin) ? -1 : begin;
        var banked = banks.length == 0 ? null : request(from, target, false, false, leftOut, player.withBank(bank),
            wilderness);
        return banked == null ? plain : plain.withBank(new BankDetour(banks, banked, config.routeSaveBank(), pouch));
    }

    /** Per account (RS profile): the bank's items any teleport or transport needs, as last seen. */
    static final String BANK_KEY = "bankItems";
    /** This account's bank as last seen (item id to amount): only what routes may need. */
    volatile Map<Integer, Long> bankItems = Map.of();
    /** What the rune pouch holds while not carried (item id to amount); client thread sets it. */
    volatile Map<Integer, Long> pouchRunes = Map.of();
    /** The same by the game's own item names, read where the pouch is (not through the item name cache). */
    volatile Map<String, Long> pouchNamed = Map.of();
    /** The item ids any teleport, transport, gate or own way needs; null before the places are known. */
    volatile Set<Integer> routeItemIds;
    /** The items a teleport or transport step needs, by the step's name (its plain name: without the bank). */
    volatile Map<String, Set<Integer>> stepItems = Map.of();
    /** The same with the most of each any of them asks for. */
    volatile Map<Integer, Long> routeItemAmounts;

    /** This account's bank as kept, on its login. */
    void loadBank() {
        String saved = configManager.getRSProfileConfiguration(GROUP, BANK_KEY);
        var items = new TreeMap<Integer, Long>();
        for (String entry : saved == null ? new String[0] : saved.split(",")) {
            var parts = entry.split(":");
            if (parts.length == 2 && parts[0].matches("\\d{1,9}") && parts[1].matches("\\d{1,18}")) {
                items.put(Integer.parseInt(parts[0]), Long.parseLong(parts[1]));
            }
        }
        bankItems = unmodifiableMap(items);
    }

    /** The bank as seen (item id to amount), kept for this account when what routes may need in it changed. Client thread. */
    void rememberBank(Map<Integer, Long> bank) {
        if (routeItemIds == null) {
            nameItems();
        }
        var wanted = routeItemIds;
        // Before a map version's tables are read what routes need is not known: the bank kept stays.
        if (wanted == null) {
            return;
        }
        var items = new TreeMap<>(bank);
        items.values().removeIf(n -> n <= 0);
        items.keySet().removeIf(id -> !wanted.contains(id) && !RUNE_POUCHES.contains(id));
        if (items.equals(bankItems) || configManager.getRSProfileKey() == null) {
            return;
        }
        bankItems = unmodifiableMap(items);
        configManager.setRSProfileConfiguration(GROUP, BANK_KEY, items.entrySet().stream()
            .map(e -> e.getKey() + ":" + e.getValue()).collect(joining(",")));
    }

    /** The game's item names, so a step says what it lacks by name; set by the plugin. */
    volatile ItemNames itemNames;
    /** The tables whose items were named last. */
    volatile RouteTables named;

    /**
     * Asks the game for the names of the items a map version's teleports, transports, gates, shortcuts and own ways need
     * (also what is kept of the bank), and again for those it had not named yet; a name had later searches the route
     * again, else its steps would say "Item 563".
     */
    void nameItems() {
        var names = itemNames;
        var tables = RouteFiles.tables();
        if (names == null || tables.places.isEmpty()) {
            return;
        }
        if (tables != named) {
            itemTables(tables);
        }
        int had = names.names.size();
        if (!names.names.keySet().containsAll(routeItemIds)) {
            names.resolve(routeItemIds, () -> {
                if (names.names.size() > had) {
                    settingsChanged();
                }
            });
        }
    }

    void itemTables(RouteTables tables) {
        named = tables;
        var byStep = new HashMap<String, Set<Integer>>();
        tables.places.values().forEach(jumps -> jumps.stream().filter(jump -> !jump.house).forEach(jump ->
            // A spell's runes are no item to use: outlined in the bank only.
            byStep.computeIfAbsent((jump.group != null && jump.group.endsWith("Spellbook") ? SPELL : "") + jump.name,
                k -> new HashSet<>()).addAll(Requirements.itemIds(jump.need.items + "&&" + jump.poiNeed.items))));
        ownItems(byStep);
        byStep.values().removeIf(Set::isEmpty);
        stepItems = byStep;
        var most = new HashMap<Integer, Long>();
        fixedNeeds().forEach(needs -> Requirements.itemAmounts(needs.items, most));
        routeItemAmounts = unmodifiableMap(most);
        routeItemIds = unmodifiableSet(new HashSet<>(most.keySet()));
    }

    /**
     * The planner's own ways: into the house (the spell chosen or not, its tablet and the capes are what to use) and
     * to the boat (the Sailing cape, the tablet), into {@code byStep}.
     */
    static void ownItems(Map<String, Set<Integer>> byStep) {
        var house = new HashSet<Integer>();
        var boat = new HashSet<Integer>();
        for (var way : RouteFiles.tables().own) {
            if (way[0].equals("house") && way[2].isEmpty() || way[0].equals("sailing cape")) {
                (way[0].equals("house") ? house : boat).addAll(Requirements.itemIds(way[3]));
            } else if (way[0].equals("boat teleport")) {
                boat.addAll(Requirements.itemIds(way[7]));
            }
        }
        own("house").forEach(way -> {
            byStep.put(way[1], house);
            byStep.put(way[1] + " (Inside)", house);
        });
        byStep.put("Teleport to Boat", boat);
    }

    /** On the first map right-click: Shortest Path becomes the planner if it is on and nothing was chosen. */
    void choosePlannerOnce() {
        if (get("routePlannerChosen") != null) {
            return;
        }
        set("routePlannerChosen", true);
        if (get("routePlanner") == null && shortestPathOn()) {
            set("routePlanner", RoutePlanner.SHORTEST_PATH);
        }
    }

    /** Of a search's places, this many are planned to for the fastest (see {@link #fastest}). */
    static final int FASTEST_OF = 8;
    /** Which look for the fastest is current; an older one stops. */
    final AtomicInteger fastestAsked = new AtomicInteger();

    /**
     * Swing thread: of these places, the one the planner gets to soonest goes to {@code chosen} (on Swing, unless
     * {@code stale} by then); with {@code plan} false, or when no route can be planned now, the nearest as the crow
     * flies. {@code wilderness}: as {@link #request}.
     */
    void fastest(List<WorldPoint> places, boolean plan, BooleanSupplier stale, Boolean wilderness,
        Consumer<WorldPoint> chosen) {
        int from = start;
        var pool = background;
        Pathfinder pathfinder = walker;
        var nearest = new ArrayList<WorldPoint>(new LinkedHashSet<>(places));
        // In one's house from its portal outside, not from the plan's tiles off the map (none known: as given).
        var me = from < 0 ? null : nearFrom(from);
        if (me != null) {
            nearest.sort(Comparator.comparingLong(p -> Tour.away(p, me)));
        }
        if (nearest.isEmpty()) {
            return;
        }
        if (from < 0 || pool == null || pathfinder == null || !plan || nearest.size() == 1) {
            chosen.accept(nearest.get(0));
            return;
        }
        var leftOut = leftOut();
        toast("Finding the fastest…");
        if (!inBackground(pool, () -> {
            var candidates = candidates(nearest, nearest.stream().mapToInt(this::node).toArray(),
                request(from, node(nearest.get(0)), false, false, leftOut, state, wilderness));
            var best = nearest.get(0);
            int bestCost = Integer.MAX_VALUE;
            for (var p : candidates) {
                if (stale.getAsBoolean()) {
                    return null;
                }
                int to = node(p);
                // Weighed as the route there will be: past a bank too.
                var request = withBank(request(from, to, false, false, leftOut, state, wilderness), from, to,
                    leftOut, wilderness);
                Route route;
                try {
                    // Nearest as the crow flies first; a place that cannot beat the best so far is given up on
                    // early. Chosen by the same cost (time, teleports a little heavier) the route is planned by:
                    // by time, a place reached by teleport could be given up on although quicker.
                    route = request == null ? null : find(pathfinder, request, stale, bestCost);
                } catch (Throwable e) {
                    log.warn("Could not plan to {}", p, e);
                    continue;
                }
                if (route != null && route.outcome == FOUND && route.cost < bestCost) {
                    best = p;
                    bestCost = route.cost;
                }
            }
            return best;
        }, found -> {
            if (found != null && !stale.getAsBoolean()) {
                chosen.accept(found);
            }
        })) {
            chosen.accept(nearest.get(0));
        }
    }

    /**
     * Of {@code nearest} (nearest as the crow flies first, at {@code nodes}), the {@link #FASTEST_OF} planned to: half
     * of them the nearest, then those a teleport or transport gets to soonest by a guess from {@code guessed}'s ways.
     */
    static Collection<WorldPoint> candidates(List<WorldPoint> nearest, int[] nodes, RouteRequest guessed) {
        var candidates = new LinkedHashSet<>(nearest.subList(0, min(FASTEST_OF / 2, nearest.size())));
        for (int i : guessed == null ? new int[0] : quickest(guessed, nodes, FASTEST_OF)) {
            if (candidates.size() < FASTEST_OF) {
                candidates.add(nearest.get(i));
            }
        }
        return candidates;
    }

    /** Swing thread; nothing while the data loads. */
    void routeTo(WorldPoint point) {
        stopTour();
        if (toSp(point)) {
            return;
        }
        var now = controller;
        if (now != null) {
            newRoute();
            now.setTarget(node(point));
        }
    }

    /** Where the planner goes for a spot: its sea block for one {@link #atSea}. */
    int node(WorldPoint p) {
        return atSea(p) ? seaAt(p.getX(), p.getY()) : pack(p);
    }

    /**
     * A spot of the game on the water (a Barracuda Trial's start, salvage) lies here, or beside it (a click on the
     * icon): it is sailed to.
     */
    boolean atSea(WorldPoint point) {
        int at = pack(point);
        var on = screens;
        return point.getPlane() == 0 && (Arrays.stream(seaSpots).anyMatch(spot -> distance(spot, at) <= 2)
            || on.length > 0 && on[0].view.pois().stream()
            .anyMatch(poi -> poi.atSea() && poi.location.getPlane() == 0 && poi.location.distanceTo2D(point) <= 2));
    }

    /** The user's own route: shown at once, with nothing avoided yet. */
    void newRoute() {
        bringUp = true;
        fromPlugin = false;
        routeAvoid.clear();
    }

    void contribute(JPopupMenu popup, WorldPoint point) {
        // Before the entries are made, so they name the planner that will be used.
        choosePlannerOnce();
        var c = controller;
        // The entry used most at the top of the menu (#20), Clear path under it.
        boolean theirs = spPlanner();
        item(popup, 0, theirs ? t("rfPathSp") : c == null ? t("rfPathLoading") : "Path to here",
            () -> routeTo(point)).setEnabled(theirs || c != null);
        int at = 1;
        if (handedOver != null || routing()) {
            item(popup, at++, tour != null ? "Stop custom route" : "Clear path", this::clearRoute);
        }
        popup.insert(new JPopupMenu.Separator(), at);
        popup.addSeparator();
        item(popup, "Add to custom route", () -> addStop(Stop.place(placeName(point), point)))
            .setToolTipText(t("addStopTip", editingName()));
        item(popup, "Custom routes...", () -> forScreens(MapScreen::openTours)).setToolTipText(t("toursTip"));
        item(popup, "Your house...", yourHouse::openHouse).setToolTipText(t("houseTip"));
    }

    /** Max tiles of a walkable area to count as one dungeon to fit the map to. */
    static final int AREA_LIMIT = 60_000;

    // ---- custom routes ----

    static final String TOURS_KEY = "customRoutes";
    static final String EDITING_KEY = "customRouteEditing";

    @RequiredArgsConstructor
    static final class Running {
        final Tour tour;
        volatile int index, target = -1;
        /** Each stop's tile, fixed at the start (a kind: the one nearest the stop before); -1 none. */
        int[] points = new int[0];
        /** The way to each stop from the one before, planned at the start; null until found. */
        volatile Route[] legs = new Route[0];
        /** Stops skipped one after another, having no place. */
        int skipped;
        /** The ways the route leaves out, as saved; a copy for the other threads. */
        volatile List<String> avoid = List.of();
        /** Counts the plans of its parts: an older plan still running finds it is not wanted. */
        volatile int plan;
    }

    /** Set on Swing; read on the client and extras threads too. */
    volatile Running tour;

    /** The running custom route's name, or null. */
    public String runningTour() {
        Running r = tour;
        return r == null ? null : r.tour.name;
    }

    /**
     * Swing thread: a way (or every one of its kind) the running custom route leaves out from now on, saved with the
     * route; the way to the current stop is looked for again, and the later parts planned again.
     */
    void avoidInRoute(String name) {
        Running r = tour;
        if (r == null) {
            return;
        }
        var all = tours();
        Tour saved = all.stream().filter(t -> t.name.equals(r.tour.name)).findFirst().orElse(null);
        if (saved == null || saved.avoid.size() >= MAX_AVOIDED) {
            toast(saved == null ? t("tourGone", r.tour.name) : t("avoidFull", r.tour.name, MAX_AVOIDED));
            return;
        }
        saved.avoid.add(name);
        r.avoid = new ArrayList<>(saved.avoid);
        saveTours(all, editingName());
        toast(t("avoided", r.tour.name, Tour.describe(name).toLowerCase(ROOT)));
        settingsChanged();
        r.legs = new Route[r.points.length];
        r.plan++;
        planLegs(r);
        redoTours();
    }

    public List<Tour> tours() {
        return decode(get(TOURS_KEY));
    }

    void saveTours(List<Tour> tours, String editing) {
        set(TOURS_KEY, encode(tours));
        set(EDITING_KEY, editing);
    }

    /** The route stops are added to: the last one edited, else the first, else a new "My route". */
    public String editingName() {
        String editing = get(EDITING_KEY);
        var tours = tours();
        return tours.stream().anyMatch(t -> t.name.equals(editing)) ? editing
            : tours.isEmpty() ? "My route" : tours.get(0).name;
    }

    void addStop(Stop stop) {
        var tours = tours();
        String name = editingName();
        Tour target = Tour.named(tours, name);
        if (target.stops.size() >= MAX_STOPS) {
            JOptionPane.showMessageDialog(screens.length > 0 ? screens[0] : null, t("stopsFull", name, MAX_STOPS),
                "Custom routes", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        target.stops.add(stop);
        saveTours(tours, name);
        redoTours();
        toast(t("stopAdded", name, target.stops.size()));
    }

    String placeName(WorldPoint point) {
        if (screens.length > 0) {
            for (var poi : screens[0].view.pois()) {
                if (poi.location.getPlane() == point.getPlane() && poi.location.distanceTo2D(point) <= 2) {
                    return poi.name;
                }
            }
            String place = screens[0].placeName(point);
            if (place != null) {
                return "Near " + place;
            }
        }
        return t("rfSpot");
    }

    public void save(List<Tour> tours, String editing) {
        saveTours(tours, editing);
        redoTours();
    }

    public void cancelOrder() {
        orders.incrementAndGet();
    }

    void redoTours() {
        forScreens(MapScreen::redoTours);
        sidebarTours.run();
    }

    public void runTour(Tour chosen) {
        if (chosen.stops.isEmpty()) {
            return;
        }
        // A stop right after the same one is skipped.
        Running running = new Running(chosen.withoutRepeats());
        running.avoid = new ArrayList<>(chosen.avoid);
        tour = running;
        var at = resolve(running.tour.stops, nearFrom(start));
        running.points = at.stream().mapToInt(p -> p == null ? -1 : node(p)).toArray();
        running.legs = new Route[at.size()];
        goToStop(start);
        planLegs(running);
        redoTours();
    }

    /** Plans every leg in the background, so the whole route is drawn at once. */
    void planLegs(Running running) {
        var work = background;
        Pathfinder pf = walker;
        if (work == null || pf == null || spPlanner()) {
            return;
        }
        int plan = running.plan;
        BooleanSupplier stale = () -> tour != running || running.plan != plan || background == null;
        inBackground(work, () -> {
            for (int i = 1; i < running.points.length; i++) {
                if (stale.getAsBoolean()) {
                    return null;
                }
                int from = running.points[i - 1], to = running.points[i];
                var request = from < 0 || to < 0 ? null : request(from, to, false);
                Route leg = request == null ? null : pf.find(request, stale);
                if (leg != null && leg.outcome != CANCELLED) {
                    int at = i;
                    invokeLater(() -> {
                        if (running.plan != plan) {
                            return;
                        }
                        var legs = running.legs.clone();
                        legs[at] = leg;
                        running.legs = legs;
                        repaint();
                    });
                }
            }
            return null;
        }, none -> { });
    }

    TourPanel.Actions sidebarTours(Runnable refresh) {
        sidebarTours = refresh;
        return this;
    }

    /** Also shows the steps in the sidebar; a step clicked there goes to {@code focus}. */
    void setSidebar(Consumer<IntFunction<JComponent>> show, Consumer<WorldPoint> focus) {
        sidebar = show;
        sidebarFocus = focus;
        changed();
    }

    Color legColor(int leg) {
        return leg <= 0 ? walk() : LEG_COLORS[(leg - 1) % LEG_COLORS.length];
    }

    Color current() {
        Running running = tour;
        return running == null ? walk() : legColor(running.index);
    }

    /** The custom route's parts after the current one, last first (drawn under the nearer ones). */
    List<Map.Entry<Integer, Route>> laterLegs() {
        Running running = tour;
        var later = new ArrayList<Map.Entry<Integer, Route>>();
        if (running == null) {
            return later;
        }
        var legs = running.legs;
        for (int i = legs.length - 1; i > running.index; i--) {
            if (legs[i] != null && !legs[i].steps.isEmpty()) {
                later.add(Map.entry(i, legs[i]));
            }
        }
        return later;
    }

    /** Stops a custom route and clears the route, also Shortest Path's. */
    public void clearRoute() {
        var c = controller;
        stopTour();
        Optional.ofNullable(c).ifPresent(RouteController::clear);
        if (handedOver != null) {
            handOverClear();
        }
    }

    void stopTour() {
        if (tour != null) {
            tour = null;
            redoTours();
        }
    }

    void nextStop(int at) {
        Running running = tour;
        var c = controller;
        if (running == null || c == null) {
            return;
        }
        running.index++;
        if (running.index >= running.tour.stops.size() && running.index > 1 && loops(running)) {
            running.index = 0;
        } else if (running.index >= running.tour.stops.size()) {
            fadeOut(walkedAhead(ahead));
            clearRoute();
            toast("\"" + running.tour.name + "\" done");
            // Another plugin's directions kept while it ran are followed now.
            followAgain();
            return;
        }
        goToStop(at);
    }

    /** As saved, so ticking Loop counts for the route already running. */
    boolean loops(Running running) {
        return tours().stream().anyMatch(t -> t.loop && t.name.equals(running.tour.name));
    }

    /** Routes to the current stop; a kind becomes the one nearest {@code from}. */
    void goToStop(int from) {
        Running running = tour;
        var c = controller;
        if (running == null || c == null) {
            return;
        }
        int planned = running.index < running.points.length ? running.points[running.index] : -1;
        var to = planned >= 0 ? worldPoint(planned) : resolve(running.tour.stops.get(running.index), nearFrom(from));
        if (to == null) {
            // A kind with no place yet (icons not loaded): skipped. A loop of only such stops ends.
            if (++running.skipped > running.tour.stops.size()) {
                clearRoute();
                return;
            }
            nextStop(from);
            return;
        }
        running.skipped = 0;
        // The stop's place as planned (a spot at sea is its sea block, whose centre may lie off the spot).
        running.target = planned >= 0 ? planned : node(to);
        if (toSp(to)) {
            return;
        }
        newRoute();
        var legs = running.legs;
        Route leg = running.index > 0 && running.index < legs.length ? legs[running.index] : null;
        // Planned from the stop before: shown only where the player is there (not after Next stop elsewhere), else it
        // would stand as the way from here and not be planned again until the player walks off it.
        if (leg != null && leg.outcome == FOUND && (leg.steps.isEmpty()
            || Tiles.near(from, leg.steps.get(0).first(), RouteController.STRAY))) {
            c.show(running.target, from, leg);
            return;
        }
        c.setTarget(running.target);
    }

    /** Each stop's place; a kind becomes the one nearest the stop before (or {@code near}); null when none. */
    List<WorldPoint> resolve(List<Stop> stops, WorldPoint near) {
        var points = new ArrayList<WorldPoint>();
        for (var stop : stops) {
            var p = resolve(stop, near);
            points.add(p);
            near = p != null ? p : near;
        }
        return points;
    }

    WorldPoint resolve(Stop stop, WorldPoint near) {
        return !stop.isKind() ? stop.point : Tour.nearest(screens.length == 0 ? null : screens[0].kind(stop.kind), near);
    }

    /** "▶ My route: stop 2 of 5", its stops, Next stop and Stop route, above a running custom route's steps. */
    JComponent tourHeader(Running running, int width) {
        JPanel header = column();
        var stops = running.tour.stops;
        header.add(label("\u25B6 " + running.tour.name + (loops(running) ? " (loop)" : "") + ": stop "
            + (running.index + 1) + " of " + stops.size(), width,
            Font.BOLD, legColor(running.index)));
        for (int i = 0; i < stops.size(); i++) {
            header.add(label((i + 1) + ". " + stops.get(i).name, width, i == running.index ? Font.BOLD : Font.PLAIN,
                i < running.index ? MEDIUM_GRAY_COLOR : legColor(i)));
        }
        JPanel buttons = panel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        buttons.add(button(running.index + 1 < stops.size() || stops.size() > 1 && loops(running) ? "Next stop" : "Finish",
            () -> nextStop(start)));
        buttons.add(Box.createHorizontalStrut(4));
        buttons.add(button("Stop route", this::clearRoute));
        header.add(buttons);
        header.add(Box.createVerticalStrut(6));
        return header;
    }

    /**
     * Orders the stops fastest from the player, by routes between every two (in the background), and saves it unless
     * the stops changed meanwhile. {@code done} always runs on Swing.
     */
    public void fastestOrder(Tour chosen, boolean everything, Runnable done) {
        var work = background;
        Pathfinder pf = walker;
        int from = start;
        if (work == null || pf == null) {
            done.run();
            return;
        }
        String name = chosen.name;
        var stops = List.copyOf(chosen.stops);
        int order = orders.incrementAndGet();
        var me = nearFrom(from);
        var points = new ArrayList<WorldPoint>();
        points.add(me);
        points.addAll(resolve(stops, me));
        var leftOut = new ArrayList<String>(chosen.avoid);
        toast(t(everything ? "orderingAll" : "ordering", stops.size()));
        BooleanSupplier cancelled = () -> background == null || orders.get() != order;
        if (!inBackground(work, () -> {
            int n = points.size();
            // Each trip's cost with a home or minigame teleport (cast once in the route, then its cooldown),
            // and without: the order takes such a teleport on one trip at most.
            long[][] once = new long[n][n], cost = new long[n][n];
            // The same ways from any spot on land (the stops and the player are tiles): asked for once.
            var ways = request(0, 0, everything, false, leftOut);
            var without = new ArrayList<String>(leftOut);
            without.add(ONCE_ONLY);
            var plainWays = request(0, 0, everything, false, without);
            // Stop by stop: the searches to one stop share their heuristic, and one that tried everything tells
            // where else that stop is out of reach. None back to the player: an order never returns there.
            for (int b = 1; b < n && !cancelled.getAsBoolean(); b++) {
                var outOfReach = new ArrayList<Route>();
                var plainOutOfReach = new ArrayList<Route>();
                for (int a = 0; a < n; a++) {
                    // From a stop with no place no trip is known either: free only from the player.
                    Route trip = a == b || a > 0 && points.get(a) == null ? null
                        : between(pf, ways, points.get(a), points.get(b), outOfReach, cancelled);
                    once[a][b] = a == b ? 0 : tripCost(trip);
                    // Without the teleport only where the trip took one.
                    cost[a][b] = trip == null || trip.steps.stream().noneMatch(RouteSource::onceOnly) ? once[a][b]
                        : tripCost(between(pf, plainWays, points.get(a), points.get(b), plainOutOfReach, cancelled));
                }
            }
            var locked = new boolean[stops.size()];
            for (int i = 0; i < locked.length; i++) {
                locked[i] = stops.get(i).locked;
            }
            return cancelled.getAsBoolean() ? null : Tour.fastestOrder(cost, once, locked);
        }, result -> {
            try {
                if (result != null && orders.get() == order) {
                    applyOrder(name, stops, result);
                } else if (orders.get() != order) {
                    // Another panel's order or a cancel: said, not dropped without a word.
                    toast(t("orderStopped", name));
                }
            } finally {
                done.run();
            }
        })) {
            done.run();
        }
    }

    /** Saves the order if the route's stops are unchanged. */
    void applyOrder(String name, List<Stop> stops, List<Integer> order) {
        var all = tours();
        for (int i = 0; i < all.size(); i++) {
            Tour now = all.get(i);
            if (!now.name.equals(name)) {
                continue;
            }
            var was = now.stops;
            if (was.size() != stops.size() || !IntStream.range(0, was.size())
                .allMatch(at -> was.get(at).same(stops.get(at)) && was.get(at).name.equals(stops.get(at).name)
                && was.get(at).locked == stops.get(at).locked)) {
                toast(t("orderChanged", name));
                return;
            }
            var sorted = new ArrayList<Stop>();
            for (int at : order) {
                sorted.add(now.stops.get(at - 1));
            }
            // Its Loop and left-out ways stay.
            all.set(i, new Tour(name, sorted, now.loop, now.avoid));
            // The route edited now stays so, also when another was chosen meanwhile.
            save(all, editingName());
            return;
        }
        toast(t("orderLost", name));
    }

    /** Which fastest-order run is current; any other stops and keeps nothing. */
    final AtomicInteger orders = new AtomicInteger();

    static long tripCost(Route trip) {
        return trip == null ? -1 : trip.cost;
    }

    /**
     * The trip found, or null when there is no way, also where an earlier search to {@code b} that tried everything
     * got to. From nowhere known (the player's place unknown) every first stop is as good: a trip costing nothing.
     */
    static Route between(Pathfinder pf, RouteRequest ways, WorldPoint a, WorldPoint b, List<Route> outOfReach,
        BooleanSupplier cancelled) {
        if (b == null || ways == null) {
            return null;
        }
        if (a == null) {
            return new Route(FOUND, List.of(), 0, pack(b), pack(b), false, false, 0);
        }
        int from = pf.startNode(pack(a));
        if (outOfReach.stream().anyMatch(proved -> proved.reached(from))) {
            return null;
        }
        Route route = pf.find(ways.between(pack(a), pack(b)), cancelled);
        if (route.exhausted && route.outcome != FOUND) {
            outOfReach.add(route);
        }
        return route.outcome == FOUND ? route : null;
    }

    /** Even with every requirement met there is no way: the data lacks a passage. */
    static final String NO_WAY = t("rfNoWay");

    /** Swing thread: for an unreachable target, the way with every requirement met. */
    Route why;
    volatile Route whyFor;
    int whyTarget = -1;
    boolean explaining;
    /** Which look for the way anyone could go is current; any other stops and keeps nothing. */
    final AtomicInteger whyAsked = new AtomicInteger();

    /** Looks in the background for what is missing. Swing thread. */
    void explain(RouteController c, Route route) {
        if (route == whyFor) {
            return;
        }
        boolean nearest = route != null && route.outcome == NEAREST;
        // Searched again only because the player moved: the way anyone could go stays as found (or as being found),
        // not looked for by flooding the world once more.
        boolean keep = nearest && c.onlyMoved() && c.target() == whyTarget && (why != null || explaining);
        whyFor = route;
        if (keep) {
            return;
        }
        int asked = whyAsked.incrementAndGet();
        explaining = false;
        if (c.target() != whyTarget) {
            why = null;
            whyTarget = c.target();
        }
        var pool = background;
        Pathfinder pathfinder = walker;
        // Whenever the way does not arrive (also at the search limit), so the whole route shows with what is missing.
        if (!nearest || pool == null || pathfinder == null || config.routeIgnoreLevels() && config.routeIgnoreItems()) {
            why = null;
            return;
        }
        var request = request(start, c.target(), true);
        if (request == null) {
            whyFor = null; // retry once the start is known
            return;
        }
        explaining = true;
        if (!lookUp(pool, pathfinder, () -> request, whyAsked, asked, found -> {
            why = found;
            explaining = false;
            changed();
        })) {
            explaining = false;
        }
    }

    /**
     * Swing thread: in the background, the route for {@code request} (null when none, or the search failed) goes to
     * {@code found} on Swing while {@code asked} is the current look of {@code looks}; false when the pool is shut.
     */
    boolean lookUp(ExecutorService pool, Pathfinder pathfinder, Supplier<RouteRequest> request, AtomicInteger looks,
        int asked, Consumer<Route> found) {
        return inBackground(pool, () -> {
            var made = request.get();
            return made == null ? null : pathfinder.find(made, () -> looks.get() != asked);
        }, route -> {
            if (looks.get() == asked) {
                found.accept(route);
            }
        });
    }

    /** A tip is given when bringing items saves at least this many half ticks (15 seconds) ... */
    static final int TIP_MIN_SAVED = 50;
    /** ... and this share of the route's time. */
    static final int TIP_MIN_PERCENT = 15;

    /** Swing thread: what to bring to get there notably sooner, for {@link #tipFor}; null when nothing. */
    Tip tip;
    Route tipFor;
    int tipTarget = -1;
    /** Which look for a tip is current; any other stops and keeps nothing. */
    final AtomicInteger tipAsked = new AtomicInteger();

    /** Looks in the background once per target whether carrying something would make the way notably shorter. */
    void lookForTip(RouteController c, Route route) {
        if (route == tipFor) {
            return;
        }
        tipFor = route;
        // Searched again only because the player moved: the tip found for this target stays.
        if (c.onlyMoved() && c.target() == tipTarget) {
            return;
        }
        int asked = tipAsked.incrementAndGet();
        tipTarget = c.target();
        tip = null;
        var pool = background;
        Pathfinder pathfinder = walker;
        if (route == null || route.outcome != FOUND || pool == null || pathfinder == null || !config.routeTips()
            || config.routeIgnoreItems()) {
            return;
        }
        int from = start, target = c.target();
        lookUp(pool, pathfinder, () -> request(from, target, false, true, leftOut()), tipAsked, asked,
            found -> {
                Tip made = tip(route, found);
                tip = made;
                List<Integer> unnamed = made == null ? List.of() : made.unnamed;
                var names = itemNames;
                if (!unnamed.isEmpty() && names != null) {
                    // Asked of the game only now: once named, the tip is looked for again to say them.
                    // resolve() calls back on the Swing thread.
                    names.resolve(unnamed, () -> {
                        if (tipAsked.get() == asked && controller == c) {
                            tipFor = null;
                            tipTarget = -1;
                            changed();
                        }
                    });
                }
                changed();
            });
    }

    /** What to bring for a quicker way: each thing with the step that needs it, and how much sooner. */
    @RequiredArgsConstructor
    static final class Tip {
        /** "3200 × Coins", with the step it is for: "Charter ship: Port Tyras". */
        final List<String[]> bring;
        /** "About 2 minutes sooner". */
        final String sooner;
        /** Item ids the game had not named yet ("Item 995"). */
        final List<Integer> unnamed;
    }

    /**
     * What to bring when the way with any item is notably quicker than the player's and some step of it needs what
     * they do not carry; else null.
     */
    static Tip tip(Route yours, Route withItems) {
        if (yours == null || withItems == null || yours.outcome != FOUND || withItems.outcome != FOUND) {
            return null;
        }
        int saved = yours.time() - withItems.time();
        if (saved < TIP_MIN_SAVED || saved * 100 < yours.time() * TIP_MIN_PERCENT) {
            return null;
        }
        var lacking = withItems.steps.stream().filter(RouteText::lacks).collect(toList());
        return lacking.isEmpty() ? null : new Tip(lacking.stream().map(step -> new String[]{step.said.lack,
            describe(step)}).collect(toList()), about((saved + 1) / 2) + " sooner", lacking.stream()
            .filter(step -> step.said.unnamed != null).flatMap(step -> step.said.unnamed.stream()).collect(toList()));
    }

    /** The steps that need what the player lacks. Null when nothing to say; empty when nothing nameable. */
    static List<String> blockers(Route why) {
        if (why == null || why.outcome == CANCELLED) {
            return null;
        }
        if (why.outcome != FOUND) {
            // Only a search that tried everything may say there is no way.
            return why.exhausted ? List.of(NO_WAY) : null;
        }
        return why.steps.stream().filter(RouteText::lacks)
            .map(step -> describe(step) + ": needs " + step.said.lack).collect(toList());
    }

    Route effective(RouteController c) {
        Route route = c.route();
        if (unreachable(route)) {
            return why;
        }
        // Not the way to the nearest spot while the real way is looked for.
        return route != null && route.outcome == NEAREST && explaining ? null : route;
    }

    boolean unreachable(Route route) {
        Route way = why;
        // Or the way anyone could go gets closer than the player's.
        return route != null && route.outcome == NEAREST && route == whyFor && way != null
            && (way.outcome == FOUND || way.outcome == NEAREST && way.end >= 0 && !way.steps.isEmpty()
            && (route.end < 0 || route.steps.isEmpty() || apart(way.end, way.target) < apart(route.end, route.target)));
    }

    static int apart(int from, int to) {
        return distance(from, to) + (z(from) == z(to) ? 0 : 32);
    }

    void changed() {
        var c = controller;
        if (c != null) {
            explain(c, c.route());
            lookForTip(c, c.route());
        }
        Route route = c == null ? null : effective(c), before = ahead;
        // Cut again only for another route: the same one follows the player each tick (and stays while they are off it).
        if (route != aheadOf) {
            ahead = route == null ? null : route.ahead(start);
            aheadOf = route;
            if (before != null && before != ahead) {
                // The old way fades out as the new one fades in.
                var was = walkedAhead(before);
                var now = walkedAhead(ahead);
                if (ahead == null && lines()) {
                    // Gone as a whole: its lines fade as they were.
                    long t = currentTimeMillis();
                    before.steps.stream().filter(s -> s.kind == Kind.WALK)
                        .forEach(s -> gone.add(new Gone(s.points, current(), t)));
                    // Also with nothing else fading (a route that started with a teleport): the timer clears them.
                    fadeTimer.start();
                }
                fadeOut(minus(was, now));
                fadeInTiles(minus(now, was));
            }
        }
        show(ahead);
        if (hdTiles) {
            // A new route or look reaches HD World Markers at once, not at the next game tick.
            clientThread.invokeLater(() -> updateHdTiles(start));
        }
        boolean up = bringUp;
        bringUp = false;
        showSteps(c, up);
        repaint();
    }

    void showSteps(RouteController c, boolean up) {
        boolean none = c == null || c.target() < 0;
        forScreens(screen -> screen.showRoute(none ? null : width -> steps(c, width, screen.view::focus, false),
            !none && up));
        sidebar.accept(none ? null : width -> steps(c, width, sidebarFocus, true));
    }

    /** Steps avoided for the current route only (lower case). */
    final Set<String> routeAvoid = ConcurrentHashMap.newKeySet();

    List<String> neverUsed() {
        return Arrays.stream(config.routeNeverUse().split("[\\r\\n]+")).map(String::trim).filter(line -> !line.isEmpty())
            .collect(toCollection(ArrayList::new));
    }

    Set<String> avoided(List<String> leftOut) {
        var all = new HashSet<String>(routeAvoid);
        neverUsed().forEach(name -> all.add(name.toLowerCase(ROOT)));
        leftOut.forEach(name -> all.add(name.toLowerCase(ROOT)));
        return all;
    }

    void avoid(String name) {
        routeAvoid.add(name.toLowerCase(ROOT));
        settingsChanged();
    }

    void never(String name) {
        var names = neverUsed();
        if (!names.contains(name)) {
            names.add(name);
            set("routeNeverUse", String.join("\n", names));
        }
    }

    void allow(String name) {
        var names = neverUsed();
        names.removeIf(n -> n.equalsIgnoreCase(name));
        set("routeNeverUse", String.join("\n", names));
    }

    boolean unreachable() {
        var c = controller;
        return c != null && unreachable(c.route());
    }

    /** With {@code roomy} (the sidebar): first a box with what to do now and next. */
    JComponent steps(RouteController c, int width, Consumer<WorldPoint> focus, boolean roomy) {
        Route route = effective(c);
        String status = route == null && explaining ? t("explaining") : c.status();
        Route left = ahead;
        int done = route != null && route == aheadOf && left != null ? route.steps.size() - left.steps.size() : 0;
        var panel = panel(route, c.target(), status, width, focus, this::clearRoute, this, current(), jump(), done);
        if (roomy && route != null && left != null && !left.steps.isEmpty()) {
            sidebarNow = nowBox(left, width, current(), jump());
            panel.add(sidebarNow, 1);
        }
        Running running = tour;
        if (running != null) {
            panel.add(tourHeader(running, width), 0);
        }
        if (handedOver != null) {
            panel.add(label(t("spDashed"), width, MEDIUM_GRAY_COLOR), 1);
        }
        var planned = state;
        if (route != null && route.has(Kind.SAIL) && planned != null) {
            // So a wrong dock choice can be told apart from a wrong level.
            String text = config.routeIgnoreLevels() ? t("rfIgnoring") : t("rfSailing", planned.sailingLevel);
            panel.add(label(text, width, MEDIUM_GRAY_COLOR), 1);
        }
        Tip hint = tip;
        if (hint != null && route != null && route == tipFor) {
            // Right under the "Now" box, else first under the headings.
            int at = sidebarNow != null && sidebarNow.getParent() == panel ? panel.getComponentZOrder(sidebarNow) + 1 : 1;
            panel.add(tipBox(hint.bring, hint.sooner, width), min(at, panel.getComponentCount()));
        }
        return panel;
    }

    void paintMap(Graphics2D g, MapView p) {
        if (p.map() != null && (p.map().id == BaseMap.SURFACE || p.map().id == BaseMap.FULL)) {
            yourHouse.paintHouse(g, p);
        }
        paintHandedOver(g, p);
        if (spPlanner()) {
            return;
        }
        Route route = ahead;
        for (var leg : laterLegs()) {
            paint(g, p, leg.getValue(), legColor(leg.getKey()), jump());
        }
        if (route != null) {
            paint(g, p, route, current(), jump());
        }
        long now = currentTimeMillis();
        // Passed dots in the leg's colour, as in the game.
        Color passed = current();
        for (var entry : fading.entrySet()) {
            int point = entry.getKey();
            float alpha = fadeAlpha(entry.getValue(), now);
            if (alpha > 0 && visible(p, point)) {
                g.setColor(alpha(passed, alpha * 200));
                g.fill(circle(x(p, point), y(p, point), dotRadius(p)));
            }
        }
    }

    static final double DESTINATION_SCALE = 1.8;

    /**
     * A big arrow above the destination, or at the view's edge pointing at it; on another map at the last of the route
     * this one draws (a way in). A click shows the destination.
     */
    void paintDestination(MapView view, Graphics2D g, MapView.Clicks clicks) {
        // Where the route goes (its end, else the spot asked for), or Shortest Path's target as the planner; -1 none.
        var handed = handedOver;
        var c = controller;
        Route route = ahead;
        int to = spPlanner() ? handed == null ? -1 : pack(handed)
            : route != null && route.end >= 0 ? route.end : c == null ? -1 : c.target();
        // The clue's spots and other plugins' icons, at the view's edge when out of it if they ask; one the route ends
        // by stands for the destination's arrow.
        for (var mark : marks) {
            var point = mark.getWorldPoint();
            var icon = mark.getImage();
            if (icon != null && view.shows(point)) {
                // Drawn over the map, not turned with it: where the turned map shows the spot.
                var on = view.asTurned(x(view, pack(point)), y(view, pack(point)));
                var spot = mark.isSnapToEdge() ? view.edge(on.getX(), on.getY()) : on;
                var anchor = mark.getImagePoint();
                int w = icon.getWidth(), h = icon.getHeight();
                int left = (int) spot.getX() - (anchor == null ? w / 2 : anchor.getX());
                int top = (int) spot.getY() - (anchor == null ? h / 2 : anchor.getY());
                to = to >= 0 && worldPoint(to).distanceTo(point) <= 2 ? -1 : to;
                g.drawImage(icon, left, top, null);
                clicks.add(new RoundRectangle2D.Double(left, top, w, h, 8, 8), () -> view.focus(point),
                    mark.getTooltip() != null ? mark.getTooltip() : mark.getName());
            }
        }
        int at = to;
        if (to >= 0 && !view.shows(worldPoint(to))) {
            at = -1;
            for (int i = route == null ? 0 : route.steps.size(); --i >= 0 && at < 0; ) {
                var points = route.steps.get(i).points;
                for (int j = points.length; --j >= 0 && at < 0; ) {
                    at = view.shows(worldPoint(points[j])) ? points[j] : -1;
                }
            }
        }
        if (at < 0) {
            return;
        }
        // Its tip just above the route's end dot, on the screen where the turned map shows the dot.
        var dot = view.asTurned(x(view, at), y(view, at));
        double x = dot.getX(), y = dot.getY() - 8 - 12 * DESTINATION_SCALE, angle = PI / 2;
        if (x < 0 || y < 0 || x > view.getWidth() || y > view.getHeight()) {
            var edge = view.edge(x, y);
            angle = atan2(y - edge.getY(), x - edge.getX());
            // Back in by what it is longer than the player's, so its tip stays in view.
            x = edge.getX() - cos(angle) * 12 * (DESTINATION_SCALE - 1);
            y = edge.getY() - sin(angle) * 12 * (DESTINATION_SCALE - 1);
        }
        g.setRenderingHint(KEY_ANTIALIASING, VALUE_ANTIALIAS_ON);
        PlayerMarker.pointer(g, view.text, x, y, angle, DESTINATION_SCALE, isSea(to) ? SEA_END : END, null);
        var point = worldPoint(to);
        double r = 13 * DESTINATION_SCALE;
        clicks.add(new RoundRectangle2D.Double(x - r, y - r, 2 * r, 2 * r, 8, 8), () -> view.focus(point),
            t("rfDestination"));
    }

    /** Whether a step is named on its tile in the game: not a teleport, which is cast anywhere (said over the head). */
    static boolean onItsTile(Step step) {
        return step.isJump() && step.kind != TELEPORT && step.name != null && !isSea(step.first())
            && (step.first() != step.last() || step.kind == BANK);
    }

    /** The tile a step is named on: a bank step's booth or chest, else where it starts. */
    static int namedAt(Step step) {
        return step.kind == BANK ? booth(step.first()) : step.first();
    }

    /** The step to take now: a teleport, or a transport or passage the player stands at; or null. */
    static Step nextAction(Route route, int player) {
        for (Step step : route.steps) {
            if (step.kind != Kind.WALK) {
                return step.kind == TELEPORT || step.kind == Kind.HOUSE || step.kind != Kind.SAIL
                    && step.kind != DISEMBARK && player >= 0 && step.first() >= 0
                    && distance(player, step.first()) <= 3 ? step : null;
            }
            if (step.points.length > 3) {
                return null;
            }
        }
        return null;
    }

    static final int OBSTACLE_AHEAD = 5;

    /** What to do now, such as "Chop-down Vines (bring an axe)", or null. */
    static String nextText(Route route, int player) {
        for (Step step : route.steps) {
            if (step.kind != Kind.WALK) {
                break;
            }
            for (var obstacle : step.obstacles) {
                for (int i = 0; i < step.points.length && i <= OBSTACLE_AHEAD; i++) {
                    if (step.points[i] == obstacle.at) {
                        return obstacle.text;
                    }
                }
            }
            if (step.points.length > OBSTACLE_AHEAD) {
                break;
            }
        }
        Step step = nextAction(route, player);
        return step == null ? null : describe(step);
    }

    /** The whole route {@code cut} is the start of ({@link #drawn}): what a teleport into the house is for comes after it. */
    Route whole(Route cut) {
        Route all = shown;
        return all != null && cut == drawn ? all : cut;
    }

    void paintNextAction(Graphics2D g, Route route, Player player) {
        int at = start;
        Step now = nextAction(route, at);
        // A teleport one walks to (a portal, the box, a fairy ring) is named on it, as HD World Markers names it; over
        // the head only what is cast anywhere, and obstacles on the way.
        Step jump = route.steps.stream().filter(Step::isJump).findFirst().orElse(null);
        boolean onIt = jump != null && jump.kind != TELEPORT && jump.first() >= 0
            && (jump.kind != Kind.HOUSE || HousePlan.contains(jump.first()));
        var view = client.getTopLevelWorldView();
        if (onIt && !hdTiles && view != null) {
            tag(g, yourHouse.local(view, namedAt(jump)), said(whole(route), jump, house.inside()),
                lacks(jump) ? CANNOT : jump(), 0);
        }
        String text = nextText(route, at);
        if (text == null || now != null && now == jump && onIt) {
            return;
        }
        if (now != null && text.equals(describe(now))) {
            // The step itself, not an obstacle on the way: into the house with what to use there.
            text = said(whole(route), now, house.inside());
        }
        Color color = now != null && lacks(now) ? CANNOT : jump();
        // On a boat ours: RuneLite places text over a head in the boat's own scene.
        var boat = player.getWorldView();
        if (hdTiles && hdTexts && (boat == null || boat.isTopLevel())) {
            // Drawn by HD World Markers, as the tiles' names are.
            headSaid = true;
            head(text, color, player);
            return;
        }
        tag(g, player.getLocalLocation(), text, color, player.getLogicalHeight() + 60);
    }

    /** Whether this frame's text over the head was sent to HD World Markers. */
    boolean headSaid;

    /** Whether HD World Markers draws the text over the head ({@link #drawsTexts}); else it is ours. */
    volatile boolean hdTexts;

    /**
     * Whether the HD World Markers on draws the texts sent to it ({@code texts}): since its 1.4.0, the release that also
     * brought its Interact highlight settings ({@code interactStyles}), read from its settings as RuneLite lists them. An
     * older one would leave the text over the head out. Temporary, until players have updated it.
     */
    boolean drawsTexts() {
        return pluginManager.getPlugins().stream().filter(p -> MarkersBridge.PLUGIN_NAME.equals(p.getName())).findFirst()
            .map(pluginManager::getPluginConfigProxy).map(configManager::getConfigDescriptor)
            .map(d -> d.getItems().stream().anyMatch(item -> "interactStyles".equals(item.getItem().keyName())))
            .orElse(false);
    }

    void tag(Graphics2D g, LocalPoint at, String text, Color color, int height) {
        // The font first: the text is centred with it.
        g.setFont(FontManager.getRunescapeSmallFont());
        Point spot = at == null ? null : getCanvasTextLocation(client, g, at, text, height);
        if (spot != null) {
            TextSprites.shadowed(g, text, spot.getX(), spot.getY(), color);
        }
    }

    static final int MINIMAP_ALPHA = 150;

    /**
     * Where the player is for the overlays, as the planner has it: in one's house its plan's tile, in an instance
     * copying a place that place's, on a boat its sea block. Client thread.
     */
    int near() {
        int at = start;
        return at >= 0 ? at : pack(client.getLocalPlayer().getWorldLocation());
    }

    /** The colour last made: tiles side by side share it, so a frame does not make a Color for every tile. */
    static final class Tint {
        Color base;
        int alpha = -1;
        Color made;

        Color of(Color c, float a) {
            int value = max(0, min(255, round(a)));
            if (c != base || value != alpha) {
                base = c;
                alpha = value;
                made = alpha(c, a);
            }
            return made;
        }
    }

    interface TileSink {
        void tile(int point, Color color, float strength);
    }

    /**
     * The ground tiles of the overlays and HD World Markers within {@code reach} of {@code me} (anywhere when it is not
     * known), each once: the route (fading in), then passed tiles still to be seen, then the later parts of a custom
     * route. Asked every frame: far tiles are left out before anything is looked up.
     */
    void eachTile(Route route, int me, int reach, TileSink sink) {
        var taken = new HashSet<Integer>();
        IntPredicate near = point -> (me < 0 || distance(point, me) <= reach) && taken.add(point);
        long now = currentTimeMillis();
        eachWalk(route, current(), (points, walk) -> {
            for (int point : points) {
                if (near.test(point)) {
                    sink.tile(point, walk, fadeIn(point, now));
                }
            }
        });
        for (var entry : fading.entrySet()) {
            float alpha = fadeAlpha(entry.getValue(), now);
            if (alpha > 0.05f && near.test(entry.getKey())) {
                sink.tile(entry.getKey(), current(), alpha);
            }
        }
        // The nearer part wins where they cross.
        var later = laterLegs();
        for (int i = later.size() - 1; i >= 0; i--) {
            Color color = legColor(later.get(i).getKey());
            eachWalked(later.get(i).getValue(), point -> {
                if (near.test(point)) {
                    sink.tile(point, color, 1);
                }
            });
        }
    }

    /**
     * Each walk of the later parts of a custom route, then of the route, with its colour, as {@link #eachTile} does
     * (no fades): the points in walking order.
     */
    void eachLine(Route route, BiConsumer<int[], Color> sink) {
        for (var leg : laterLegs()) {
            Color color = legColor(leg.getKey());
            for (Step step : leg.getValue().steps) {
                if (step.kind == Kind.WALK) {
                    sink.accept(step.points, color);
                }
            }
        }
        eachWalk(route, current(), sink);
        long now = currentTimeMillis();
        for (var line : gone) {
            float alpha = fadeAlpha(line.since, now);
            if (alpha > 0.02f) {
                sink.accept(line.points, alpha(line.color, line.color.getAlpha() * alpha));
            }
        }
    }

    /** Each walk of the route in {@code normal}, red from the first step the player cannot take yet. */
    static void eachWalk(Route route, Color normal, BiConsumer<int[], Color> sink) {
        int blocked = blockedFrom(route);
        List<Step> steps = route == null ? List.of() : route.steps;
        for (int index = 0; index < steps.size(); index++) {
            Step step = steps.get(index);
            if (!step.isJump()) {
                sink.accept(tilesOf(step), blocked >= 0 && index >= blocked ? CANNOT : normal);
            }
        }
    }

    /** A walk's tiles; a sail's as water tiles ({@link #sailTiles}). */
    static int[] tilesOf(Step step) {
        return step.kind == Kind.SAIL ? keptSailTiles(step.points) : step.points;
    }

    /** Sails' blocks and tiles as last drawn whole, the newest first. */
    static final java.util.Deque<int[][]> SAILS = new ArrayDeque<>();
    static final int SAILS_KEPT = 8;

    /**
     * A sail's tiles; the rest of one drawn before (the boat on its way, its blocks the end of that one's) that one's
     * tiles from the one nearest the boat's block on: drawn again from there its line would lie elsewhere, the tiles of
     * both showing as the old fade.
     */
    static int[] keptSailTiles(int[] blocks) {
        synchronized (SAILS) {
            for (var sail : SAILS) {
                int[] whole = sail[0], tiles = sail[1];
                int skip = whole.length - blocks.length;
                if (skip >= 0 && blocks.length > 0
                    && Arrays.equals(whole, skip, whole.length, blocks, 0, blocks.length)) {
                    int from = 0;
                    for (int k = 1; k < tiles.length; k++) {
                        if (distance(tiles[k], blocks[0]) < distance(tiles[from], blocks[0])) {
                            from = k;
                        }
                    }
                    return Arrays.copyOfRange(tiles, from, tiles.length);
                }
            }
            int[] tiles = sailTiles(blocks);
            SAILS.addFirst(new int[][]{blocks, tiles});
            if (SAILS.size() > SAILS_KEPT) {
                SAILS.removeLast();
            }
            return tiles;
        }
    }

    /**
     * A sail's way as tiles on the water, so the ground and the minimap show it as they show a walk: its 4×4 sea
     * blocks' centres joined by straight lines, each tile next to the one before.
     */
    static int[] sailTiles(int[] blocks) {
        // A straight sail's blocks step half a block aside where its line falls between them: drawn as the line itself.
        int[] kept = new int[blocks.length];
        int n = 0;
        for (int i = 0, j; i < blocks.length; i = j) {
            kept[n++] = blocks[i];
            for (j = i + 1; j + 1 < blocks.length && straight(blocks, i, j + 1); j++) {
            }
        }
        blocks = Arrays.copyOf(kept, n);
        int count = blocks.length == 0 ? 0 : 1;
        for (int i = 1; i < blocks.length; i++) {
            count += distance(blocks[i - 1], blocks[i]);
        }
        int[] tiles = new int[count];
        n = 0;
        for (int i = 0; i < blocks.length; i++) {
            int from = blocks[i == 0 ? 0 : i - 1], to = blocks[i], steps = distance(from, to);
            int ax = x(from), ay = y(from), dx = x(to) - ax, dy = y(to) - ay;
            for (int k = i == 0 ? 0 : 1; k <= steps && n < count; k++) {
                double t = steps == 0 ? 0 : k / (double) steps;
                tiles[n++] = Tiles.pack(ax + (int) round(dx * t), ay + (int) round(dy * t), z(to));
            }
        }
        return tiles;
    }

    /** Whether blocks i to k lie within half a block of the line from i to k. */
    static boolean straight(int[] blocks, int i, int k) {
        double ax = x(blocks[i]), ay = y(blocks[i]), dx = x(blocks[k]) - ax, dy = y(blocks[k]) - ay;
        return IntStream.range(i + 1, k).allMatch(m -> abs((x(blocks[m]) - ax) * dy - (y(blocks[m]) - ay) * dx)
            <= hypot(dx, dy) * (CELL / 2.0 + 0.1));
    }

    /** A walk of a route just gone (arrived, cleared), fading as it was drawn, its arrows where they were. */
    @RequiredArgsConstructor
    static final class Gone {
        final int[] points;
        final Color color;
        final long since;
    }

    final List<Gone> gone = new CopyOnWriteArrayList<>();

    /**
     * The passed tiles still fading, as pieces of line: between two of them next to each other, and from one to the
     * route's first tile; with how visible each is (the fainter end's), as tiles fade.
     */
    void eachFade(Route route, long now, BiConsumer<int[], Float> sink) {
        markArrows(route);
        Step walk = route == null ? null : route.first(Kind.WALK);
        int first = walk == null ? -1 : walk.first();
        // Not the tiles of a route gone, which fade as whole lines.
        var whole = new HashSet<Integer>();
        if (!gone.isEmpty()) {
            gone.forEach(line -> Arrays.stream(line.points).forEach(whole::add));
        }
        for (var entry : fading.entrySet()) {
            int p = entry.getKey();
            float alpha = whole.contains(p) ? 0 : fadeAlpha(entry.getValue(), now);
            for (int dx = -1; dx <= 1 && alpha > 0.05f; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    int q = Tiles.pack(x(p) + dx, y(p) + dy, z(p));
                    Long other = q == first ? null : fading.get(q);
                    // A passed arrow fades with its piece, pointing as it did: that piece goes its way round.
                    boolean arrow = arrowsTo.getOrDefault(p, -1) == q;
                    if ((dx | dy) != 0 && (q == first || other != null) && (arrow
                        || arrowsTo.getOrDefault(q, -1) != p && (q == first || q > p))) {
                        sink.accept(new int[]{p, q, arrow ? 1 : 0},
                            other == null ? alpha : min(alpha, fadeAlpha(other, now)));
                    }
                }
            }
        }
    }

    /** With arrows: each arrow's tile to the tile it points at, the route's and those passed while they fade. */
    final Map<Integer, Integer> arrowsTo = new ConcurrentHashMap<>();

    /** The route and arrow setting {@link #arrowsTo} was last worked out for, and how many tiles faded then. */
    Route arrowsOf;
    boolean arrowsOn;
    int arrowsFading;

    /**
     * The route's arrows (each walk's every {@link #ARROW_EVERY}th tile from its far end), keeping those fading: worked
     * out again only for another route, the setting, or tiles done fading (each frame asks).
     */
    void markArrows(Route route) {
        boolean on = arrows();
        if (route == arrowsOf && on == arrowsOn && fading.size() >= arrowsFading) {
            arrowsFading = fading.size();
            return;
        }
        arrowsOf = route;
        arrowsOn = on;
        arrowsFading = fading.size();
        arrowsTo.keySet().retainAll(on ? fading.keySet() : Set.of());
        for (Step step : on && route != null ? route.steps : List.<Step>of()) {
            int[] p = step.kind == Kind.WALK ? step.points : new int[0];
            for (int i = 0; i + 1 < p.length; i++) {
                if (arrowAt(p, i)) {
                    arrowsTo.put(p[i], p[i + 1]);
                }
            }
        }
    }

    /**
     * The route as lines through the tiles' centres on the ground, as Shortest Path's Lines: broken where a step is not
     * to a neighbour, off this floor or off the scene.
     */
    void paintLines(Graphics2D g, Route route, WorldView view, int plane) {
        int me = near();
        boolean arrows = arrows();
        g.setStroke(new BasicStroke(config.routeLineWidth(), CAP_ROUND, JOIN_ROUND));
        eachLine(route, (points, color) -> {
            var path = new Path2D.Float();
            var heads = new ArrayList<Runnable>();
            int previous = -1;
            for (int i = 0; i < points.length; i++) {
                int point = points[i];
                Point at = canvas(view, point, plane, me);
                if (at != null && previous >= 0 && distance(previous, point) == 1) {
                    path.lineTo(at.getX(), at.getY());
                } else if (at != null) {
                    path.moveTo(at.getX(), at.getY());
                }
                previous = at == null ? -1 : point;
                // Counted from the walk's far end, which stays as the player walks it: the arrows stay put.
                if (arrows && at != null && arrowAt(points, i)) {
                    var on = yourHouse.local(view, point);
                    int dx = x(points[i + 1]) - x(point), dy = y(points[i + 1]) - y(point);
                    heads.add(() -> arrow(g, on, dx, dy, plane));
                }
            }
            g.setColor(color);
            g.draw(path);
            heads.forEach(Runnable::run);
        });
        Color walked = current();
        eachFade(route, currentTimeMillis(), (piece, alpha) -> {
            Point a = canvas(view, piece[0], plane, me), b = canvas(view, piece[1], plane, me);
            if (a != null && b != null) {
                g.setColor(alpha(walked, walked.getAlpha() * alpha));
                g.drawLine(a.getX(), a.getY(), b.getX(), b.getY());
                if (piece[2] == 1) {
                    arrow(g, yourHouse.local(view, piece[0]), x(piece[1]) - x(piece[0]),
                        y(piece[1]) - y(piece[0]), plane);
                }
            }
        });
    }

    /** Whether a walk's arrowhead stands at {@code points[i]}, toward the next point (a neighbour on its floor). */
    static boolean arrowAt(int[] points, int i) {
        return i + 1 < points.length && (points.length - 1 - i) % ARROW_EVERY == ARROW_EVERY / 2
            && distance(points[i], points[i + 1]) == 1 && z(points[i]) == z(points[i + 1]);
    }

    /** A route tile's centre on the canvas: on this floor, within the extended scene; else null. */
    Point canvas(WorldView view, int point, int plane, int me) {
        var local = z(point) != plane || distance(point, me) > Constants.EXTENDED_SCENE_SIZE ? null
            : yourHouse.local(view, point);
        return local == null ? null : localToCanvas(client, local, plane);
    }

    /** With Path style Lines with arrows: an arrowhead every this many tiles, as HD World Markers draws them. */
    static final int ARROW_EVERY = 3;
    /** An arrowhead's size in local units (128 a tile), as HD World Markers draws it: tip ahead, back behind, half width. */
    static final int TIP = 26, BACK = 22, HALF = 20;

    /** A small filled arrowhead on the ground around {@code at}, pointing (dx, dy). */
    void arrow(Graphics2D g, LocalPoint at, int dx, int dy, int plane) {
        double length = hypot(dx, dy), ux = dx / length, uy = dy / length;
        double[][] corners = {{ux * TIP, uy * TIP}, {-ux * BACK - uy * HALF, -uy * BACK + ux * HALF},
            {-ux * BACK + uy * HALF, -uy * BACK - ux * HALF}};
        Polygon head = new Polygon();
        for (var corner : corners) {
            Point p = localToCanvas(client, new LocalPoint(at.getX() + (int) round(corner[0]),
                at.getY() + (int) round(corner[1]), at.getWorldView()), plane);
            if (p == null) {
                return;
            }
            head.addPoint(p.getX(), p.getY());
        }
        g.fill(head);
    }

    /** Dots on the minimap: above the widgets, or the minimap would cover them. */
    final class MinimapOverlay extends Overlay {
        final Tint tint = new Tint();

        MinimapOverlay() {
            setPosition(OverlayPosition.DYNAMIC);
            setLayer(OverlayLayer.ABOVE_WIDGETS);
        }

        public Dimension render(Graphics2D g) {
            Route route = drawn;
            if (route == null && fading.isEmpty()) {
                return null;
            }
            var view = client.getTopLevelWorldView();
            if (view == null || view.isInstance() && !house.own() && !inCopy || client.getLocalPlayer() == null
                || !config.routeMinimap()) {
                return null;
            }
            int plane = view.getPlane();
            // The minimap shows 20 tiles around the player (Perspective.localToMinimap).
            eachTile(route, near(), 20, (point, color, strength) -> {
                var local = isSea(point) || z(point) != plane ? null : yourHouse.local(view, point);
                Point mini = local == null ? null : localToMinimap(client, local);
                if (mini != null) {
                    g.setColor(tint.of(color, MINIMAP_ALPHA * strength));
                    g.fill(circle(mini.getX(), mini.getY(), 1.25));
                }
            });
            return null;
        }
    }

    /** The tiles round the player's tile for the HD minimap, drawn on Swing's thread into one picture. */
    @NoArgsConstructor
    @AllArgsConstructor
    static final class MiniSnap {
        /**
         * Game x, y, plane, zoom × 100, tick, r: the picture covers r tiles each way round (x, y), at the minimap's own
         * pixels a tile (its zoom).
         */
        int[] k;
        /** Null when no tile is loaded yet. */
        BufferedImage image;
        boolean missing;
    }

    /** Game x and minus game y in tiles to the canvas as Perspective.localToMinimap turns them (angle 0-16383). */
    static AffineTransform toMinimap(double cx, double cy, double zoom, int angle, double fx, double fy) {
        var t = AffineTransform.getTranslateInstance(cx, cy);
        t.rotate(angle * PI / 8192);
        t.scale(zoom, zoom);
        t.translate(-fx, fy);
        return t;
    }

    /** Per boat's world view, for the world map: the tick its faces were taken at and the faces. Client thread. */
    final Map<Integer, Map.Entry<Integer, List<double[]>>> mapFaces = new HashMap<>();

    /**
     * The boats in the scene as the world map draws them, as the minimap shows them (one's own at its dock too): their
     * models seen from above as the game's minimap draws them (taken again every 5 ticks) and where their corners lie
     * in the world. Client thread.
     */
    List<MapView.Boat> boatsOnMap(int tick) {
        var top = client.getTopLevelWorldView();
        var boats = new ArrayList<MapView.Boat>();
        for (var boat : top == null ? List.<WorldEntity>of() : top.worldEntities()) {
            var deck = boat.getWorldView();
            if (deck == null || deck.getScene() == null) {
                continue;
            }
            var faces = mapFaces.get(deck.getId());
            if (faces == null || tick - faces.getKey() >= 5 || tick < faces.getKey()) {
                faces = Map.entry(tick, unmodifiableList(hdMinimap.deckFaces(deck)));
                mapFaces.put(deck.getId(), faces);
            }
            int w = deck.getSizeX() * 128, h = deck.getSizeY() * 128;
            double[] corners = new double[6];
            boolean placed = true;
            for (int k = 0; k < 3 && placed; k++) {
                var p = boat.transformToMainWorld(new LocalPoint(k == 1 ? w : 0, k == 2 ? h : 0, deck.getId()));
                placed = p != null;
                if (placed) {
                    corners[2 * k] = top.getBaseX() + p.getX() / 128.0;
                    corners[2 * k + 1] = top.getBaseY() + p.getY() / 128.0;
                }
            }
            if (placed) {
                boats.add(new MapView.Boat(faces.getValue(), deck.getSizeX(), deck.getSizeY(), corners));
            }
        }
        mapFaces.keySet().removeIf(id -> top == null || top.worldEntities().byIndex(id) == null);
        return boats;
    }

    /**
     * Our tiles inside the game's minimap (setting hdMinimap), centred, zoomed and turned as the game draws it, with its
     * dots drawn again on top. Draws only: clicks go to the game's minimap below.
     */
    final class HdMinimap extends Overlay {
        /** Read on Swing's thread; the client thread only draws it. */
        volatile MiniSnap snap;
        volatile boolean asking;
        /** The minimap's shape for each draw area sprite (fixed, resizable). */
        final Map<Integer, Shape> masks = new HashMap<>();
        /** Each dot sprite as a picture, made once (the Minimap plugin's colours swap in new sprites). */
        final Map<SpritePixels, BufferedImage> dots = new HashMap<>();
        BufferedImage flag;
        int foot;
        boolean zoomBack;
        double savedZoom;
        /**
         * The game's walk from walkFrom to walkTo (tiles, clicked), ending at walkEnd; worked out again when either
         * changes. Client thread.
         */
        int walkFrom = -1, walkTo = -1, walkEnd = -1;
        int[] walkPath;

        HdMinimap() {
            setPosition(OverlayPosition.DYNAMIC);
            setLayer(OverlayLayer.MANUAL);
            // The compass's click layer is drawn right after the minimap and before the orbs.
            drawAfterLayer(InterfaceID.Toplevel.COMPASSCLICK);
            drawAfterLayer(InterfaceID.ToplevelOsrsStretch.COMPASSCLICK);
            drawAfterLayer(InterfaceID.ToplevelPreEoc.COMPASSCLICK);
        }

        public Dimension render(Graphics2D g) {
            var view = client.getTopLevelWorldView();
            var focus = client.getCameraFocusEntity();
            var area = client.getWidget(!client.isResized() ? InterfaceID.Toplevel.MINIMAP
                : client.getVarbitValue(RESIZABLE_STONE_ARRANGEMENT) == 1 ? InterfaceID.ToplevelPreEoc.MINIMAP
                : InterfaceID.ToplevelOsrsStretch.MINIMAP);
            if (!config.hdMinimap() || client.getGameState() != GameState.LOGGED_IN || client.getLocalPlayer() == null
                || view == null || view.isInstance() || client.getVarbitValue(MINIMAP_STATE) != 0 || area == null
                || area.isHidden() || focus == null || focus.getCameraFocus() == null) {
                return null;
            }
            // On a boat the camera follows it: its place in the main world, where the game's minimap centres.
            var at = focus.getCameraFocus();
            var boat = at.getWorldView() == 0 ? null : view.worldEntities().byIndex(focus.getWorldView().getId());
            at = boat == null ? at : boat.transformToMainWorld(at);
            double zoom = client.getMinimapZoom(), fx = view.getBaseX() + at.getX() / 128.0;
            double fy = view.getBaseY() + at.getY() / 128.0;
            // The minimap's zoom kept over restarts: put back at the plugin's first frame, saved when it changes.
            if (!zoomBack) {
                zoomBack = true;
                savedZoom = config.minimapZoom();
                if (savedZoom > 0) {
                    client.setMinimapZoom(savedZoom);
                }
            } else if (zoom != savedZoom) {
                savedZoom = zoom;
                set("minimapZoom", zoom);
            }
            // As the game's own minimap: one picture at 4 pixels a tile, which its zoom and turn only scale and turn; at
            // 2 under zoom 4 ({@link #halve}), so it is never drawn smaller: shrunk unsmoothed, every other row of pixels
            // went, and walls one pixel wide fell into dots. 47 tiles each way reach past the minimap's edge (90 pixels
            // from its middle) at the smallest zoom, 2.
            int[] want = {(int) fx, (int) fy, view.getPlane(), zoom < 4 ? 200 : 400, client.getTickCount(), 47};
            var s = snap;
            if ((s == null || !Arrays.equals(s.k, 0, 4, want, 0, 4) || s.missing && s.k[4] != want[4]) && !asking) {
                asking = true;
                invokeLater(() -> {
                    try {
                        snap = read(want);
                    } finally {
                        asking = false;
                    }
                });
            }
            var mask = masks.computeIfAbsent(area.getSpriteId(), this::mask);
            if (s == null || s.image == null || s.k[2] != want[2] || mask == null) {
                return null;
            }
            var loc = area.getCanvasLocation();
            int cx = loc.getX() + area.getWidth() / 2, cy = loc.getY() + area.getHeight() / 2;
            var d = (Graphics2D) g.create();
            d.clip(AffineTransform.getTranslateInstance(loc.getX(), loc.getY()).createTransformedShape(mask));
            var t = toMinimap(cx, cy, zoom, client.getCameraYawTarget() & 16383, fx, fy);
            // On whole pixels: the picture's corner put on a screen pixel, so the map and its icons move together a pixel at
            // a time while walking instead of each rounding its own way (turned, unsmoothed pixels still crawl).
            var corner = t.transform(new Point2D.Double(s.k[0] - s.k[5], -s.k[1] - s.k[5]), null);
            t.preConcatenate(AffineTransform.getTranslateInstance(round(corner.getX()) - corner.getX(),
                round(corner.getY()) - corner.getY()));
            // Scaled by the zoom and turned with each pixel taken as it is (no smoothing): on the game's own pixels.
            var m = (Graphics2D) d.create();
            m.transform(t);
            m.setRenderingHint(KEY_INTERPOLATION, VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            double zs = s.k[3] / 100.0;
            m.drawImage(s.image, new AffineTransform(1 / zs, 0, 0, 1 / zs, s.k[0] - s.k[5], -s.k[1] - s.k[5]), null);
            boats(m, view, want[4], zoom);
            m.dispose();
            // The game's map icons round the player, at their own size, on whole pixels as the game draws them.
            for (var icon : icons) {
                var sprite = GameIcons.element(icon.element);
                var spot = icon.drawn;
                if (sprite != null && spot.getPlane() == want[2] && abs(spot.getX() - fx) < 40 && abs(spot.getY() - fy) < 40) {
                    var c = t.transform(new Point2D.Double(spot.getX() + 0.5, -spot.getY() - 0.5), null);
                    d.drawImage(sprite, (int) round(c.getX() - sprite.getWidth() / 2.0),
                        (int) round(c.getY() - sprite.getHeight() / 2.0), null);
                }
            }
            // Where one walks to: the game's walk there from here by the scene's own collision (at once, doors as they
            // stand), under the dots; its flag at the walk's end on top of them. A spot nobody stands on: as the game, the
            // nearest one reached round it (rings out to 10 tiles).
            var dest = client.getLocalDestinationLocation();
            int bx = view.getBaseX(), by = view.getBaseY();
            int to = dest == null ? -1 : Tiles.pack(bx + dest.getSceneX(), by + dest.getSceneY(), want[2]);
            int here = Tiles.pack(want[0], want[1], want[2]);
            var maps = view.getCollisionMaps();
            if (to >= 0 && maps != null && (here != walkFrom || to != walkTo)) {
                int[][] flags = maps[want[2]].getFlags();
                GameWalk.Shut shut = (x, y, z, dx, dy) -> !step(flags, x - bx, y - by, dx, dy);
                int[] path = to == walkTo && walkEnd >= 0 ? GameWalk.path(null, shut, here, new int[]{walkEnd}) : null;
                for (int r = 0; path == null && r <= 10; r++) {
                    path = GameWalk.path(null, shut, here, ring(to, r));
                }
                walkFrom = here;
                walkTo = to;
                walkPath = path;
                walkEnd = path == null ? -1 : path[path.length - 1];
            }
            if (to >= 0 && walkPath != null && to == walkTo && config.hdMinimapPath()) {
                var line = new Path2D.Double();
                line.moveTo(fx, -fy);
                for (int i = 1; i < walkPath.length; i++) {
                    line.lineTo(x(walkPath[i]) + 0.5, -y(walkPath[i]) - 0.5);
                }
                d.setStroke(new BasicStroke(1.5f));
                d.setColor(walk());
                d.draw(t.createTransformedShape(line));
            }
            // The game's own dots, which our tiles cover: ground items, NPCs, players, and oneself in the middle.
            for (var row : view.getScene().getTiles()[view.getPlane()]) {
                for (var tile : row) {
                    if (tile != null && tile.getItemLayer() != null) {
                        dot(d, 0, tile.getLocalLocation());
                    }
                }
            }
            for (var npc : view.npcs()) {
                if (npc.getTransformedComposition() != null && npc.getTransformedComposition().isMinimapVisible()) {
                    dot(d, 1, npc.getLocalLocation());
                }
            }
            for (var other : view.players()) {
                if (other != client.getLocalPlayer()) {
                    dot(d, 2, other.getLocalLocation());
                }
            }
            if (flag == null) {
                var sprites = client.getSprites(client.getIndexSprites(), SpriteID.MAPMARKER, 0);
                flag = sprites == null ? null : sprites[0].toBufferedImage();
                // Its pole's foot: the lowest pixel drawn (the last one, row by row), put where the walk ends.
                for (int i = 0; flag != null && i < flag.getWidth() * flag.getHeight(); i++) {
                    foot = flag.getRGB(i % flag.getWidth(), i / flag.getWidth()) >>> 24 != 0 ? i : foot;
                }
            }
            int end = walkPath != null && to == walkTo ? walkEnd : to;
            if (end >= 0 && flag != null) {
                var c = t.transform(new Point2D.Double(x(end) + 0.5, -y(end) - 0.5), null);
                d.drawImage(flag, (int) round(c.getX()) - foot % flag.getWidth(), (int) round(c.getY()) - foot / flag.getWidth(),
                    null);
            }
            d.setColor(Color.WHITE);
            d.fillRect(cx - 1, cy - 1, 3, 3);
            d.dispose();
            return null;
        }

        /** A step the loaded scene's collision allows, in scene tiles: the game's own walls, doors as they stand now. */
        boolean step(int[][] f, int x, int y, int dx, int dy) {
            if (dx != 0 && dy != 0) {
                return step(f, x, y, dx, 0) && step(f, x, y, 0, dy) && step(f, x + dx, y, 0, dy) && step(f, x, y + dy, dx, 0);
            }
            int bx = x + dx, by = y + dy;
            if (min(min(x, y), min(bx, by)) < 0 || max(max(x, y), max(bx, by)) >= f.length) {
                return false;
            }
            // No wall on the side left by, none on the side come in by, and the tile entered not blocked as a whole.
            int out = dx > 0 ? BLOCK_MOVEMENT_EAST : dx < 0 ? BLOCK_MOVEMENT_WEST : dy > 0 ? BLOCK_MOVEMENT_NORTH
                : BLOCK_MOVEMENT_SOUTH;
            int in = dx > 0 ? BLOCK_MOVEMENT_WEST : dx < 0 ? BLOCK_MOVEMENT_EAST : dy > 0 ? BLOCK_MOVEMENT_SOUTH
                : BLOCK_MOVEMENT_NORTH;
            return (f[x][y] & out) == 0 && (f[bx][by] & (in | BLOCK_MOVEMENT_FULL)) == 0;
        }

        /** The tiles {@code r} tiles round {@code at} (a square ring; {@code at} itself at 0). */
        int[] ring(int at, int r) {
            int n = 2 * r + 1;
            return IntStream.range(0, n * n).filter(i -> max(abs(i % n - r), abs(i / n - r)) == r)
                .map(i -> Tiles.pack(x(at) + i % n - r, y(at) + i / n - r, z(at))).toArray();
        }

        /** Each boat's deck as the game's minimap draws it, by its world view: made again every 5 ticks, or at another zoom. */
        final Map<Integer, MiniSnap> decks = new HashMap<>();

        /**
         * The boats over the picture ({@code g}: its transform), each its deck as the game's minimap draws it, turned and
         * moved with it as the game draws them. Client thread.
         */
        void boats(Graphics2D g, WorldView view, int tick, double zoom) {
            var smooth = (Graphics2D) g.create();
            smooth.setRenderingHint(KEY_INTERPOLATION, VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            // At the minimap's own pixels a tile (rounded up), so turning it unsmoothed loses next to nothing.
            int scale = (int) ceil(zoom);
            for (var boat : view.worldEntities()) {
                var deck = boat.getWorldView();
                if (deck == null || deck.getScene() == null) {
                    continue;
                }
                int w = deck.getSizeX(), h = deck.getSizeY();
                var made = decks.get(deck.getId());
                if (made == null || tick - made.k[0] >= 5 || tick < made.k[0] || made.k[1] != scale) {
                    decks.put(deck.getId(), made = new MiniSnap(new int[]{tick, scale}, deck(deck, scale), false));
                }
                // The picture's corners on the map: its south-west (the scene's local 0, 0), south-east and north-west.
                Point2D[] at = new Point2D[3];
                for (int k = 0; k < 3; k++) {
                    var p = boat.transformToMainWorld(new LocalPoint(k == 1 ? w * 128 : 0, k == 2 ? h * 128 : 0, deck.getId()));
                    at[k] = new Point2D.Double(view.getBaseX() + p.getX() / 128.0, -view.getBaseY() - p.getY() / 128.0);
                }
                double pw = made.image.getWidth(), ph = made.image.getHeight();
                smooth.drawImage(made.image, new AffineTransform((at[1].getX() - at[0].getX()) / pw,
                    (at[1].getY() - at[0].getY()) / pw, (at[0].getX() - at[2].getX()) / ph, (at[0].getY() - at[2].getY()) / ph,
                    at[2].getX(), at[2].getY()), null);
            }
            smooth.dispose();
            decks.keySet().removeIf(id -> view.worldEntities().byIndex(id) == null);
        }

        /**
         * A boat at {@code scale} pixels a tile, north up, as the game's minimap shows one: its models seen from above
         * (the hull, masts and sails; not the deck's floor tiles, which stick out past the hull), unsmoothed, with a dark
         * edge round them. Client thread.
         */
        BufferedImage deck(WorldView deck, int scale) {
            return MapView.Boat.picture(deckFaces(deck), deck.getSizeX(), deck.getSizeY(), scale);
        }

        /**
         * The boat's models' faces seen from above, north up, in the deck's local units (128 a tile), the lower first:
         * per face its corners, how high it lies (floor, then height) and its colour. Client thread.
         */
        List<double[]> deckFaces(WorldView deck) {
            var textures = client.getTextureProvider();
            double brightness = textures == null ? 0.8 : textures.getBrightness();
            int north = deck.getSizeY() * 128;
            // Per face: its corners on the picture, how high it lies (floor, then height) and its colour.
            List<double[]> faces = new ArrayList<>();
            for (var floor : deck.getScene().getTiles()) {
                for (var row : floor) {
                    for (var tile : row) {
                        if (tile == null) {
                            continue;
                        }
                        int z = tile.getPlane();
                        for (var object : tile.getGameObjects()) {
                            if (object != null && object.getSceneMinLocation().equals(tile.getSceneLocation())) {
                                faces(faces, object.getRenderable(), object.getLocalLocation(), object.getModelOrientation(),
                                    z, north, textures, brightness);
                            }
                        }
                        var wall = tile.getWallObject();
                        if (wall != null) {
                            faces(faces, wall.getRenderable1(), wall.getLocalLocation(), 0, z, north, textures, brightness);
                            faces(faces, wall.getRenderable2(), wall.getLocalLocation(), 0, z, north, textures, brightness);
                        }
                        var ground = tile.getGroundObject();
                        if (ground != null) {
                            faces(faces, ground.getRenderable(), ground.getLocalLocation(), 0, z, north, textures, brightness);
                        }
                        var decoration = tile.getDecorativeObject();
                        if (decoration != null) {
                            faces(faces, decoration.getRenderable(), decoration.getLocalLocation(), 0, z, north, textures,
                                brightness);
                        }
                    }
                }
            }
            faces.sort(Comparator.comparingDouble(f -> f[6]));
            return faces;
        }

        /** A model's faces into {@code faces}, turned by {@code orientation} (2048 a turn) and placed at {@code at}. */
        void faces(List<double[]> faces, Renderable renderable, LocalPoint at, int orientation, int floor, int north,
            TextureProvider textures, double brightness) {
            var model = renderable == null ? null : renderable instanceof Model ? (Model) renderable : renderable.getModel();
            if (model == null || at == null) {
                return;
            }
            float[] vx = model.getVerticesX(), vy = model.getVerticesY(), vz = model.getVerticesZ();
            int[] a = model.getFaceIndices1(), b = model.getFaceIndices2(), c = model.getFaceIndices3();
            int[] colors = model.getFaceColors1(), middle = model.getFaceColors2(), last = model.getFaceColors3();
            short[] texture = model.getFaceTextures();
            byte[] see = model.getFaceTransparencies();
            double sin = sin(orientation * PI / 1024), cos = cos(orientation * PI / 1024);
            for (int f = 0; f < model.getFaceCount(); f++) {
                int alpha = see == null ? 255 : 255 - (see[f] & 0xff);
                int[] corners = {a[f], b[f], c[f]};
                double height = -(vy[a[f]] + vy[b[f]] + vy[c[f]]) / 3;
                // Masts, sails and rigging (higher than the railings) left out: from above they covered the hull.
                if (last[f] == -2 || alpha == 0 || colors[f] == 0 || height > 120) {
                    continue;
                }
                int rgb;
                if (texture != null && texture[f] != -1 && textures != null) {
                    // Textured: its pixels' mean colour, as light as the face is lit (its colour is then a lightness).
                    rgb = shade(average(textures, texture[f]), colors[f] & 127);
                } else {
                    // The mean of its corners' colours (one colour when the third is -1).
                    rgb = last[f] == -1 ? rgb(colors[f], brightness)
                        : mean(rgb(colors[f], brightness), rgb(middle[f], brightness), rgb(last[f], brightness));
                }
                var face = new double[8];
                for (int k = 0; k < 3; k++) {
                    int v = corners[k];
                    // The game turns a model so: x' = z sin + x cos, z' = z cos - x sin; its y grows downwards.
                    face[2 * k] = at.getX() + vz[v] * sin + vx[v] * cos;
                    face[2 * k + 1] = north - (at.getY() + vz[v] * cos - vx[v] * sin);
                }
                face[6] = floor * 1e6 + height;
                face[7] = (double) (alpha << 24 | rgb);
                faces.add(face);
            }
        }

        /** The mean of three colours. */
        int mean(int a, int b, int c) {
            int out = 0;
            for (int shift = 16; shift >= 0; shift -= 8) {
                out = out << 8 | ((a >> shift & 0xff) + (b >> shift & 0xff) + (c >> shift & 0xff)) / 3;
            }
            return out;
        }

        /** Each texture's mean colour, by its id. */
        final Map<Integer, Integer> textureColors = new HashMap<>();

        int average(TextureProvider textures, int id) {
            return textureColors.computeIfAbsent(id, k -> {
                var all = textures.getTextures();
                var pixels = k < all.length && all[k] != null && all[k].isLoaded() ? all[k].getPixels() : null;
                if (pixels == null || pixels.length == 0) {
                    return 0x808080;
                }
                long r = 0, g = 0, b = 0;
                for (int p : pixels) {
                    r += p >> 16 & 0xff;
                    g += p >> 8 & 0xff;
                    b += p & 0xff;
                }
                int n = pixels.length;
                return (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
            });
        }

        /** {@code rgb} at the game's light {@code light} (0 to 127; 64 as it is). */
        int shade(int rgb, int light) {
            int out = 0;
            for (int shift = 16; shift >= 0; shift -= 8) {
                out = out << 8 | min(255, (rgb >> shift & 0xff) * light / 64);
            }
            return out;
        }

        /** The game's colour (hue, saturation, lightness in 16 bits) in RGB, at the game's brightness. */
        int rgb(int hsl, double brightness) {
            double hue = (hsl >> 10 & 63) / 64.0 + 0.0078125, saturation = (hsl >> 7 & 7) / 8.0 + 0.0625;
            double lightness = (hsl & 127) / 128.0, q = lightness < 0.5 ? lightness * (1 + saturation)
                : lightness + saturation - lightness * saturation, p = 2 * lightness - q;
            int rgb = 0;
            for (double t : new double[]{hue + 1 / 3.0, hue, hue - 1 / 3.0}) {
                t = t < 0 ? t + 1 : t > 1 ? t - 1 : t;
                double v = 6 * t < 1 ? p + (q - p) * 6 * t : 2 * t < 1 ? q : 3 * t < 2 ? p + (q - p) * (2 / 3.0 - t) * 6 : p;
                rgb = rgb << 8 | min(255, (int) (pow(v, brightness) * 256));
            }
            return rgb;
        }

        /** The game's dot sprite (the Minimap plugin's colours when it sets them): 0 item, 1 NPC, 2 player. */
        void dot(Graphics2D g, int kind, LocalPoint local) {
            Point p = localToMinimap(client, local);
            var sprites = client.getMapDots();
            if (p != null && sprites != null) {
                g.drawImage(dots.computeIfAbsent(sprites[kind], SpritePixels::toBufferedImage), p.getX() - 1, p.getY() - 1, null);
            }
        }

        /**
         * The minimap's own shape: the empty pixels of its draw area's sprite, which covers what lies round it (made once a
         * sprite, some 20 ms).
         */
        Shape mask(int sprite) {
            var sprites = client.getSprites(client.getIndexSprites(), sprite, 0);
            if (sprites == null) {
                return null;
            }
            var s = sprites[0];
            int[] px = s.getPixels();
            var path = new Path2D.Double();
            for (int i = 0; i < px.length; i++) {
                if (px[i] == 0) {
                    path.append(new Rectangle(s.getOffsetX() + i % s.getWidth(), s.getOffsetY() + i / s.getWidth(), 1, 1),
                        false);
                }
            }
            return new Area(path);
        }

        /**
         * Swing's thread: our map's tiles of a square of {@code size} tiles (its west and north edges on the map as
         * drawn) at {@code z} pixels a tile, unsmoothed: 1 when a tile is missing (not downloaded yet), 2 when one was
         * drawn.
         */
        int paintMap(Graphics2D g, MapView on, BaseMap map, int west, int north, int size, int plane, double z) {
            // The tile cache's level of as many pixels a tile (4: level 2, as is); a tile cache tile spans span game tiles.
            int lv = (int) round(log(z) / log(2)), span = TileCache.TILE_SIZE >> lv, got = 0;
            double f = z / (1 << lv);
            g.setRenderingHint(KEY_INTERPOLATION, VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            for (int tx = floorDiv(west, span); tx * span < west + size; tx++) {
                for (int ty = floorDiv(north - size, span); ty * span < north; ty++) {
                    var key = new TileCache.Key(map.tiles, lv, plane, tx, ty);
                    // Not in memory (just started): from the disk at once, as the map does, not after a wait.
                    var tile = on.tiles.get(key);
                    tile = tile != null ? tile : on.tiles.fromDiskNow(key);
                    g.drawImage(tile, new AffineTransform(f, 0, 0, f, (tx * span - west) * z, (north - (ty + 1) * span) * z),
                        null);
                    got |= tile == null ? 1 : 2;
                }
            }
            return got;
        }

        /**
         * Swing's thread: the map round a game tile at the minimap's own pixels a tile, asking the tile cache for those
         * it lacks. An upper floor's tiles are see-through: the game's own minimap shows below.
         */
        MiniSnap read(int[] want) {
            var on = screens.length == 0 ? null : screens[0].view;
            var game = new WorldPoint(want[0], want[1], want[2]);
            var map = on == null || on.maps == null ? null : on.maps.find(game);
            if (map == null) {
                return null;
            }
            var p = MapView.shownOn(map, game);
            int r = want[5];
            var image = new BufferedImage(8 * r, 8 * r, BufferedImage.TYPE_INT_ARGB);
            var g = image.createGraphics();
            int got = paintMap(g, on, map, p.getX() - r, p.getY() + r, 2 * r, p.getPlane(), 4);
            g.dispose();
            var s = new MiniSnap(want, (got & 2) == 0 ? null : want[3] == 200 ? halve(image) : image, (got & 1) != 0);
            on.tiles.endFrame();
            return s;
        }

        /**
         * Half the size, each pixel one of its four: a wall's or a door's when one is there (as the map's tiles draw them),
         * so walls one pixel wide stay whole and as bright; else the north-west one, as the game shrinks its own.
         */
        BufferedImage halve(BufferedImage image) {
            int w = image.getWidth() / 2, h = image.getHeight() / 2, wide = w * 2;
            int[] in = image.getRGB(0, 0, wide, h * 2, null, 0, wide), out = new int[w * h];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int at = 2 * y * wide + 2 * x, pick = in[at];
                    for (int k : new int[]{at, at + 1, at + wide, at + wide + 1}) {
                        int rgb = in[k] & 0xffffff;
                        if (in[k] >>> 24 != 0 && (rgb == 0xcccccc || rgb == 0xcc0000)) {
                            pick = in[k];
                            break;
                        }
                    }
                    out[y * w + x] = pick;
                }
            }
            var half = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            half.setRGB(0, 0, w, h, out, 0, w);
            return half;
        }
    }

    /**
     * Of {@code route}'s steps, the items the first one needing any needs into {@code next}, and after a bank step what
     * the steps after it need into {@code bank}. A house step goes by its teleport ("… → Fairy ring").
     */
    static Step routeItems(Route route, Map<String, Set<Integer>> byStep, Set<Integer> next, Set<Integer> bank) {
        boolean banked = false;
        Step found = null;
        for (var step : route == null ? List.<Step>of() : route.steps) {
            banked |= step.kind == BANK;
            String name = step.name, first = step.said.into != null ? step.said.into : name;
            var spell = name == null ? null : byStep.getOrDefault(SPELL + name, byStep.get(SPELL + first));
            var items = name == null ? null : byStep.getOrDefault(name, byStep.get(first));
            if (banked) {
                bank.addAll(items != null ? items : spell != null ? spell : Set.of());
            } else if (found == null && (items != null || spell != null || SPELLS.containsKey(first))) {
                found = step;
                next.addAll(items != null ? items : Set.of());
            }
        }
        return found;
    }

    /**
     * Outlines in the teleport colour what the route needs: in the inventory and worn, the item of its next teleport or
     * transport; in the bank, what its bank step takes. Client thread.
     */
    static final String SPELL = "spell:";
    /** The spellbook's button of each teleport spell (data/spells.tsv). */
    static final Map<String, String[]> SPELLS = PoiType.rows("data/spells.tsv");

    final class ItemsOverlay extends WidgetItemOverlay {
        Route itemsOf;
        boolean fairyRing;
        int spell;
        Set<Integer> next = Set.of(), bank = Set.of();

        ItemsOverlay() {
            showOnInventory();
            showOnEquipment();
            showOnBank();
            drawAfterInterface(InterfaceID.MAGIC_SPELLBOOK);
        }

        // Here, not per item: with the spellbook open no item is drawn.
        public Dimension render(Graphics2D g) {
            Route route = ahead;
            if (route != itemsOf) {
                itemsOf = route;
                next = new HashSet<>();
                bank = new HashSet<>();
                var step = routeItems(route, stepItems, next, bank);
                // A fairy ring's staff: worn is enough, and none is needed after the Lumbridge Elite Diary.
                fairyRing = step != null && PoiType.FAIRY_RING.displayName.equals(step.category);
                var row = step == null ? null : SPELLS.get(step.said.into != null ? step.said.into : step.name);
                spell = row == null ? -1 : Integer.parseInt(row[1]);
            }
            var button = config.routeItems() && spell > 0 ? client.getWidget(spell) : null;
            if (button != null && !button.isHidden()) {
                g.setColor(config.routeJumpColor());
                g.draw(button.getBounds());
            }
            return super.render(g);
        }

        public void renderItemOverlay(Graphics2D g, int itemId, WidgetItem item) {
            int group = WidgetUtil.componentToInterface(item.getWidget().getId());
            // The Lumbridge Elite Diary opens fairy rings without a staff.
            if (config.routeItems() && (bank(group) ? bank.contains(itemId) : next.contains(itemId) && (!fairyRing
                || group != InterfaceID.WORNITEMS && client.getVarbitValue(LUMBRIDGE_DIARY_ELITE_COMPLETE) != 1))) {
                var at = item.getCanvasBounds();
                // Of the stack as shown (coins' pile); ItemManager keeps each outline by item, amount and colour.
                g.drawImage(itemManager.getItemOutline(itemId, item.getQuantity(), config.routeJumpColor()),
                    at.x, at.y, null);
            }
        }
    }

    final class GameOverlay extends Overlay {
        final Tint fillTint = new Tint(), borderTint = new Tint();

        GameOverlay() {
            setPosition(OverlayPosition.DYNAMIC);
            setLayer(OverlayLayer.ABOVE_SCENE);
        }

        public Dimension render(Graphics2D g) {
            Route route = drawn;
            Player player = client.getLocalPlayer();
            if (player == null) {
                return null;
            }
            var view = client.getTopLevelWorldView();
            boolean instance = view == null || view.isInstance();
            headSaid = false;
            if (route != null && (!instance || house.inside() || inCopy)) {
                // Not in other instances (their tiles copy a template); in the house the portal is the next thing.
                paintNextAction(g, route, player);
            }
            if (!headSaid && hdTiles) {
                head(null, null, player);
            }
            // With the route done its tiles still fade away; HD World Markers draws them when it is on.
            if (route == null && fading.isEmpty() || instance && !house.own() && !inCopy || hdTiles) {
                return null;
            }
            int plane = view.getPlane();
            Stroke old = g.getStroke();
            if (lines()) {
                paintLines(g, route, view, plane);
                g.setStroke(old);
                return null;
            }
            int fill = config.routeTileFill(), borderAlpha = config.routeTileBorder();
            double tileWidth = config.routeTileWidth();
            g.setStroke(new BasicStroke((float) max(0.5, min(8, tileWidth))));
            // No scene, also an expanded one, reaches further.
            eachTile(route, near(), Constants.EXTENDED_SCENE_SIZE, (point, color, strength) -> {
                var local = z(point) != plane ? null : yourHouse.local(view, point);
                Polygon tile = local == null ? null : tilePoly(view, local, plane);
                if (tile != null) {
                    g.setColor(fillTint.of(color, fill * strength));
                    g.fill(tile);
                    if (borderAlpha > 0 && tileWidth > 0) {
                        g.setColor(borderTint.of(color, borderAlpha * strength));
                        g.draw(tile);
                    }
                }
            });
            g.setStroke(old);
            return null;
        }
    }


    /** Tiles the extended scene reaches past each edge of the scene. */
    static final int EXPANDED_EDGE = (Constants.EXTENDED_SCENE_SIZE - Constants.SCENE_SIZE) / 2;

    /**
     * A tile's outline on the canvas, also past the scene's edge in an expanded scene: RuneLite's own takes the ground
     * there as flat, so the corners' heights are read from the extended scene's heights.
     */
    Polygon tilePoly(WorldView view, LocalPoint local, int plane) {
        int sx = local.getSceneX(), sy = local.getSceneY();
        if (sx >= 0 && sy >= 0 && sx < view.getSizeX() && sy < view.getSizeY()) {
            return getCanvasTilePoly(client, local);
        }
        var heights = view.getTileHeights();
        int ex = sx + EXPANDED_EDGE, ey = sy + EXPANDED_EDGE;
        if (heights == null || plane < 0 || plane >= heights.length || ex < 0 || ey < 0
            || ex + 1 >= heights[plane].length || ey + 1 >= heights[plane][ex].length) {
            return null;
        }
        int half = LOCAL_TILE_SIZE / 2, x = local.getX(), y = local.getY();
        Polygon poly = new Polygon();
        // The corners south-west, south-east, north-east, north-west: 0 is the tile's west or south side, 1 east or north.
        for (int[] c : new int[][]{{0, 0}, {1, 0}, {1, 1}, {0, 1}}) {
            Point corner = localToCanvas(client, x + (2 * c[0] - 1) * half, y + (2 * c[1] - 1) * half,
                heights[plane][ex + c[0]][ey + c[1]]);
            if (corner == null) {
                return null;
            }
            poly.addPoint(corner.getX(), corner.getY());
        }
        return poly;
    }

    static final class RouteText {
        /** "Can't yet: 45 Sailing, Lunar Diplomacy". */
        static String cannotYet(Route route) {
            var missing = route.steps.stream().filter(RouteText::lacks)
                .flatMap(step -> Arrays.stream(step.said.lack.split(",\\s*"))).map(String::trim).filter(part -> !part.isEmpty())
                .collect(toCollection(LinkedHashSet::new));
            return missing.isEmpty() ? "Can't yet." : "Can't yet: " + String.join(", ", missing);
        }

        static boolean lacks(Step step) {
            return step.said.lack != null;
        }

        /** The first step the player cannot take yet (drawn red from there on), or -1. */
        static int blockedFrom(Route route) {
            for (int i = 0; route != null && i < route.steps.size(); i++) {
                if (lacks(route.steps.get(i))) {
                    return i;
                }
            }
            return -1;
        }

        static String escape(String text) {
            return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }

        static List<String> notes(Route route) {
            var notes = new ArrayList<String>();
            if (route.outcome == NONE) {
                notes.add("No route from here.");
            } else if (route.outcome == NEAREST) {
                notes.add(route.exhausted ? "Can't get there." : t("tooFar"));
            } else if (route.snapped()) {
                notes.add(t("snapped"));
            }
            if (route.viaWild && route.outcome == FOUND) {
                notes.add(t("wilderness"));
            }
            return notes;
        }

        /** As "an axe, a machete", or null. */
        static String bring(Route route) {
            var tools = route.steps.stream().flatMap(step -> step.obstacles.stream()).map(o -> o.tool)
                .collect(toCollection(LinkedHashSet::new));
            return tools.isEmpty() ? null : String.join(", ", tools);
        }

        /** Whether a detail is only the caution that it may need something unknown. */
        static boolean unsure(String detail) {
            return Pathfinder.UNKNOWN_NEEDS.equals(detail);
        }

        static String duration(Route route) {
            int ticks = (route.time() + 1) / 2;
            return about(ticks) + " (" + ticks + " ticks)";
        }

        static String about(int ticks) {
            int seconds = (int) round(ticks * 0.6);
            return "About " + (seconds < 90 ? seconds + " seconds" : round(seconds / 60.0) + " minutes");
        }

        /** How a house step from outside joins the way in to what is used inside. */
        static final String INTO = " → ";
        /** The same in the game, whose font has no "→". */
        static final String THEN = " -> ";

        /**
         * A step's name as said: a house step by what it uses in the house ("Jewellery box 5 Burthorpe"), after its way in
         * and {@code then} unless that is null; a jump to the bank with what is taken there (" → Bank: Dramen staff").
         * {@code brief}: as a long text in the game says it ("Jwlry box 5").
         */
        static String named(Step step, String then, boolean brief) {
            var s = step.said;
            String name = step.kind != Kind.HOUSE || s.inHouse == null ? step.name
                : (then == null || s.into == null ? "" : brief && s.brief != null ? s.brief + ", " : s.into + then)
                + (brief && s.boxed != null ? s.boxed : s.inHouse);
            return s.taken == null || step.kind == Kind.BANK ? name : name + BankDetour.TO_BANK + String.join(", ", s.taken);
        }

        /**
         * A step as said in the game, over the head and on its tile: a house step outside the house by the way in and
         * what to use there ("Teleport to House -> Jewellery box 5 Burthorpe"), inside by what to use; a way into the
         * house with the house step after it; else as {@link #describe}. A long text cut short: "TP house, Jwlry box 2
         * -> Bank: Dramen staff".
         */
        static String said(Route route, Step step, boolean inside) {
            String text = saying(route, step, inside, false);
            // The game's font has no "→" (also in "… → Bank: …").
            return (text.length() > LONG ? saying(route, step, inside, true) : text).replace(INTO, THEN);
        }

        /** Characters above which a text in the game is said {@code brief}. */
        static final int LONG = 50;

        static String saying(Route route, Step step, boolean inside, boolean brief) {
            if (step.kind == Kind.HOUSE && step.name != null) {
                return named(step, inside ? null : THEN, brief);
            }
            int to = step.last();
            if (!inside && step.isJump() && to >= 0 && HousePlan.contains(to)) {
                // Into the house, then past the walk (and stairs) inside to what is used there.
                for (int i = route.steps.indexOf(step) + 1; i < route.steps.size(); i++) {
                    Step next = route.steps.get(i);
                    if (next.kind == Kind.HOUSE && next.name != null) {
                        return (brief && step.said.brief != null ? step.said.brief + ", " : describe(step) + THEN)
                            + named(next, null, brief);
                    }
                    if (next.kind != Kind.WALK && next.kind != Kind.STAIRS) {
                        break;
                    }
                }
            }
            return describe(step);
        }

        static String describe(Step step) {
            switch (step.kind) {
                case WALK:
                    return "Walk " + plural(step.points.length - 1, "tile")
                        + (step.doors.length > 0 ? " (" + plural(step.doors.length, "door") + ")" : "");
                case SAIL:
                    return "Sail about " + (step.points.length - 1) * CELL + " tiles";
                case STAIRS:
                    return step.name + " to floor " + z(step.last());
                default:
                    return step.name != null ? named(step, INTO, false) : step.kind.name().toLowerCase(ROOT);
            }
        }

        static final Color OUTLINE = new Color(0, 0, 0, 140);

        static void paint(Graphics2D g, MapView p, Route route, Color walk, Color jump) {
            float width = (float) max(2.5, min(6, pow(2, p.zoom()) / 2));
            Stroke solid = new BasicStroke(width, CAP_ROUND, JOIN_ROUND);
            Stroke dashed = new BasicStroke(max(2f, width - 1), CAP_ROUND, JOIN_ROUND, 1, new float[]{7, 6}, 0);
            Stroke outline = new BasicStroke(width + 2.5f, CAP_ROUND, JOIN_ROUND);
            int blocked = blockedFrom(route);
            for (int index = 0; index < route.steps.size(); index++) {
                Step step = route.steps.get(index);
                if (blocked >= 0 && index >= blocked) {
                    walk = CANNOT;
                    jump = CANNOT;
                }
                if (step.isJump()) {
                    var at = drawn(p, step.points);
                    int b = 3 * (step.points.length - 1);
                    if (shown(p, at, 0) && shown(p, at, b) && step.first() != step.last()) {
                        g.setStroke(dashed);
                        g.setColor(color(step, walk, jump));
                        g.draw(new Line2D.Double(p.screenX(at[0]), p.screenY(at[1]), p.screenX(at[b]), p.screenY(at[b + 1])));
                    }
                    g.setColor(jump);
                    for (int i : new int[]{0, b}) {
                        if (shown(p, at, i)) {
                            g.fill(circle(p.screenX(at[i]), p.screenY(at[i + 1]), 4));
                        }
                    }
                    continue;
                }
                var line = path(p, step.points, true);
                g.setStroke(outline);
                g.setColor(OUTLINE);
                g.draw(line);
                g.setStroke(solid);
                g.setColor(color(step, walk, jump));
                g.draw(line);
            }
            boolean ends = route.end >= 0 && visible(p, route.end);
            if (ends) {
                var end = circle(x(p, route.end), y(p, route.end), 6);
                g.setStroke(new BasicStroke(2f));
                g.setColor(isSea(route.end) ? SEA_END : END);
                g.fill(end);
                g.setColor(Color.WHITE);
                g.draw(end);
            }
            if (route.snapped() && visible(p, route.target)) {
                // The spot asked for, crossed out, with a thin line to where the route ends.
                double tx = x(p, route.target), ty = y(p, route.target);
                g.setStroke(new BasicStroke(2.5f));
                g.setColor(END);
                g.draw(new Line2D.Double(tx - 6, ty - 6, tx + 6, ty + 6));
                g.draw(new Line2D.Double(tx - 6, ty + 6, tx + 6, ty - 6));
                if (ends) {
                    g.setStroke(new BasicStroke(1.5f, CAP_BUTT, JOIN_MITER, 1, new float[]{3, 4}, 0));
                    g.draw(new Line2D.Double(tx, ty, x(p, route.end), y(p, route.end)));
                }
            }
        }

        /** The points' line where shown, broken where not. */
        static Path2D path(MapView p, int[] points) {
            return path(p, points, false);
        }

        /**
         * The same, {@code inView}: without the pieces wholly past one edge of the view (a long custom route has tens
         * of thousands, a few of them in view). Not for dashes: their pattern would start again where the view begins.
         */
        static Path2D path(MapView p, int[] points, boolean inView) {
            var line = new Path2D.Double();
            var at = drawn(p, points);
            // Past the widest stroke's half: a piece's end there leaves no cap in view.
            double m = 16, right = p.width() + m, bottom = p.height() + m, lastX = 0, lastY = 0;
            boolean drawing = false;
            int lastSide = -1;
            for (int i = 0; i < at.length; i += 3) {
                if (!shown(p, at, i)) {
                    drawing = false;
                    lastSide = -1;
                    continue;
                }
                double x = p.screenX(at[i]), y = p.screenY(at[i + 1]);
                int side = !inView ? 0 : (x < -m ? 1 : x > right ? 2 : 0) | (y < -m ? 4 : y > bottom ? 8 : 0);
                if (lastSide >= 0 && (side & lastSide) == 0) {
                    if (!drawing) {
                        line.moveTo(lastX, lastY);
                        drawing = true;
                    }
                    line.lineTo(x, y);
                } else {
                    drawing = false;
                }
                lastSide = side;
                lastX = x;
                lastY = y;
            }
            return line;
        }

        /**
         * Per map, where routes' points are drawn on it, worked out once per points and map (not per point every
         * frame): the two views may show different maps. Weakly: a version's maps go with it.
         */
        static final Map<BaseMap, Map<int[], float[]>> DRAWN = new WeakHashMap<>();

        /**
         * Per point: its x and y as drawn ({@code NaN} where this map does not show it) and its floor (-1 at sea: shown on
         * every floor).
         */
        static synchronized float[] drawn(MapView p, int[] points) {
            return DRAWN.computeIfAbsent(p.map(), map -> new WeakHashMap<>()).computeIfAbsent(points, k -> {
                float[] at = new float[points.length * 3];
                for (int i = 0; i < points.length; i++) {
                    int node = points[i];
                    boolean sea = isSea(node);
                    var game = worldPoint(node);
                    var shown = sea ? game : p.shown(game);
                    at[3 * i] = !p.shows(game) ? Float.NaN : sea ? Tiles.x(node) : shown.getX() + 0.5f;
                    at[3 * i + 1] = sea ? Tiles.y(node) : shown.getY() + 0.5f;
                    at[3 * i + 2] = sea ? -1 : shown.getPlane();
                }
                return at;
            });
        }

        static boolean shown(MapView p, float[] at, int i) {
            return !Float.isNaN(at[i]) && (at[i + 2] < 0 || at[i + 2] == p.plane());
        }

        static boolean visible(MapView p, int node) {
            var game = worldPoint(node);
            // Where the map draws it: a part drawn elsewhere (the Kalphite Lair) there, on the floor drawn.
            return p.shows(game) && (isSea(node) || p.shown(game).getPlane() == p.plane());
        }

        static double x(MapView p, int node) {
            return p.screenX(isSea(node) ? Tiles.x(node) : p.shown(worldPoint(node)).getX() + 0.5);
        }

        static double y(MapView p, int node) {
            return p.screenY(isSea(node) ? Tiles.y(node) : p.shown(worldPoint(node)).getY() + 0.5);
        }
    }
}
