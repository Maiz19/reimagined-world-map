package com.hdmapreforged;

import static com.hdmapreforged.BaseMap.*;
import static java.awt.Component.*;
import static java.util.Locale.*;
import static java.awt.BorderLayout.*;
import static java.awt.RenderingHints.*;
import static java.util.Collections.*;
import static java.awt.Font.*;
import static java.awt.Color.*;
import static com.hdmapreforged.PoiIcons.*;
import static java.util.Comparator.*;
import static java.lang.Math.*;

import java.util.concurrent.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.*;
import java.util.*;
import java.util.List;
import java.util.function.*;
import java.util.regex.*;
import java.util.stream.*;
import javax.swing.*;
import net.runelite.api.coords.*;
import net.runelite.client.ui.*;
import lombok.*;
import net.runelite.client.util.*;
import com.hdmapreforged.Spawns.Group;
import com.hdmapreforged.WorldData.Drop;

/** What a search found (a monster, an item's sources, a kind of place), marked on the map and listed. Swing thread. */
final class SearchResults implements MapView.Overlay, MapView.HitLayer {
    interface Card {
        void show(IntFunction<JComponent> section);

        /** A background answer (a tile check): re-render only while this search is what is shown. */
        default void update(IntFunction<JComponent> section) {
            show(section);
        }
    }

    @RequiredArgsConstructor
    static final class Result {
        final String title, page, summary;
        final List<Group> groups;
        final String credit;
        String kind;
        /** The NPC whose picture heads it; -1 for none. */
        int picture = -1;
        /** The item it is about, for its picture and price; -1 for none. */
        int item = -1;
        /** A monster's drops: its standard table's. */
        List<Drop> drops = emptyList();
        /** All its page's drop tables, the standard one first. */
        List<WorldData.Table> tables = emptyList();
        /** An item's droppers. */
        List<ItemSources.Dropper> droppers = emptyList();
    }

    static final String SHOPS = "Shops";
    static final String GROUND = "On the ground";
    static final Pattern FRACTION = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*/\\s*(\\d+(?:\\.\\d+)?)");

    static final Color DOT = new Color(70, 220, 90), SHOP = new Color(255, 196, 60);
    static final Color GROUND_COLOR = new Color(120, 200, 255), DOT_EDGE = new Color(0, 50, 10, 230);
    static final Color LABEL_BACKGROUND = new Color(16, 16, 20, 215);
    static final Font LABEL_FONT = PlayerMarker.FONT;
    static final Color LINK = ColorScheme.GRAND_EXCHANGE_LIMIT;

    final MapView view;
    final Supplier<WorldData> world;
    final Card card;
    @Setter
    Function<WorldPoint, MapData.Place> places = point -> null;
    Result result;
    String failed;
    /** What the search failed to find, for its Wiki button. */
    String failedName;
    /** The results clicked through from (a drop, a dropper), for Back. */
    /** The results clicked through from, newest last, at most 20; Back takes the newest. */
    final Deque<Visit> history = new ArrayDeque<>();
    /** The tab shown of the current result (a kind of place, "drops", "droppers"); null for the first. */
    String tab;
    /** Which of a monster's drop tables the Drops tab shows. */
    int table;
    /** How the Shops tab is ordered ("nearest", "stock", "name"); null for nearest while the player is known, else stock. */
    String shopSort;
    /** How a drops table is ordered within its parts ("page", "rarity", "value"); kept from one search to the next. */
    String dropSort = "page";
    @Setter
    Prices prices = Prices.NONE;

    /** The Grand Exchange prices the game client knows ({@link ItemNames}). */
    interface Prices {
        Prices NONE = id -> -1;

        /** -1 while not known. */
        int price(int id);

        /** Looks up what is not known yet, then runs {@code done} on the Swing thread. */
        default void resolve(Collection<Integer> ids, Runnable done) {
        }
    }

    /** A result left by clicking through (a drop, a dropper), with the tab and drop table it showed. */
    @RequiredArgsConstructor
    static final class Visit {
        final Result result;
        final String tab;
        final int table;
    }

    SearchResults(MapView view, Supplier<WorldData> world, Card card) {
        this.view = view;
        this.world = world;
        this.card = card;
        view.addOverlay(this);
        view.hitLayers.add(this);
        filter.setAlignmentX(LEFT_ALIGNMENT);
        filter.setToolTipText(PoiType.t("dFilterTip"));
        BigWindow.typable(filter);
        // Escape empties it; once empty, the key is the window's (closing the map over the game).
        HintField.bind(filter, KeyEvent.VK_ESCAPE, "empty", () -> filter.setText(""), () -> !filter.getText().isEmpty());
        // Typed in the filter: the rows that match show from the top; nothing else is built again.
        HintField.onEdit(filter, () -> {
            filterRows();
            listed.scrollRectToVisible(new Rectangle(0, 0, 1, 1));
        });
    }

    /**
     * Filters the Dropped by list by name as one types: one field however often the panel is built, so the typing goes
     * on when a late answer builds it again ({@link #rebuild}).
     */
    final JTextField filter = new HintField(() -> "Filter by name");
    /** The Dropped by list as last built: the line saying none matches, then each row after the space above it. */
    JPanel listed = column();
    /** A Dropped by row's monster, kept on the row for the filter. */
    static final String NAMED = "monster";

    /** Shows the rows whose monster's name holds the words typed, each with the space above it, else that none does. */
    void filterRows() {
        var parts = listed.getComponents();
        boolean any = false;
        for (int i = 2; i < parts.length; i += 2) {
            boolean shown = matches((String) ((JComponent) parts[i]).getClientProperty(NAMED), filter.getText());
            parts[i - 1].setVisible(shown);
            parts[i].setVisible(shown);
            any |= shown;
        }
        if (parts.length > 0) {
            parts[0].setVisible(!any);
        }
    }

    /** Whether {@code name} holds every word typed, in any letters and order: "gi fire" finds "Fire giant". Pure. */
    static boolean matches(String name, String typed) {
        String lower = name.toLowerCase(ROOT);
        return Arrays.stream(typed.toLowerCase(ROOT).trim().split("\\s+")).allMatch(lower::contains);
    }

    /**
     * Runs {@code action} (building a panel again, opening a menu) so that it never takes focus from the game: a
     * {@code field} being typed in keeps the keys, also where it is shown again.
     */
    static void keepFocus(JComponent field, Runnable action) {
        boolean typing = field.isFocusOwner();
        action.run();
        if (typing) {
            field.requestFocusInWindow();
        }
    }

