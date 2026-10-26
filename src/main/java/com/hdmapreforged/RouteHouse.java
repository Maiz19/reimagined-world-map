package com.hdmapreforged;

import static com.hdmapreforged.PoiType.t;
import static java.lang.Math.*;
import static com.hdmapreforged.RouteFeature.*;
import static com.hdmapreforged.RoutePanels.*;
import static com.hdmapreforged.SearchResults.*;
import static com.hdmapreforged.MapConfig.GROUP;
import static com.hdmapreforged.HousePlan.*;
import static com.hdmapreforged.HouseSettings.*;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.*;
import java.io.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.regex.*;
import java.util.stream.*;
import javax.imageio.*;
import javax.imageio.stream.*;
import javax.swing.*;
import lombok.*;
import net.runelite.api.*;
import net.runelite.api.Point;
import net.runelite.api.coords.*;
import net.runelite.api.events.*;
import net.runelite.api.widgets.*;
import net.runelite.client.eventbus.*;
import net.runelite.client.events.*;
import net.runelite.client.ui.*;
import net.runelite.client.util.*;

/**
 * One's own house for routes, kept per account: looked through once per visit (its portals, nexus and such, and its
 * plan), its picture drawn at the surface map's edge, and its panel. Threads as {@link RouteFeature}'s.
 */
@RequiredArgsConstructor
public final class RouteHouse {
    final RouteFeature routes;
    final HouseTracker house;

    /**
     * Where a route tile is in the scene: the plan of one's house lies at {@link HousePlan#X}/{@link HousePlan#Y}
     * plus the house's own place, moved to where it is loaded now; null outside the scene, past its edge only as far as
     * an expanded scene loads.
     */
    LocalPoint local(WorldView view, int point) {
        int x = Tiles.x(point), y = Tiles.y(point);
        // In an instance copying a place (the Watchtower's top floor): its tiles are the template's, wherever the
        // instance put them in the scene.
        var template = view.isInstance() ? InstanceCopies.template(x, y, Tiles.z(point)) : null;
        if (template != null) {
            for (var at : WorldPoint.toLocalInstance(view, new WorldPoint(template[0], template[1], template[2]))) {
                var local = LocalPoint.fromWorld(view, at);
                if (local != null) {
                    return local;
                }
            }
            return null;
        }
        if (contains(x, y)) {
            return house.own() ? LocalPoint.fromScene(x - X + houseX, y - Y + houseY, view) : null;
        }
        var local = LocalPoint.fromWorld(view, x, y);
        if (local == null) {
            // Past the scene's edge, where an expanded scene (the GPU plugins' setting) draws the ground on: the
            // route goes on there too, as far as the game loaded.
            int beyond = min(EXPANDED_EDGE, 8 * max(0, routes.client.getExpandedMapLoading()));
            int sx = x - view.getBaseX(), sy = y - view.getBaseY();
            if (beyond > 0 && sx >= -beyond && sy >= -beyond && sx < view.getSizeX() + beyond
                && sy < view.getSizeY() + beyond) {
                local = LocalPoint.fromScene(sx, sy, view);
            }
        }
        return local;
    }

    /**
     * Tiles from the plan to the scene as loaded now. The game centres the scene on where one stood as it loaded (a
     * teleport in, building mode), so the house lies elsewhere in it each time; the plan keeps its south-west room at
     * chunk 2. Client thread writes.
     */
    volatile int houseX;

    volatile int houseY;

    void placeHouse(WorldView view) {
        var chunks = view == null ? null : view.getInstanceTemplateChunks();
        int west = 13, south = 13;
        for (var plane : chunks == null ? new int[0][][] : chunks) {
            for (int x = 0; x < plane.length; x++) {
                for (int y = 0; y < plane[x].length; y++) {
                    // A room of the house: copied from the game's house templates.
                    int data = plane[x][y];
                    if (data != -1 && HouseTracker.isTemplate((data >> 14 & 0x3ff) * 8, (data >> 3 & 0x7ff) * 8)) {
                        west = min(west, x);
                        south = min(south, y);
                    }
                }
            }
        }
        houseX = west < 13 ? (west - 2) * 8 : 0;
        houseY = south < 13 ? (south - 2) * 8 : 0;
        placed = west < 13;
    }