    @Setter
    Consumer<Tour.Stop> stopAdder;
    @Setter
    NpcImages npcPictures = new NpcImages((id, done) -> done.accept(null));
    @Setter
    BiConsumer<Integer, JLabel> itemPictures = (id, label) -> { };

    @Setter
    Consumer<List<WorldPoint>> fastest;

    /**
     * What to call a place of the search: the map it is shown on ({@link #mapOf}) when that is a dungeon's, so the name
     * and the map agree (the Brimhaven Dungeon, not the Ardent Ocean Underground around it); else the map's naming.
     */
    MapData.Place placeOf(WorldPoint point) {
        var maps = view.maps();
        var map = maps == null ? null : world.get().drawnBy(maps).get(point);
        return map != null && map.id != SURFACE && map.id != FULL ? MapScreen.mapPlace(map, point)
            : places.apply(point);
    }

    void findMonster(String name) {
        findMonster(name, null);
    }

    /** The monster, its Drops tab on the table named {@code table} (a dropper's: the version dropping the item). */
    void findMonster(String name, String table) {
        var found = world.get().spawns(name);
        Result shown = found == null ? null : monsterResult(found, this::placeOf, view.near());
        answer(shown, "No places of \"" + name + "\" are known.", name,
            shown == null ? 0 : tableNamed(shown.tables, table));
    }

    /** Which of a monster's drop tables has this name; the first when none has, or for null. Pure. */
    static int tableNamed(List<WorldData.Table> tables, String name) {
        for (int i = 0; i < tables.size(); i++) {
            if (tables.get(i).name.equals(name)) {
                return i;
            }
        }
        return 0;
    }

    /** What a search for {@code name} found, on its drop table {@code table}, else {@code none}: why nothing shows. */
    void answer(Result found, String none, String name, int table) {
        if (found != null) {
            if (result != null && result != found) {
                history.addLast(new Visit(result, tab, this.table));
                if (history.size() > 20) {
                    history.removeFirst();
                }
            }
            show(found, null, table);
            return;
        }
        result = null;
        tab = null;
        failed = none;
        failedName = name;
        refresh();
    }

    /** A monster's places by name, nearest first, each with how many stand there at once. Pure. */
    static Result monsterResult(Spawns found, Function<WorldPoint, MapData.Place> places, WorldPoint player) {
        var groups = named(found.groups, places, "Elsewhere");
        Result shown = new Result(found.page, found.page, "In " + places(groups.size()) + ", marked with green arrows"
            + (player != null ? ", nearest first" : "") + PoiType.t("t2"),
            nearestFirst(groups, player), null);
        shown.picture = found.npc;
        shown.drops = found.tables.isEmpty() ? emptyList() : found.tables.get(0).drops;
        shown.tables = found.tables;
        return shown;
    }

    /**
     * The groups under the names of their places ({@code unnamed} where no place is known; "" keeps the group's own
     * words): places of one name say which side of it they are, an upper floor says so, else the same side is
     * numbered. Pure.
     */
    static List<Group> named(List<Group> found, Function<WorldPoint, MapData.Place> places,
        String unnamed) {
        var known = new ArrayList<MapData.Place>();
        var uses = new HashMap<String, Integer>();
        for (var group : found) {
            var place = places.apply(group.center());
            known.add(place);
            if (place != null) {
                uses.merge(place.name, 1, Integer::sum);
            }
        }
        var groups = new ArrayList<Group>();
        var numbered = new HashMap<String, Integer>();
        for (int i = 0; i < found.size(); i++) {
            var group = found.get(i);
            var place = known.get(i);
            String name = place == null ? unnamed : place.name;
            if (name.isEmpty()) {
                groups.add(group);
                continue;
            }
            if (place != null && uses.get(place.name) > 1 && !place.point.equals(group.center())) {
                name += ", " + MapScreen.compass(place.point, group.center());
            }
            int floor = group.center().getPlane();
            int n = numbered.merge(name + ":" + floor, 1, Integer::sum);
            groups.add(group.at(floor > 0 && numbered.containsKey(name + ":0") ? name + ", upstairs"
                : n > 1 ? name + " (" + n + ")" : name));
        }
        return groups;
    }

    void findItem(String name) {
        var found = world.get().item(name);
        answer(found == null ? null : itemResult(found, this::placeOf, view.near()),
            PoiType.t("dNothingKnown", name), name, 0);
    }

    void showKind(KindIndex.Kind kind, Function<WorldPoint, String> placeName) {
        var groups = kindGroups(kind, placeName, view.near());
        Result shown = new Result(kind.label, kind.page, places(groups.size()) + ", marked with green arrows" + (view.near() != null ? ", nearest first" : "") + ". Click one to go there.",
            groups, kind.type == null ? PoiType.t("t3") : null);
        shown.kind = kind.label;
        show(shown);
    }

    /** A kind's places, those close together in one town merged ("Catherby, 4 spots"). */
    static List<Group> kindGroups(KindIndex.Kind kind, Function<WorldPoint, String> placeName, WorldPoint player) {
        var groups = new ArrayList<Group>();
        entries:
        for (var entry : kind.entries) {
            String place = Objects.requireNonNullElse(placeName.apply(entry.point), "");
            String name = kind.type == null && !place.isEmpty() ? place : entry.name;
            for (var group : groups) {
                var first = group.points.get(0);
                if (!place.isEmpty() && group.location.equals(place) && group.name.equals(name)
                    // Spots 48 tiles apart or closer on one floor, one after another, are one place.
                    && first.distanceTo(entry.point) <= 48) {
                    group.points.add(entry.point);
                    continue entries;
                }
            }
            var group = new Group(name, place,
                new ArrayList<>(singletonList(entry.point)), null, entry.detail == null ? "" : entry.detail,
                null);
            group.poi = entry.poi;
            groups.add(group);
        }
        groups.replaceAll(g -> {
            int n = g.points.size();
            return n > 1 ? g.withNote(n + " spots" + (g.note.isEmpty() ? "" : " · " + g.note)) : g;
        });
        return nearestFirst(groups, player);
    }