    /** Whether the house's rooms are placed on the plan: not on the first tick after coming in, as its scene loads. */
    boolean placed;

    /** The player's tile on the plan of their own house once seen; else -1 (the route starts from its ways out). */
    int inHouse() {
        var local = me();
        var view = routes.client.getTopLevelWorldView();
        var seen = plan;
        int at = seen == null || !house.own() || local == null || view == null ? -1
            : planNode(local, view.getPlane());
        // Just in, one stands where the portal put one (its arrival), but the tile read from the scene on the first
        // ticks is off (the scene still loading): the arrival is the tile until the reading changes (it settles, or
        // one walks).
        if (seen == null || enteredAt < 0 || routes.ticks - enteredAt >= SETTLE_TICKS) {
            return at;
        }
        if (firstReading == NONE) {
            firstReading = at;
        }
        if (at == firstReading || at < 0) {
            return seen.arrival;
        }
        enteredAt = -1;
        return at;
    }

    /** Where the player stands in the scene; null before there is one. */
    LocalPoint me() {
        Player player = routes.client.getLocalPlayer();
        return player == null ? null : player.getLocalLocation();
    }

    /** Ticks after coming in during which the arrival is the tile while the scene's reading has not changed. */
    static final int SETTLE_TICKS = 10, NONE = Integer.MIN_VALUE;
    /** The tick one came into the house; -1 when not in it. */
    int enteredAt = -1;
    /** The scene's first reading of one's tile since coming in. */
    int firstReading = NONE;

    /** A scene tile of one's house on its plan. */
    int planNode(LocalPoint local, int plane) {
        return node(local.getSceneX() - houseX, local.getSceneY() - houseY, plane);
    }

    @Subscribe
    public void onGameObjectSpawned(GameObjectSpawned event) {
        // Not while a scene loads: the scan sees those, and going into building mode its spaces load before it shows.
        if (house.own() && routes.client.getGameState() == GameState.LOGGED_IN) {
            house.seen(name(event.getGameObject().getId()));
        }
    }

    String name(int id) {
        var object = routes.client.getObjectDefinition(id);
        if (object != null && object.getImpostorIds() != null) {
            object = object.getImpostor();
        }
        return object == null || "null".equals(object.getName()) ? null : object.getName();
    }

    /** The game's list of portal nexus teleports (an enum of structs), and the param naming each. */
    static final int NEXUS_TELEPORTS = 1377;

    static final int NEXUS_NAME = 660;

    static final Pattern STAIRS = Pattern.compile(".*(tair|adder|rapdoor).*");