    /**
     * Where an item comes from: the shops (gold arrows) and the spots on the ground (blue), each under the name of its
     * place, nearest first. Pure.
     */
    static Result itemResult(ItemSources found, Function<WorldPoint, MapData.Place> places, WorldPoint player) {
        var shops = new ArrayList<Group>();
        for (var store : found.stores) {
            var shop = new Group(store.shop, "", singletonList(store.point), SHOPS,
                ItemSources.stockText(store.stock), SHOP);
            shop.stock = store.stock;
            shops.add(shop);
        }
        var grounds = new ArrayList<Group>();
        for (var ground : found.grounds) {
            var group = new Group(found.page, "", singletonList(ground.point), GROUND,
                ground.count + " here", GROUND_COLOR);
            group.seen = ground.count;
            grounds.add(group);
        }
        var parts = new ArrayList<String>();
        if (!shops.isEmpty()) {
            parts.add("sold in " + plural(shops.size(), "shop"));
        }
        if (!grounds.isEmpty()) {
            parts.add("lying in " + places(grounds.size()));
        }
        if (!found.droppers.isEmpty()) {
            parts.add("dropped by " + plural(found.droppers.size(), "monster"));
        }
        String summary = Character.toUpperCase(parts.get(0).charAt(0)) + String.join(", ", parts).substring(1)
            + (shops.isEmpty() && grounds.isEmpty() ? "." : ": marked on the map" + (player != null ? ", nearest first"
            : "") + ". Click a place to see it.");
        var groups = new ArrayList<>(nearestFirst(named(shops, places, ""), player));
        groups.addAll(nearestFirst(named(grounds, places, ""), player));
        Result shown = new Result(found.page, found.page, summary, groups, null);
        shown.item = found.item;
        var droppers = new ArrayList<>(found.droppers);
        droppers.sort(comparingDouble((ItemSources.Dropper d) -> rarity(d.rarity)).thenComparing(d -> d.name));
        shown.droppers = droppers;
        return shown;
    }

    /** How often a drop comes, as a number to sort by: always first, then by chance, then by its words. */
    static double rarity(String words) {
        String w = words.toLowerCase(ROOT);
        Matcher fraction = FRACTION.matcher(w);
        if (fraction.find()) {
            double chance = Double.parseDouble(fraction.group(1)) / Double.parseDouble(fraction.group(2));
            return chance > 0 ? -chance : 0;
        }
        if (w.startsWith("always")) {
            return -1;
        }
        // The words, as the chances they stand for, roughly.
        String[] kinds = {"very common", "common", "uncommon", "very rare", "rare"};
        double[] chances = {1 / 4.0, 1 / 10.0, 1 / 50.0, 1 / 5000.0, 1 / 500.0};
        for (int i = 0; i < kinds.length; i++) {
            if (w.startsWith(kinds[i])) {
                return -chances[i];
            }
        }
        return 1;
    }

    static List<Group> nearestFirst(List<Group> groups, WorldPoint player) {
        var sorted = new ArrayList<>(groups);
        if (player != null) {
            sorted.sort(comparingLong(g -> distance(g.center(), player)));
        }
        return sorted;
    }

    static String places(int n) {
        return plural(n, "place");
    }

    static String plural(int n, String thing) {
        return n + " " + thing + (n == 1 ? "" : "s");
    }

    /** Squared, as the crow flies; underground as above it (most dungeons lie 6400 tiles north of what is above them). */
    static long distance(WorldPoint a, WorldPoint b) {
        long dx = a.getX() - b.getX(), dy = a.getY() % 6400 - b.getY() % 6400;
        return dx * dx + dy * dy;
    }

    void refresh() {
        keepFocus(filter, () -> card.show(this::section));
        view.repaint();
    }

    void show(Result found) {
        show(found, null, 0);
    }

    /** Shows a result on {@code tab} (null: its first) and, for a monster, on its drop table {@code table}. */
    void show(Result found, String tab, int table) {
        filter.setText("");
        resetFound();
        foundPois.clear();
        result = found;
        this.tab = tab;
        this.table = table;
        // The place clicked before is no longer what is shown. The map stays: a place is gone to by a click.
        view.select(null);
        refresh();
        // Asks the game for the prices of what the result shows, and shows them when they come.
        var ids = new HashSet<Integer>();
        if (found.item >= 0) {
            ids.add(found.item);
        }
        found.drops.forEach(drop -> ids.add(drop.item));
        found.tables.forEach(t -> t.drops.forEach(drop -> ids.add(drop.item)));
        if (!ids.isEmpty()) {
            prices.resolve(ids, () -> {
                if (result == found) {
                    keepFocus(filter, () -> card.update(this::section));
                }
            });
        }
    }

    void clear() {
        result = null;
        resetFound();
        history.clear();
        card.show(null);
        view.repaint();
    }

    /** Forgets the caches a shown result fills (tags, centers, per-group lookups) and any failure message. */
    void resetFound() {
        failed = null;
        failedName = null;
        tags.clear();
        centers.clear();
        groupBounds.clear();
        groupMaps.clear();
    }

    /** Whether no map draws this place: going there would show only black. */
    boolean undrawn(Group group) {
        return mapOf(group) == null;
    }

    void look(Group group) {
        var map = mapOf(group);
        if (map == null) {
            return;
        }
        view.setFollowing(false);
        view.remember(map);
        // Where the map draws it: a place drawn elsewhere (Dagannoth Kings' lair) would otherwise land in a corner.
        view.showMap(map, MapView.shownOn(map, standing(group.center(), view.ground())), max(view.targetZoom(), 2));
        view.select(iconOf(group, map));
    }

    Poi iconOf(Group group, BaseMap map) {
        if (group.poi != null) {
            return group.poi;
        }
        var c = standing(group.center(), view.ground());
        boolean shop = SHOPS.equals(group.category);
        return view.searchExtras().stream().filter(poi -> shop && poi.type == PoiType.SHOP
            && poi.location.distanceTo(c) <= WorldData.SHOP_RADIUS)
            .min(comparingInt(poi -> poi.location.distanceTo2D(c))).orElseGet(() -> foundPois.computeIfAbsent(group, g -> {
            String title = result == null ? g.name : result.title, name = placeName(g, title), note = dotted(g.note);
            String page = shop ? g.name : result != null && result.page != null ? result.page : g.name;
            return new Poi(shop ? PoiType.SHOP : PoiType.FOUND, name.equals(title) || shop ? name : title + ": " + name,
                c, map, null, Needs.NONE, page, null, note.isEmpty() ? null : note);
        }));
    }

    /** One per place of the result shown, so a click and a hover pick the same. */
    final Map<Group, Poi> foundPois = new IdentityHashMap<>();

    /** A place's arrows on the map: clicked, the place is selected, and the map shows the way there. */
    public Poi hit(Point point, Projection projection) {
        Result shown = result;
        var viewMap = projection.map();
        if (shown == null || viewMap == null) {
            return null;
        }
        double r = arrowRadius(projection.zoom());
        for (var group : shown.groups) {
            var map = mapOf(group);
            if (map == null || viewMap.id != FULL && map != viewMap) {
                continue;
            }
            var first = group.points.get(0);
            var moved = projection.shown(first);
            for (var p : group.points) {
                double x = projection.screenX(p.getX() + moved.getX() - first.getX() + 0.5);
                double y = projection.screenY(p.getY() + moved.getY() - first.getY() + 0.5) - r;
                if (hypot(point.x - x, point.y - y) <= r * 2) {
                    return iconOf(group, map);
                }
            }
        }
        return null;
    }