    /**
     * One's own house, looked through once per visit (its objects spawn while it loads, before a tick finds the player
     * inside): its portals, nexus and such for routes, and its plan, drawn at the surface map's edge. Kept per account.
     */
    void scanHouse() {
        var view = routes.client.getTopLevelWorldView();
        var me = me();
        if (view == null || me == null) {
            house.scanAgain();
            return;
        }
        int dx = houseX, dy = houseY;
        var scene = view.getScene().getTiles();
        var collision = view.getCollisionMaps();
        // Moved to the house's own place (houseX/houseY): the scene is centred on where one stood as it loaded.
        byte[][] placed = new byte[4][SIZE * SIZE];
        var things = new LinkedHashMap<Integer, String>();
        var names = new ArrayList<String>();
        for (int z = 0; z < 4; z++) {
            var flags = collision == null || collision[z] == null ? new int[0][] : collision[z].getFlags();
            for (int x = max(0, dx); x < min(SIZE, SIZE + dx); x++) {
                for (int y = max(0, dy); y < min(SIZE, SIZE + dy); y++) {
                    Tile tile = scene[z][x][y];
                    // No ground (the void round the house) is no floor, though the game sets no flag there.
                    int f = tile == null || tile.getSceneTilePaint() == null && tile.getSceneTileModel() == null
                        || x >= flags.length || y >= flags[x].length ? CollisionDataFlag.BLOCK_MOVEMENT_FULL : flags[x][y];
                    int node = node(x - dx, y - dy, z), i = (x - dx) * SIZE + y - dy;
                    placed[z][i] = (byte) (((f & CollisionDataFlag.BLOCK_MOVEMENT_FULL) == 0 ? 1 : 0)
                        | ((f & CollisionDataFlag.BLOCK_MOVEMENT_NORTH) != 0 ? 2 : 0)
                        | ((f & CollisionDataFlag.BLOCK_MOVEMENT_EAST) != 0 ? 4 : 0));
                    var standing = tile == null ? new GameObject[0] : tile.getGameObjects();
                    for (int k = 0; tile != null && k < standing.length + 2; k++) {
                        // A mounted glory hangs on a wall.
                        var object = k < standing.length ? standing[k]
                            : k == standing.length ? tile.getWallObject() : tile.getDecorativeObject();
                        // Once per object, at its south-west tile.
                        Point base = object instanceof GameObject ? ((GameObject) object).getSceneMinLocation() : null;
                        // Its tiles east (32) and north (64) of its first: routes use it from beside any.
                        placed[z][i] |= base == null ? 0 : base.getX() < x && base.getY() == y ? 32
                            : base.getY() < y && base.getX() == x ? 64 : 0;
                        String name = object == null || base != null && (base.getX() != x || base.getY() != y) ? null
                            : name(object.getId());
                        if (name == null) {
                            continue;
                        }
                        String lower = name.toLowerCase(Locale.ROOT);
                        if (lower.endsWith(" space")) {
                            // Building mode's empty spaces (its varbit can come before the normal house loads).
                            house.scanAgain();
                            return;
                        }
                        names.add(name);
                        if (object instanceof WallObject && lower.contains("door")) {
                            door(placed[z], x - dx, y - dy, ((WallObject) object).getOrientationA());
                        } else if (lower.contains("nexus") || name.equals("Portal") || HouseTracker.feature(name) != null
                            || STAIRS.matcher(name).matches()) {
                            things.put(node, lower.contains("nexus") ? RouteSource.NEXUS : name);
                        }
                    }
                }
            }
        }
        house.replaceFeatures(Set.of());
        names.forEach(house::seen);
        things.replaceAll((node, name) -> name.equals(RouteSource.NEXUS) ? nexus() : name);
        int arrival = node(me.getSceneX() - dx, me.getSceneY() - dy, view.getPlane());
        var from = new ArrayList<Integer>(List.of(arrival));
        things.forEach((node, name) -> {
            for (int d = 0; STAIRS.matcher(name).matches() && d < 9; d++) {
                // From where the player stands; stairs lead to the floors above and below.
                from.add(Tiles.pack(Tiles.x(node) + d / 3 - 1, Tiles.y(node) + d % 3 - 1, Tiles.z(node)));
            }
        });
        keepReachable(placed, from);
        String exit = "Portal";
        for (var thing : things.entrySet()) {
            for (int d = 1; exit.equals(thing.getValue()) && d <= 3; d++) {
                int north = thing.getKey() + d, y = Tiles.y(north) - Y;
                if (y < SIZE && (placed[Tiles.z(north)][(Tiles.x(north) - X) * SIZE + y] & 1) != 0) {
                    arrival = north;
                    exit = "";
                }
            }
        }
        // A house may have several superior gardens: a spirit tree in one and a fairy ring in another count as both.
        var gardens = things.values().stream().filter(RouteHouse::garden).map(HouseTracker::feature)
            .collect(Collectors.toSet());
        if (!gardens.isEmpty()) {
            boolean both = gardens.size() > 1 || gardens.contains(BOTH);
            var kept = new LinkedHashSet<String>(house.features());
            kept.removeIf(Arrays.asList(GARDENS)::contains);
            kept.add(both ? BOTH : gardens.iterator().next());
            house.replaceFeatures(kept);
        }
        var seen = new HousePlan(placed, things, arrival);
        // The game's own minimap of each floor (only floors the house has: each picture is drawn for the whole scene).
        var drawn = new BufferedImage[4];
        for (int z = 0; z < 4; z++) {
            var floor = placed[z];
            var minimap = IntStream.range(0, floor.length).anyMatch(i -> (floor[i] & 1) != 0)
                ? routes.client.drawInstanceMap(z) : null;
            drawn[z] = minimap == null ? null : minimap.toBufferedImage();
        }
        keepLook(drawn, placed, dx, dy);
        String text = seen.encode();
        if (text != null) {
            routes.configManager.setRSProfileConfiguration(GROUP, PLAN_KEY, text);
        }
        setPlan(seen);
        writeHouse(house.features());
    }

    static boolean garden(String thing) {
        String feature = HouseTracker.feature(thing);
        return feature != null && Arrays.asList(GARDENS).contains(feature);
    }

    /** A door counts as open (orientation 1 west, 2 north, 4 east, 8 south): its wall's bit becomes a door's. */
    static void door(byte[] tiles, int x, int y, int orientation) {
        int wall = orientation == 2 || orientation == 8 ? 2 : orientation == 1 || orientation == 4 ? 4 : 0;
        int tx = orientation == 1 ? x - 1 : x, ty = orientation == 8 ? y - 1 : y, i = tx * SIZE + ty;
        if (wall != 0 && tx >= 0 && ty >= 0) {
            tiles[i] = (byte) (tiles[i] & ~wall | wall * 4);
        }
    }

    /** The varbits of the nexus' 45 slots: runs one after another, each its first and length (list "nexus slots"). */
    static final RouteFiles.Table<int[]> NEXUS_SLOTS = new RouteFiles.Table<>(() -> Pathfinder.list("nexus slots",
        item -> Arrays.copyOf(Pathfinder.ints(item), 2)).stream().flatMapToInt(run -> IntStream.range(run[0],
        run[0] + run[1])).filter(varbit -> varbit >= 0).limit(45).toArray());

    /** "Portal Nexus: Varrock, Falador": its places from the game's varbits (slot, teleport, its name). */
    String nexus() {
        var places = new ArrayList<String>();
        var teleports = routes.client.getEnum(NEXUS_TELEPORTS);
        for (int varbit : teleports == null ? new int[0] : NEXUS_SLOTS.get()) {
            int id = routes.client.getVarbitValue(varbit), struct = id <= 0 ? -1 : teleports.getIntValue(id);
            var teleport = struct < 0 ? null : routes.client.getStructComposition(struct);
            String name = teleport == null ? null : teleport.getStringValue(NEXUS_NAME);
            String place = name == null ? null : Text.removeTags(name);
            if (place != null && !place.isEmpty()) {
                places.add(place);
                house.seen(RouteSource.NEXUS + ": " + place);
            }
        }
        return RouteSource.NEXUS + (places.isEmpty() ? "" : ": " + String.join(", ", places));
    }