    /** Arrows grow a little when zoomed in, so a spawn tile stays easy to spot. */
    static double arrowRadius(double zoom) {
        return zoom < -1 ? 3 : zoom < 2 ? 4.5 : 4.5 + min(4, (zoom - 2) * 1.5);
    }

    void lookAtPoint(WorldPoint at) {
        if (result != null && at != null) {
            result.groups.stream().filter(group -> group.points.contains(at)).findFirst().ifPresent(this::look);
        }
    }

    /** Each place's map, worked out once per result and maps, not in every frame. */
    final Map<Group, Optional<BaseMap>> groupMaps = new IdentityHashMap<>();
    Map<WorldPoint, BaseMap> groupMapsFor;
    BaseMaps groupMapsFrom;

    /** The map that draws a place: where maps overlap as our map server worked it out ({@link WorldData#drawnBy}). */
    BaseMap mapOf(Group group) {
        var maps = view.maps();
        var placeMaps = world.get().drawnBy(maps);
        if (groupMapsFor != placeMaps || groupMapsFrom != maps) {
            groupMaps.clear();
            groupMapsFor = placeMaps;
            groupMapsFrom = maps;
        }
        return groupMaps.computeIfAbsent(group, g -> Optional.ofNullable(mapOf(g, maps, placeMaps, view.ground())))
            .orElse(null);
    }

    final Map<Group, int[]> groupBounds = new IdentityHashMap<>();

    int[] bounds(Group group) {
        return groupBounds.computeIfAbsent(group, g -> {
            int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
            for (var p : g.points) {
                b[0] = min(b[0], p.getX());
                b[1] = min(b[1], p.getY());
                b[2] = max(b[2], p.getX());
                b[3] = max(b[3], p.getY());
            }
            return b;
        });
    }

    /**
     * The map a place is shown on: the map server's answer for its spot, or where one stands there on another floor
     * ({@link #standing}) the map showing a player standing there.
     */
    static BaseMap mapOf(Group group, BaseMaps maps, Map<WorldPoint, BaseMap> placeMaps,
        Predicate<WorldPoint> ground) {
        if (maps == null) {
            return null;
        }
        var c = group.center();
        var at = standing(c, ground);
        return at.equals(c) && placeMaps.containsKey(c) ? placeMaps.get(c) : maps.find(at);
    }

    /**
     * Where one stands at a place: its spot, or beside it on its floor (a counter, a tree), else the nearest floor there
     * that is ground. A place's floor is given as its map draws it: the Brimhaven Dungeon's raised paths on floor
     * 1, walked on floor 2.
     */
    static WorldPoint standing(WorldPoint p, Predicate<WorldPoint> ground) {
        if (ground == null || groundNear(p, ground)) {
            return p;
        }
        for (int d = 1; d < 4; d++) {
            for (int floor : new int[]{p.getPlane() + d, p.getPlane() - d}) {
                var other = new WorldPoint(p.getX(), p.getY(), floor);
                if (floor >= 0 && floor < 4 && groundNear(other, ground)) {
                    return other;
                }
            }
        }
        return p;
    }

    /** Ground at the spot or within two tiles of it, on its floor. */
    static boolean groundNear(WorldPoint p, Predicate<WorldPoint> ground) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                if (ground.test(new WorldPoint(p.getX() + dx, p.getY() + dy, p.getPlane()))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The corners after the tip, in radii: right, up. */
    static final double[][] ARROW = {{-1.2, 1.4}, {-0.45, 1.4}, {-0.45, 2.6}, {0.45, 2.6}, {0.45, 1.4},
        {1.2, 1.4}};

    static Path2D arrow(double x, double y, double r) {
        var arrow = new Path2D.Double();
        arrow.moveTo(x, y);
        for (var corner : ARROW) {
            arrow.lineTo(x + r * corner[0], y - r * corner[1]);
        }
        arrow.closePath();
        return arrow;
    }

    static BufferedImage dotIcon() {
        return sprite(14, 14, 1, g -> paintArrow(g, arrow(7, 13, 4.4), DOT, new BasicStroke()));
    }

    static void paintArrow(Graphics2D g, Path2D arrow, Color fill, BasicStroke edge) {
        g.setColor(fill);
        g.fill(arrow);
        g.setColor(DOT_EDGE);
        g.setStroke(edge);
        g.draw(arrow);
    }

    /** A result's panel: the header (title, summary, buttons, tabs, sort) kept in view, the list below it scrolling. */
    JComponent section(int width) {
        JPanel header = column();
        if (result == null) {
            header.add(grey(failed != null ? failed : "Nothing found.", width));
            // Buttons may reach past the text's width: HTML text comes out wider than asked.
            add(header, 6, wrapLabels(buttons(failedName), 4, width + 70));
            return header;
        }
        if (!history.isEmpty()) {
            Visit back = history.peekLast();
            add(header, 0, link("← Back to " + back.result.title, () -> {
                history.pollLast();
                show(back.result, back.tab, back.table);
            }, width));
            header.add(Box.createVerticalStrut(4));
        }
        var title = heading(result.title, width - 70);
        if (result.picture >= 0) {
            header.add(pictureRow(pictureLabel(result.picture, NpcImages.LARGE), title));
        } else if (result.item >= 0) {
            JLabel icon = pictureIcon(PICTURE);
            itemPictures.accept(result.item, icon);
            header.add(pictureRow(icon, title));
        } else {
            header.add(title);
        }
        header.add(grey(result.summary, width));
        int price = result.item >= 0 ? prices.price(result.item) : -1;
        if (price >= 0) {
            header.add(grey("Grand Exchange " + gp(price), width));
        }
        add(header, 6, wrapLabels(buttons(result.page), 4, width + 70));
        var tabs = tabs();
        if (tabs.isEmpty()) {
            return header;
        }
        String shown = tabs.stream().anyMatch(t -> t[0].equals(tab)) ? tab : tabs.get(0)[0];
        var labels = new ArrayList<JComponent>();
        for (var t : tabs) {
            boolean active = t[0].equals(shown);
            JLabel label = optionLabel(t[1], active);
            label.setBorder(BorderFactory.createMatteBorder(0, 0, 2, 0, active ? WHITE : new Color(0, 0, 0, 0)));
            labels.add(clickable(label, () -> {
                tab = t[0];
                refresh();
            }, !active));
        }
        add(header, 8, wrapLabels(labels, 12, width));
        JPanel body = column();
        if (shown.equals("drops")) {
            dropsTab(header, body, width);
        } else if (shown.equals("droppers")) {
            droppersTab(header, body, width);
        } else {
            placesTab(shown, header, body, width);
        }
        if (result.credit != null) {
            add(body, 10, grey(result.credit, width));
        }
        return new Sections(header, body);
    }

    /** The tabs of the result shown, {key, label}: places by kind (a monster's all as "Locations"), drops, droppers. */
    List<String[]> tabs() {
        var counts = new LinkedHashMap<String, Integer>();
        result.groups.forEach(g -> counts.merge(category(g), 1, Integer::sum));
        var tabs = new ArrayList<String[]>();
        counts.forEach((category, n) -> tabs.add(new String[]{category, category + " (" + n + ")"}));
        var drops = shownDrops();
        if (!drops.isEmpty()) {
            tabs.add(new String[]{"drops", "Drops (" + drops.size() + ")"});
        }
        if (!result.droppers.isEmpty()) {
            tabs.add(new String[]{"droppers", "Dropped by (" + result.droppers.size() + ")"});
        }
        return tabs;
    }

    static String category(Group group) {
        return group.category == null ? "Locations" : group.category;
    }

    /** Which of the page's drop tables is chosen. */
    int chosenTable() {
        return min(table, max(0, result.tables.size() - 1));
    }

    List<Drop> shownDrops() {
        return result.tables.isEmpty() ? result.drops : result.tables.get(chosenTable()).drops;
    }

    /** The Drops tab: the table chosen, its order, then the drops under the parts of the page. */
    void dropsTab(JPanel header, JPanel body, int width) {
        if (result.tables.size() > 1) {
            add(header, 6, versions(result.tables, chosenTable(), width));
        }
        add(header, 6, choice(new String[][]{{"page", "Page order"}, {"rarity", "Rarity"}, {"value", "Value"}},
            dropSort, sort -> {
                dropSort = sort;
                refresh();
            }, width));
        var drops = dropOrder(shownDrops(), dropSort, prices::price);
        // 100%, the main table, the rare drop table, tertiary.
        String[][] parts = {{"a", "100%"}, {"", "Main drops"}, {"r", "Rare drop table"}, {"t", "Tertiary"}};
        for (var part : parts) {
            boolean first = true;
            for (var drop : drops) {
                if (!drop.part.equals(part[0])) {
                    continue;
                }
                if (first) {
                    add(body, 8, heading(part[1], width));
                    first = false;
                }
                JLabel icon = pictureIcon(PICTURE);
                itemPictures.accept(drop.item, icon);
                long value = value(prices.price(drop.item), drop.quantity);
                Runnable open = () -> findItem(drop.name);
                add(body, 3, clickableRow(pictureRow(icon, bold(link(drop.name, open, width - PICTURE - 8)),
                    grey(dotted(drop.rarity, drop.quantity, value >= 0 ? "GE " + gp(value) : null, drop.version),
                        width - PICTURE - 8)), open));
            }
        }
    }

    /** A list of more droppers than this gets a filter by name. */
    static final int FILTERED = 10;

    /**
     * The Dropped by tab: each monster with its picture, how often and many, and in how many places it stands; a long
     * list with a filter by name above it.
     */
    void droppersTab(JPanel header, JPanel body, int width) {
        if (result.droppers.size() > FILTERED) {
            filter.setPreferredSize(new Dimension(width, filter.getPreferredSize().height));
            filter.setMaximumSize(filter.getPreferredSize());
            add(header, 6, filter);
        }
        listed = column();
        listed.add(grey("No monster of that name drops it.", width));
        int shown = 0;
        for (var dropper : result.droppers) {
            // The first 1000 droppers get their picture, each asked of the map server once its row is in view, then
            // kept on disk.
            JLabel icon = shown++ < 1000 ? pictureLabel(dropper.npc, PICTURE) : pictureIcon(PICTURE);
            Runnable open = () -> findMonster(dropper.name, dropper.table);
            int w = width - PICTURE - 8;
            JPanel row = pictureRow(icon, bold(link(dropper.name, open, w)), grey(dotted(dropper.rarity,
                dropper.quantity, places(dropper.places), dropper.version), w));
            row.putClientProperty(NAMED, dropper.name);
            add(listed, 3, clickableRow(row, open));
        }
        body.add(listed);
        filterRows();
    }

    /** A tab of places: shops in the order chosen, the rest as the result lists them. */
    void placesTab(String shown, JPanel header, JPanel body, int width) {
        var groups = result.groups.stream().filter(g -> shown.equals(category(g)))
            .collect(Collectors.toList());
        if (shown.equals(SHOPS)) {
            String sort = shopSort != null ? shopSort : view.near() != null ? "nearest" : "stock";
            add(header, 6, choice(new String[][]{{"nearest", "Nearest"}, {"stock", "Stock"}, {"name", "Name"}}, sort,
                pick -> {
                    shopSort = pick;
                    refresh();
                }, width));
            groups.sort(shopOrder(sort, view.near(), result.title));
        }
        for (int i = 0; i < groups.size(); i++) {
            add(body, i == 0 ? 4 : 3, place(groups.get(i), width));
        }
    }

    /**
     * Shops in the order chosen: "nearest" the player (as listed, without one), "stock" fullest first, "name" by the
     * shop's, as the list names it.
     */
    static Comparator<Group> shopOrder(String sort, WorldPoint player, String title) {
        var byName = comparing((Group g) -> placeName(g, title),
            String.CASE_INSENSITIVE_ORDER);
        switch (sort) {
            case "stock":
                return (a, b) -> {
                    int byStock = ItemSources.byStock(a.stock, b.stock);
                    return byStock != 0 ? byStock : byName.compare(a, b);
                };
            case "name":
                return byName;
            default:
                return player == null ? (a, b) -> 0 : comparingLong(g -> distance(g.center(), player));
        }
    }

    /** Drops in the order chosen: "page" as listed, "rarity" surest first, "value" dearest first. */
    static List<Drop> dropOrder(List<Drop> drops, String sort, IntUnaryOperator price) {
        var sorted = new ArrayList<>(drops);
        var byName = comparing((Drop d) -> d.name, String.CASE_INSENSITIVE_ORDER);
        if (sort.equals("rarity")) {
            sorted.sort(comparingDouble((Drop d) -> rarity(d.rarity)).thenComparing(byName));
        } else if (sort.equals("value")) {
            sorted.sort(comparingLong((Drop d) -> {
                long value = value(price.applyAsInt(d.item), d.quantity);
                return value < 0 ? Long.MAX_VALUE : -value;
            }).thenComparing(byName));
        }
        return sorted;
    }

    static final Pattern NUMBER = Pattern.compile("\\d{1,9}");

    /**
     * What a drop is worth at the Grand Exchange: the price times the least that drops ("5-10": five); -1 while the
     * price is not known.
     */
    static long value(int price, String quantity) {
        Matcher number = NUMBER.matcher(quantity);
        return price < 0 ? -1 : price * (number.find() ? Long.parseLong(number.group()) : 1);
    }

    /** "1,234 gp". */
    static String gp(long amount) {
        return String.format(ROOT, "%,d gp", amount);
    }

    /** The choice of a monster's drop table: its versions (the Wilderness Slayer Cave), loot that is not a drop. */
    JComboBox<String> versions(List<WorldData.Table> tables, int chosen, int width) {
        var versions = plainChoices(new JComboBox<String>(tables.stream()
            .map(t -> t.name.isEmpty() ? "Standard" : t.name).toArray(String[]::new)));
        versions.setSelectedIndex(chosen);
        versions.setForeground(WHITE);
        versions.setFocusable(false);
        versions.setAlignmentX(LEFT_ALIGNMENT);
        versions.setPreferredSize(new Dimension(width, versions.getPreferredSize().height));
        versions.setMaximumSize(versions.getPreferredSize());
        versions.addItemListener(e -> {
            if (e.getStateChange() == ItemEvent.SELECTED) {
                table = versions.getSelectedIndex();
                refresh();
            }
        });
        return versions;
    }

    /** An item's picture is 36 by 32. */
    static final int PICTURE = 36;

    /** An NPC's picture {@code size} wide and high once it came; an empty square meanwhile. */
    JLabel pictureLabel(int npc, int size) {
        JLabel label = pictureIcon(size);
        npcPictures.show(npc, label);
        return label;
    }

    /**
     * The square a picture shows in, {@code size} wide and high, empty until it has one. No background: an opaque
     * see-through one was painted over itself each time a picture arrived, and flashed until the card was drawn again.
     */
    static JLabel pictureIcon(int size) {
        JLabel icon = new JLabel();
        icon.setPreferredSize(new Dimension(size, size));
        icon.setHorizontalAlignment(JLabel.CENTER);
        return icon;
    }

    /** A picture at the left, words stacked beside it. */
    static JPanel pictureRow(JComponent icon, JComponent... words) {
        JPanel row = panel(new BorderLayout(8, 0));
        row.add(icon, WEST);
        JPanel column = column();
        for (var word : words) {
            column.add(word);
        }
        row.add(column, CENTER);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, max(PICTURE, row.getPreferredSize().height)));
        return row;
    }

    JComponent place(Group group, int width) {
        JPanel row = column();
        String where = placeName(group, result.title);
        boolean undrawn = undrawn(group);
        row.add(bold(undrawn ? text(where, WHITE, true, width) : link(where, () -> look(group), width)));
        if (undrawn) {
            row.add(text("Not on the map yet", new Color(255, 190, 120), false, width));
        }
        var map = mapOf(group);
        int floor = group.points.get(0).getPlane();
        String facts = dotted(group.note, map != null && map.id != SURFACE && !map.name.equals(where) ? map.name
            : null, floor > 0 ? "Floor " + floor : null, away(view.near(), group.center()));
        if (!facts.isEmpty()) {
            row.add(grey(facts, width));
        }
        return undrawn ? row : clickableRow(row, () -> look(group));
    }

    /**
     * How far from the player: "120 tiles north-east", "1.6k tiles west"; across ground levels, which level the place
     * is on; null without a player, or at the spot.
     */
    static String away(WorldPoint player, WorldPoint place) {
        if (player == null || place.equals(player)) {
            return null;
        }
        boolean below = place.getY() >= MapView.UNDERGROUND_Y;
        if (player.getY() >= MapView.UNDERGROUND_Y != below) {
            return below ? "underground" : "on the surface";
        }
        int d = player.distanceTo2D(place);
        String tiles = d >= 1000 ? String.format(ROOT, "%.1fk tiles", d / 1000.0) : plural(d, "tile");
        return tiles + " " + MapScreen.compass(player, place);
    }

    /** A result's panel in two parts: the header stays in view while the list below it scrolls (the search panel). */
    static final class Sections extends JPanel {
        final JComponent body;

        Sections(JComponent header, JComponent body) {
            super(new BorderLayout(0, 4));
            setOpaque(false);
            setAlignmentX(LEFT_ALIGNMENT);
            this.body = body;
            add(header, NORTH);
            add(body, CENTER);
        }

        /** The list in a scroll pane of its own ({@code scroll} makes one), the header above it. */
        Sections scrolling(Function<JComponent, JScrollPane> scroll) {
            remove(body);
            JPanel top = new JPanel(new BorderLayout());
            top.setOpaque(false);
            top.add(body, NORTH);
            JScrollPane pane = scroll.apply(top);
            pane.setOpaque(false);
            pane.getViewport().setOpaque(false);
            add(pane, CENTER);
            return this;
        }
    }

    /** The parts in rows of at most {@code width}, each row a line of its own, so none is clipped. */
    static JPanel wrapLabels(List<? extends JComponent> parts, int gap, int width) {
        JPanel rows = column(), line = null;
        int used = 0;
        for (var part : parts) {
            int w = part.getPreferredSize().width;
            if (line == null || used + gap + w > width) {
                if (line != null) {
                    rows.add(Box.createVerticalStrut(4));
                }
                line = panel(new FlowLayout(FlowLayout.LEFT, 0, 0));
                rows.add(line);
                used = 0;
            } else {
                line.add(Box.createHorizontalStrut(gap));
                used += gap;
            }
            line.add(part);
            used += w;
        }
        return rows;
    }

    /** A row of choices ("Sort by  Nearest  Stock  Name"), the one chosen white; another clicked is picked. */
    static JComponent choice(String[][] options, String chosen, Consumer<String> pick, int width) {
        var parts = new ArrayList<JComponent>();
        JLabel sort = new JLabel("Sort by");
        sort.setForeground(SOFT);
        sort.setFont(FontManager.getRunescapeSmallFont());
        parts.add(sort);
        for (var option : options) {
            boolean active = option[0].equals(chosen);
            JLabel label = optionLabel(option[1], active);
            parts.add(active ? label : clickable(label, () -> pick.accept(option[0]), true));
        }
        return wrapLabels(parts, 10, width);
    }

    /** A tab or a choice, white and bold while it is the one shown. */
    static JLabel optionLabel(String text, boolean active) {
        JLabel label = InfoCard.plain(text);
        label.setForeground(active ? WHITE : LINK);
        label.setFont(label.getFont().deriveFont(active ? BOLD : PLAIN));
        return label;
    }

    static final Color LIT = new Color(255, 255, 255, 18);

    /** A see-through panel that can be lit faintly: a row under the mouse. */
    static final class Lit extends JPanel {
        boolean lit;

        Lit(LayoutManager layout) {
            super(layout);
        }

        void setLit(boolean lit) {
            if (this.lit != lit) {
                this.lit = lit;
                repaint();
            }
        }

        protected void paintComponent(Graphics g) {
            if (lit) {
                var g2 = (Graphics2D) g.create();
                g2.setRenderingHint(KEY_ANTIALIASING, VALUE_ANTIALIAS_ON);
                g2.setColor(LIT);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                g2.dispose();
            }
            super.paintComponent(g);
        }
    }

    /** A component that runs {@code action} when clicked anywhere on it, lit faintly while the mouse is over it. */
    static <T extends JComponent> T clickableRow(T row, Runnable action) {
        row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        row.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                action.run();
            }

            public void mouseEntered(MouseEvent e) {
                light(row, true);
            }

            public void mouseExited(MouseEvent e) {
                light(row, false);
            }
        });
        return row;
    }

    /** Lights the row {@code c} is on: the nearest panel up from it with a mouse listener of its own. */
    static void light(Component c, boolean lit) {
        for (Component at = c; at != null; at = at.getParent()) {
            if (at instanceof Lit && at.getMouseListeners().length > 0) {
                ((Lit) at).setLit(lit);
                return;
            }
        }
    }

    static String dotted(String... parts) {
        return Arrays.stream(parts).filter(part -> part != null && !part.isEmpty()).collect(Collectors.joining(" · "));
    }

    /** The label above a place on the map: what and how many stand there ("Fire giant ×7"), else where it is. */
    static String tag(Group group) {
        return group.entrance ? group.name + " (entrance)" : group.seen > 0 ? group.name + " ×" + group.seen
            : group.location.isEmpty() ? group.name : group.location;
    }

    static String placeName(Group group, String title) {
        return group.location.isEmpty() ? group.name.isEmpty() ? "Unnamed place" : group.name
            : group.name.equalsIgnoreCase(title) || group.name.equalsIgnoreCase(group.location) ? group.location
            : group.name + " – " + group.location;
    }

    /** The result's buttons, for {@link #wrapLabels} to lay out in rows. */
    List<JButton> buttons(String page) {
        var buttons = new ArrayList<JButton>();
        if (result != null && result.kind != null && stopAdder != null) {
            String kind = result.kind;
            buttons.add(tip(button("+ Stop", () -> stopAdder.accept(Tour.Stop.kind(kind))),
                PoiType.t("t4")));
        }
        var closest = closest();
        if (closest != null && fastest != null) {
            var places = new ArrayList<WorldPoint>();
            result.groups.stream().filter(group -> !undrawn(group)).forEach(group -> places.addAll(group.points));
            buttons.add(tip(button("Fastest", () -> fastest.accept(places)),
                PoiType.t("t5")));
        } else if (closest != null) {
            buttons.add(tip(button("Closest to me", () -> look(closest)), "Go to the place nearest to you"));
        }
        if (page != null) {
            buttons.add(button("Wiki", () -> LinkBrowser.browse(Wiki.pageUrl(page))));
        }
        buttons.add(button("Clear", this::clear));
        return buttons;
    }

    Group closest() {
        var me = view.near();
        return me == null || result == null ? null : closest(result.groups.stream()
            .filter(group -> !undrawn(group)).collect(Collectors.toList()), me);
    }

    static Group closest(List<Group> groups, WorldPoint me) {
        Group best = null;
        long bestDistance = Long.MAX_VALUE;
        for (var group : groups) {
            for (var p : group.points) {
                long d = distance(p, me);
                if (d < bestDistance) {
                    best = group;
                    bestDistance = d;
                }
            }
        }
        return best;
    }

    /** A see-through panel, left-aligned in a column. */
    static JPanel panel(LayoutManager layout) {
        JPanel panel = new Lit(layout);
        panel.setOpaque(false);
        panel.setAlignmentX(LEFT_ALIGNMENT);
        return panel;
    }

    static JPanel column() {
        JPanel panel = panel(null);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        return panel;
    }

    static void add(JPanel panel, int space, Component c) {
        panel.add(Box.createVerticalStrut(space));
        panel.add(c);
    }

    static <T extends JComponent> T capped(T c) {
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
        return c;
    }

    static JButton button(String text, Runnable action) {
        JButton button = new JButton(text);
        button.setFocusable(false);
        button.addActionListener(e -> action.run());
        return button;
    }

    /** A left-aligned button that looks like text. */
    static JButton flat(String text, String tip, Runnable action) {
        JButton button = button(text, action);
        button.setHorizontalAlignment(JButton.LEFT);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setToolTipText(tip);
        button.setAlignmentX(0);
        return button;
    }

    static JMenuItem item(JPopupMenu menu, String text, Runnable action) {
        JMenuItem item = plainItem(text);
        item.addActionListener(e -> action.run());
        return menu.add(item);
    }

    /** Names from the server's files as they are: a leading {@code <html>} must not render (remote images). */
    static JMenuItem plainItem(String text) {
        JMenuItem item = new JMenuItem();
        item.putClientProperty("html.disable", Boolean.TRUE);
        item.setText(text);
        return item;
    }

    /** The same for a choice's names. */
    static <T extends JComboBox<?>> T plainChoices(T box) {
        if (box.getRenderer() instanceof JComponent) {
            ((JComponent) box.getRenderer()).putClientProperty("html.disable", Boolean.TRUE);
        }
        return box;
    }

    static <T extends JComponent> T tip(T c, String tip) {
        c.setToolTipText(tip);
        return c;
    }

    static <T extends JComponent> T bold(T label) {
        label.setFont(label.getFont().deriveFont(BOLD));
        return label;
    }

    /** The second line of a row, the summary: lighter than the names, still easy to read. */
    static final Color SOFT = new Color(205, 205, 210);

    static JLabel grey(String text, int width) {
        return text(text, SOFT, false, width);
    }

    /** A list's heading. */
    static JLabel heading(String text, int width) {
        return text(text, WHITE, true, width);
    }

    /** A list's line: {@code bold} one point larger, a pixel of space above and below. */
    static JLabel text(String text, Color color, boolean bold, int width) {
        JLabel label = text(text, color, bold ? BOLD : PLAIN, bold ? 1 : 0, width);
        label.setBorder(BorderFactory.createEmptyBorder(1, 0, 1, 0));
        return label;
    }

    /** Text in {@code style} ({@link Font#BOLD} or plain), {@code larger} points above the usual size. */
    static JLabel text(String text, Color color, int style, float larger, int width) {
        JLabel label = new JLabel();
        // Font and colour before the text: changing either makes an HTML label parse its text again.
        if (style != PLAIN || larger != 0) {
            label.setFont(label.getFont().deriveFont(style, label.getFont().getSize2D() + larger));
        }
        label.setForeground(color);
        label.setAlignmentX(LEFT_ALIGNMENT);
        return setText(label, text, width);
    }

    /**
     * HTML labels wrap, but are slow to build: plain wherever the text fits on one line, else wrapped at {@code width}.
     * Plain text is never read as HTML (a name may start with {@code <html>}); a label set again may switch between them.
     */
    static JLabel setText(JLabel label, String text, int width) {
        boolean plain = text.indexOf('\n') < 0 && label.getFontMetrics(label.getFont()).stringWidth(text) <= width;
        label.putClientProperty("html.disable", plain ? Boolean.TRUE : null);
        label.setText(plain ? text : "<html><div style='width:" + width + "px'>" + InfoCard.escape(text) + "</div></html>");
        return label;
    }

    static JLabel link(String text, Runnable action, int width) {
        return clickable(text(text, LINK, false, width), action, true);
    }

    /** A label that runs {@code action} when clicked; {@code hover}: a link that turns white under the mouse. */
    static JLabel clickable(JLabel label, Runnable action, boolean hover) {
        clickableRow(label, action);
        if (hover) {
            label.addMouseListener(new MouseAdapter() {
                public void mouseEntered(MouseEvent e) {
                    label.setForeground(WHITE);
                }

                public void mouseExited(MouseEvent e) {
                    label.setForeground(LINK);
                }
            });
        }
        return label;
    }

    static final BasicStroke EDGE = new BasicStroke(1.2f);
    final Map<Group, WorldPoint> centers = new IdentityHashMap<>();

    /**
     * Arrows and each place's label as pictures made once: drawing hundreds of shapes and texts every frame was slow.
     * Labels for {@link #tagsDevice}; a label's colour follows from its place.
     */
    final Map<Group, BufferedImage> tags = new IdentityHashMap<>();
    double tagsDevice;
    final Map<Long, BufferedImage> arrows = new HashMap<>();

    static void cap(Map<?, ?> cache, int max) {
        if (cache.size() > max) {
            cache.clear();
        }
    }

    BufferedImage arrowSprite(Color fill, double r, double device) {
        long key = (long) fill.getRGB() << 32 | (round(r * 4) & 0xffffL) << 16 | round(device * 10) & 0xffffL;
        cap(arrows, 500);
        return arrows.computeIfAbsent(key, k -> {
            double w = r * 2.4 + 4, h = r * 2.6 + 4;
            return sprite(w, h, device, g -> paintArrow(g, arrow(w / 2, h - 2, r), fill, EDGE));
        });
    }

    static BufferedImage labelSprite(String label, Color color, FontMetrics metrics, double device) {
        int width = metrics.stringWidth(label) + 10, height = metrics.getHeight() + 2;
        return sprite(width + 2, height + 2, device, g -> {
            g.setRenderingHint(KEY_TEXT_ANTIALIASING, VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(LABEL_FONT);
            var box = new RoundRectangle2D.Double(1, 1, width, height, 8, 8);
            g.setColor(LABEL_BACKGROUND);
            g.fill(box);
            g.setColor(color);
            g.draw(box);
            g.setColor(WHITE);
            MapView.text(g, label, 6, 1 + metrics.getHeight() - metrics.getDescent());
        });
    }

    static final Map<Color, Color> FADED = new ConcurrentHashMap<>();

    public void paint(Graphics2D g, Projection projection) {
        Result shown = result;
        var viewMap = projection.map();
        if (shown == null || viewMap == null) {
            return;
        }
        double zoom = projection.zoom(), r = arrowRadius(zoom);
        g.setFont(LABEL_FONT);
        var metrics = g.getFontMetrics();
        double device = max(1, min(4, abs(g.getTransform().getScaleX())));
        cap(groupBounds, 5000);
        cap(tags, device != tagsDevice ? -1 : 5000);
        tagsDevice = device;
        for (var group : shown.groups) {
            var b = bounds(group);
            // A place the map draws elsewhere (the Kalphite Lair, the Brimhaven Dungeon's raised paths) moves as its first
            // point does, on the floor one stands on there.
            var first = standing(group.points.get(0), view.ground());
            var moved = projection.shown(first);
            int dx = moved.getX() - first.getX(), dy = moved.getY() - first.getY();
            double left = projection.screenX(b[0] + dx), right = projection.screenX(b[2] + 1 + dx);
            double top = projection.screenY(b[3] + 1 + dy), bottom = projection.screenY(b[1] + dy);
            if (right < -150 || left > projection.width() + 150 || bottom < -20 || top > projection.height() + 60) {
                continue;
            }
            var map = mapOf(group);
            if (map == null || viewMap.id != FULL && map != viewMap) {
                continue;
            }
            Color color = group.color != null ? group.color : DOT;
            Color fill = moved.getPlane() != projection.plane()
                ? FADED.computeIfAbsent(color, c -> RouteFeature.alpha(c, 110)) : color;
            BufferedImage arrow = arrowSprite(fill, r, device);
            double half = arrow.getWidth() / device / 2, tall = arrow.getHeight() / device - 2;
            for (var p : group.points) {
                double x = projection.screenX(p.getX() + dx + 0.5), y = projection.screenY(p.getY() + dy + 0.5);
                if (x < -r * 2 || y < -r || x > projection.width() + r * 2 || y > projection.height() + r * 3) {
                    continue;
                }
                blit(g, arrow, x - half, y - tall);
            }
            if (zoom < -1.5) {
                continue;
            }
            cap(centers, 5000);
            var c = centers.computeIfAbsent(group, Group::center);
            double x = projection.screenX(c.getX() + dx + 0.5), y = projection.screenY(c.getY() + dy + 0.5) - r * 3 - 4;
            BufferedImage tag = tags.computeIfAbsent(group, k -> labelSprite(tag(group), color, metrics, device));
            double width = tag.getWidth() / device;
            if (x + width / 2.0 < 0 || x - width / 2.0 > projection.width() || y < -20 || y > projection.height() + 20) {
                continue;
            }
            blit(g, tag, x - width / 2.0 - 1, y - metrics.getHeight() - 1);
        }
    }
}