    /** Which house a house portal leads to, as the player chose it there. */
    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event) {
        Widget widget = event.getWidget();
        String what = (event.getMenuOption() + " " + Text.removeTags(event.getMenuTarget()) + " " + (widget == null
            || widget.getText() == null ? "" : Text.removeTags(widget.getText()))).toLowerCase(Locale.ROOT);
        // Also a tablet, the spell or a cape into it (not their Outside): at a portal that would look like a friend's.
        if (what.startsWith("home ") || what.startsWith("build mode ") || what.contains("your house")
            || what.startsWith("tele to poh") || what.matches("(break|inside|cast) teleport to house.*")) {
            house.chose(true);
        } else if (what.contains("friend's house")) {
            house.chose(false);
        }
    }

    /**
     * The house per floor as the game's minimap draws it, 4 pixels a tile over the scene's 104, north up; null where
     * not seen. Kept per account as one picture in the plugin's folder, the floors one under another.
     */
    final AtomicReference<BufferedImage[]> houseLook = new AtomicReference<>(new BufferedImage[4]);

    static final int LOOK = SIZE * 4;

    /** Set by the plugin when it starts. */
    volatile Filepath houseFolder;

    volatile Executor houseIo;

    Filepath lookFile(long account) {
        var folder = houseFolder;
        return folder == null || houseIo == null ? null : folder.joinSegment(Long.toHexString(account) + ".png");
    }

    /**
     * The house in a minimap picture that may cover an expanded scene, moved as the plan is ({@code dx}, {@code dy}
     * tiles from the plan to the scene): of the margins a chunk apart, the one where the plan's walls are the
     * picture's white lines (else its floor is painted); null when none fits. Black (no ground) becomes clear.
     */
    static BufferedImage sceneOf(BufferedImage all, byte[] floor, int dx, int dy) {
        BufferedImage best = null;
        int most = 0;
        for (int margin = 0; margin + LOOK <= min(all.getWidth(), all.getHeight()); margin += 32) {
            int painted = 0;
            for (int i = 0; i < floor.length; i++) {
                int x = (i / SIZE + dx) * 4, y = (SIZE - 1 - i % SIZE - dy) * 4;
                boolean in = x >= 0 && y >= 0 && x + 4 <= LOOK && y + 4 <= LOOK;
                int rgb = in ? all.getRGB(margin + x + 2, margin + y + 2) : 0;
                painted += (floor[i] & 1) != 0 && rgb >>> 24 != 0 && (rgb & 0xffffff) != 0 ? 1 : 0;
                // A wall on the tile's north or east edge: white there.
                painted += in && (floor[i] & 2) != 0 && white(all.getRGB(margin + x + 1, margin + y)) ? 16 : 0;
                painted += in && (floor[i] & 4) != 0 && white(all.getRGB(margin + x + 3, margin + y + 1)) ? 16 : 0;
            }
            if (painted > most) {
                // A copy: the rest of the picture (four times its size) is not kept with it.
                most = painted;
                int[] pixels = new int[LOOK * LOOK];
                for (int i = 0; i < pixels.length; i++) {
                    int x = i % LOOK + dx * 4, y = i / LOOK - dy * 4;
                    int rgb = x < 0 || y < 0 || x >= LOOK || y >= LOOK ? 0 : all.getRGB(margin + x, margin + y);
                    pixels[i] = (rgb & 0xffffff) == 0 ? 0 : rgb;
                }
                best = new BufferedImage(LOOK, LOOK, BufferedImage.TYPE_INT_ARGB);
                best.setRGB(0, 0, LOOK, LOOK, pixels, 0, LOOK);
            }
        }
        return best;
    }

    static boolean white(int rgb) {
        return (rgb >> 16 & 255) > 180 && (rgb >> 8 & 255) > 180 && (rgb & 255) > 180;
    }

    /**
     * Fitted to the plan ({@link #sceneOf}: up to 7 ms a floor, 200 ms the first time), shown and written on the file
     * thread: not on the client thread.
     */
    void keepLook(BufferedImage[] drawn, byte[][] floors, int dx, int dy) {
        // A look read from the file meanwhile does not replace this one.
        var pending = new BufferedImage[4];
        houseLook.set(pending);
        var file = lookFile(houseOf);
        Executor io = houseIo;
        Runnable fit = () -> {
            var look = new BufferedImage[4];
            for (int z = 0; z < 4; z++) {
                look[z] = drawn[z] == null ? null : sceneOf(drawn[z], floors[z], dx, dy);
            }
            // Not over a newer scan, nor for another account.
            if (houseLook.compareAndSet(pending, look)) {
                SwingUtilities.invokeLater(routes::repaint);
                write(look, file);
            }
        };
        if (io == null) {
            fit.run();
        } else {
            // The plugin's io threads stop with it: work handed over after that is dropped.
            TileCache.execute(io, fit);
        }
    }

    static void write(BufferedImage[] look, Filepath file) {
        if (file != null) {
            BufferedImage all = new BufferedImage(LOOK, LOOK * 4, BufferedImage.TYPE_INT_ARGB);
            var g = all.createGraphics();
            for (int z = 0; z < 4; z++) {
                g.drawImage(look[z], 0, z * LOOK, null);
            }
            g.dispose();
            // In memory: ImageIO would cache through a temporary file of its own.
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try {
                try (ImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
                    ImageIO.write(all, "png", out);
                }
                TileCache.writeSafe(file, bytes.toByteArray());
            } catch (IOException | RuntimeException e) {
            }
        }
    }

    /** The account's kept look, read on the file thread; our own drawing of the plan until then. */
    void readLook(long account) {
        var none = new BufferedImage[4];
        houseLook.set(none);
        var file = lookFile(account);
        if (file == null) {
            return;
        }
        TileCache.execute(houseIo, () -> {
            var look = new BufferedImage[4];
            try (ImageInputStream in = file.isFile() ? new MemoryCacheImageInputStream(new ByteArrayInputStream(
                TileCache.read(file))) : null) {
                BufferedImage all = in == null ? null : ImageIO.read(in);
                for (int z = 0; all != null && all.getWidth() == LOOK && all.getHeight() == LOOK * 4 && z < 4; z++) {
                    look[z] = all.getSubimage(0, z * LOOK, LOOK, LOOK);
                }
            } catch (IOException | RuntimeException e) {
            }
            // Not over a scan made meanwhile, nor for another account.
            if (houseOf == account && houseLook.compareAndSet(none, look)) {
                SwingUtilities.invokeLater(routes::repaint);
            }
        });
    }

    /** The account whose house is loaded (client thread); none after a log out. */
    volatile long houseOf = Long.MIN_VALUE;

    /** For Swing: the features as last written. */
    volatile Set<String> houseFeatures = Set.of();

    volatile HousePlan plan;

    volatile CollisionMap collision;

    /** The plan and the route data (loaded meanwhile) are joined under this lock. */
    synchronized void setPlan(HousePlan seen) {
        plan = seen;
        if (collision != null) {
            collision.setHouse(seen);
        }
        houseChanged();
    }

    /** This account's house; not before RuneLite knows the account's profile: that may come just after the login. */
    void loadHouse() {
        if (routes.configManager.getRSProfileKey() == null) {
            return;
        }
        houseOf = routes.client.getAccountHash();
        routes.loadBank();
        routes.planted.load(routes.configManager.getRSProfileConfiguration(GROUP,
            Planted.KEY));
        String saved = routes.configManager.getRSProfileConfiguration(GROUP, HOUSE_KEY);
        var features = new LinkedHashSet<String>(Arrays.asList(saved == null ? new String[0] : saved.split(",")));
        features.remove("");
        house.reset();
        house.restore(features);
        readLook(houseOf);
        writeHouse(house.features());
        String text = routes.configManager.getRSProfileConfiguration(GROUP, PLAN_KEY);
        setPlan(text == null ? null : decode(text));
    }

    void writeHouse(Set<String> features) {
        houseFeatures = Set.copyOf(features);
        routes.configManager.setRSProfileConfiguration(GROUP, HOUSE_KEY, String.join(",", features));
        houseChanged();
    }

    @Subscribe
    public void onRuneScapeProfileChanged(RuneScapeProfileChanged event) {
        routes.clientThread.invokeLater(() -> {
            if (routes.client.getGameState() == GameState.LOGGED_IN) {
                loadHouse();
            }
        });
    }

    /** One's house at the surface map's edge: its picture per floor, and what stands where. */
    void paintHouse(Graphics2D g, Projection p) {
        var seen = plan;
        g.setFont(FontManager.getRunescapeSmallFont());
        if (seen == null) {
            return;
        }
        // One picture a floor, the game's minimap of it: not thousands of shapes a frame.
        BufferedImage look = houseLook.get()[max(0, min(3, p.plane()))];
        // At the surface map's edge, where the map draws the house.
        int left = (int) round(p.screenX(DRAWN_X)), top = (int) round(p.screenY(DRAWN_Y + SIZE));
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(look, left, top, (int) round(p.screenX(DRAWN_X
            + SIZE)) - left, (int) round(p.screenY(DRAWN_Y)) - top, null);
        seen.things.forEach((node, name) -> {
            String label = name.split(":")[0];
            float x = (float) RouteText.x(p, node) - g.getFontMetrics().stringWidth(label) / 2f;
            float y = (float) RouteText.y(p, node);
            if (Tiles.z(node) == p.plane()) {
                TextSprites.shadowed(g, label, x, y, Color.WHITE);
            }
        });
    }

    /** "Your house" for this account: what it has for routes, set here, and its plan. Swing thread. */
    JComponent housePanel(int width) {
        JPanel panel = bordered();
        panel.add(label("Your house", width, Font.BOLD, Color.WHITE));
        var seen = plan;
        var now = houseFeatures;
        if (collision == null || houseOf == Long.MIN_VALUE) {
            panel.add(label(t("houseLogIn"), width, NOTE));
            return panel;
        }
        Color gray = ColorScheme.LIGHT_GRAY_COLOR;
        panel.add(label(t(seen == null ? "houseUnseen" : "houseSeen"), width, gray));
        var box = new JComboBox<String>(t("houseBoxes").split("\\|"));
        box.setSelectedIndex(Arrays.asList(BOXES).indexOf(HouseSettings.box(now)));
        String[] amulets = t("houseAmulets").split("\\|");
        var mounted = IntStream.range(0, amulets.length).mapToObj(i -> new JCheckBox(amulets[i],
            now.contains(MOUNTED[i]))).toArray(JCheckBox[]::new);
        // A superior garden has one of these.
        var garden = new JComboBox<String>(t("houseGardens").split("\\|"));
        garden.setSelectedIndex(HouseSettings.garden(now));
        var obelisk = new JCheckBox(t("houseObelisk"), now.contains("obelisk"));
        obelisk.setToolTipText(t("houseObeliskTip"));
        JTextField portals = new JTextField(HouseSettings.places(now, PORTAL));
        JTextField nexus = new JTextField(HouseSettings.places(now, NEXUS));
        long account = houseOf;
        ActionListener save = e -> {
            var amulet = IntStream.range(0, amulets.length).filter(i -> mounted[i].isSelected())
                .mapToObj(i -> MOUNTED[i]).collect(Collectors.toSet());
            editHouse(account, HouseSettings.features(BOXES[box.getSelectedIndex()], amulet,
                garden.getSelectedIndex(), obelisk.isSelected(), portals.getText(), nexus.getText()));
        };
        box.addActionListener(save);
        for (var amulet : mounted) {
            amulet.addActionListener(save);
        }
        garden.addActionListener(save);
        obelisk.addActionListener(save);
        for (JTextField places : new JTextField[]{portals, nexus}) {
            places.setToolTipText(t("housePlaces"));
            BigWindow.typable(places);
            places.addActionListener(save);
        }
        var parts = new ArrayList<JComponent>(List.of(box));
        parts.addAll(List.of(mounted));
        parts.addAll(List.of(garden, obelisk, label("Portals:", width, gray), portals,
            label("Portal nexus:", width, gray), nexus));
        for (var part : parts) {
            part.setAlignmentX(Component.LEFT_ALIGNMENT);
            part.setOpaque(part instanceof JTextField);
            part.setMaximumSize(new Dimension(width, part.getPreferredSize().height));
            panel.add(part);
        }
        if (seen != null) {
            add(panel, 6, button("Show its plan",
                () -> routes.forScreens(screen -> screen.view.focus(worldPoint(seen.arrival)))));
        }
        return panel;
    }

    /** Dropped when another account (or none) is logged in than the panel was made for. */
    void editHouse(long account, Set<String> features) {
        routes.clientThread.invokeLater(() -> {
            if (houseOf != account) {
                return;
            }
            house.replaceFeatures(features);
            writeHouse(features);
            routes.state = routes.capture();
            SwingUtilities.invokeLater(routes::settingsChanged);
        });
    }

    final IntFunction<JComponent> houseSection = this::housePanel;

    void openHouse() {
        routes.forScreens(screen -> screen.showHouse(houseSection));
    }

    /** The maps painted again, and an open house panel built again with what is known now (a scan, the other map). */
    void houseChanged() {
        SwingUtilities.invokeLater(() -> {
            routes.repaint();
            routes.forScreens(screen -> screen.updateHouse(houseSection));
        });
    }

    /** The route data, loaded meanwhile, joined with the plan under the plan's lock; null when stopped. */
    synchronized void setCollision(CollisionMap map) {
        collision = map;
        if (map != null) {
            map.setHouse(plan);
        }
    }

    /** No account's house (logged out, or stopped). Client thread. */
    void forget() {
        house.reset();
        houseOf = Long.MIN_VALUE;
        houseLook.set(new BufferedImage[4]);
        setPlan(null);
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event) {
        if (event.getGameState() == GameState.LOGGED_IN && routes.client.getAccountHash() != houseOf) {
            loadHouse();
        } else if (event.getGameState() == GameState.LOADING) {
            house.reloaded();
        } else if (event.getGameState() == GameState.LOGIN_SCREEN) {
            forget();
        }
    }
}
