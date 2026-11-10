package com.hdmapreforged;

import static com.hdmapreforged.PoiType.t;
import static javax.swing.BorderFactory.*;
import static com.hdmapreforged.MapView.*;
import static com.hdmapreforged.PoiIcons.*;
import static com.hdmapreforged.SearchResults.*;
import static javax.swing.JLayeredPane.PALETTE_LAYER;
import static com.hdmapreforged.MapScreen.SearchMode.*;
import static com.hdmapreforged.SearchIndex.Type.*;
import static java.lang.Math.*;

import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.function.*;
import javax.swing.*;
import javax.swing.Timer;
import lombok.*;
import net.runelite.api.coords.*;
import net.runelite.client.ui.*;
import com.hdmapreforged.MapData.Place;

/** The map with its controls and detail card: in a window of its own, or over the game. */
class MapScreen extends JPanel implements Listener {
    static final int MAX_RESULTS = 20, NEAREST = 10, FLOATING_WIDTH = 290;
    /** The search's panel is wider: its lists hold pictures and places with their words. */
    static final int SEARCH_WIDTH = 380, SEARCH_TEXT = 195 + SEARCH_WIDTH - FLOATING_WIDTH, FLOATING_TEXT = 195;

    final MapView view;
    final InfoCard card;
    final JScrollPane cardScroll;
    final Floating cardFloat = new Floating("Details", () -> closeCard());
    /** Closed, the search's arrows and labels leave the map too. */
    final Floating npcFloat = new Floating("Search", () -> clearSearch());
    final Floating toursFloat = new Floating("Custom routes", () -> {
        if (this.tours != null) {
            this.tours.close();
        }
    });
    final HintField search = new HintField(() -> this.mode.hint);
    /** False until the window or the game-view map shows this screen. */
    boolean shown;

    final MapConfig config;
    final Supplier<WorldData> world;
    @RequiredArgsConstructor
    enum SearchMode {
        ALL(t("msAll"), t("dSearchAll")),
        PLACES(t("msPlaces"), t("t6")),
        MONSTERS(t("msMonsters"), t("t7")),
        ITEMS(t("msItems"), t("dSearchItems"));

        final String hint, tooltip;

        SearchMode next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    SearchMode mode = ALL;
    final JButton searchMode = new JButton();

    final SearchResults npcs;
    /** What the search results last put in the card or their panel. */
    IntFunction<JComponent> searchSection;

    final JLayeredPane layers = new JLayeredPane();

    MapScreen(TileCache tiles, MapConfig config, Supplier<WorldData> world, ItemNames itemNames) {
        super(new BorderLayout());
        this.config = config;
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        view = new MapView(tiles, config);
        view.listener = this;
        view.chrome = this::showMapPicker;
        card = new InfoCard(view, world, itemNames, config);
        this.world = world;
        npcs = new SearchResults(view, world, new Card() {
            public void show(IntFunction<JComponent> section) {
                searchSection = section;
                showFloating(npcPanel, npcFloat, section, PALETTE_LAYER);
            }

            public void update(IntFunction<JComponent> section) {
                // Late answers never reopen or replace what the user opened since (a place's details, a route).
                if (searchSection == null) {
                    return;
                }
                if (npcFloat.isVisible()) {
                    searchSection = section;
                    showFloating(npcPanel, npcFloat, section, PALETTE_LAYER);
                }
            }
        });
        npcs.setItemPictures(itemNames::picture);
        npcs.setPrices(itemNames);
        card.setItemSearch(npcs::findItem);
        cardScroll = scroll(card);
        for (Floating floating : new Floating[]{cardFloat, npcFloat, toursFloat, houseFloat, iconsFloat}) {
            floating.addPropertyChangeListener("folded", e -> placeFloats());
        }

        search.setToolTipText(t("t8"));
        BigWindow.typable(search);
        search.addActionListener(e -> chooseFirst());
        // The cross: what was found goes too, as with Clear.
        search.cleared = npcs::clear;
        // The arrow keys move through the suggestions; Escape closes them, then clears what was typed.
        HintField.bind(search, KeyEvent.VK_DOWN, "down", () -> move(1), () -> true);
        HintField.bind(search, KeyEvent.VK_UP, "up", () -> move(-1), () -> true);
        HintField.bind(search, KeyEvent.VK_ESCAPE, "leave", () -> {
            if (resultsShown()) {
                results.setVisible(false);
                highlighted = -1;
            } else {
                search.setText("");
            }
        }, () -> resultsShown() || !search.getText().isEmpty());
        // Clicked again: what the field holds shows at once, not only after more typing.
        search.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                if (!resultsShown()) {
                    showResults();
                }
            }
        });
        HintField.onEdit(search, typing::restart);
        typing.setRepeats(false);
        searchMode.setFocusable(false);
        searchMode.setMargin(new Insets(1, 3, 1, 3));
        searchMode.addActionListener(e -> keepFocus(search, () -> {
            mode = mode.next();
            updateSearchMode();
            showResults();
        }));
        updateSearchMode();
        card.setOnRebuilt(this::placeFloats);
        icons = new IconPanel(config, () -> {
            if (iconsShown) {
                showIcons(true);
            }
        });
        view.iconsPanel = () -> showIcons(!iconsShown);
        layers.addComponentListener(new ComponentAdapter() {
            public void componentResized(ComponentEvent e) {
                placeFloats();
            }
        });
        // Over the map: floating controls and card; over the game the search field takes keys only once clicked.
        layers.add(view, JLayeredPane.DEFAULT_LAYER);
        card.setTextWidth(FLOATING_TEXT);
        cardFloat.setContent(cardScroll);
        for (var part : new JComponent[]{cardFloat, search, searchMode}) {
            layers.add(part, PALETTE_LAYER);
        }
        add(layers, BorderLayout.CENTER);
    }

    /** The window or the game-view map shows this screen from now on. */
    void layOut() {
        shown = true;
        cardFloat.setVisible(view.selected() != null || card.route != null);
        placeFloats();
    }

    /** Null until the route feature is set up. */
    TourPanel tours;

    void setTours(TourPanel.Actions actions) {
        boolean first = tours == null;
        tours = actions == null ? null : new TourPanel(actions, this::showTours, view::focus, null);
        if (first && tours != null) {
            // Only once the window or the game-view map shows this screen.
            view.addWidget(new RoutesButton(this::toggleTours, () -> tours != null && tours.isOpen(), () -> shown));
        }
    }

    /** Not before the window or the game-view map first shows this screen. */
    void toggleTours() {
        if (tours != null && shown) {
            tours.toggle();
        }
    }

    void openTours() {
        if (tours != null && tours.isOpen() && shown) {
            redoTours();
        } else {
            toggleTours();
        }
    }

    void redoTours() {
        if (tours != null) {
            tours.refresh();
        }
    }

    final JPanel toursPanel = new JPanel(new BorderLayout());

    /** In their own panel beside the Routes button. */
    void showTours(IntFunction<JComponent> section) {
        // Above the card and search results, which could otherwise steal its clicks on a narrow map.
        showFloating(toursPanel, toursFloat, section, PALETTE_LAYER + 1);
    }

    /** A final field: a lambda in a field initializer may not read it before the constructor sets it. */
    void clearSearch() {
        npcs.clear();
    }

    final JPanel npcPanel = new JPanel(new BorderLayout()), housePanel = new JPanel(new BorderLayout());
    final Floating houseFloat = new Floating("Your house", () -> showHouse(null));

    final IconPanel icons;
    final JPanel iconsPanel = new JPanel(new BorderLayout());
    final Floating iconsFloat = new Floating("Icons", () -> showIcons(false));
    boolean iconsShown;

    /** The Icons panel: a panel of its own over the map at the top right, below its button; never in the card. */
    void showIcons(boolean show) {
        iconsShown = show;
        showFloating(iconsPanel, iconsFloat, show ? icons::section : null, PALETTE_LAYER + 2);
    }

    /** The "Your house" panel, in a panel of its own; null closes it. */
    void showHouse(IntFunction<JComponent> section) {
        showFloating(housePanel, houseFloat, section, PALETTE_LAYER + 1);
    }

    /** The "Your house" panel built again, only where it shows. */
    void updateHouse(IntFunction<JComponent> section) {
        if (houseFloat.getParent() != null && houseFloat.isVisible()) {
            // Only one the user opened: a panel never shown yet counts as visible in Swing.
            showHouse(section);
        }
    }

    /**
     * A panel of its own over the game, in a scroll pane made on first use. The search panel scrolls its list only:
     * its title, buttons and tabs stay in view.
     */
    void showFloating(JPanel panel, Floating floating, IntFunction<JComponent> section, Integer layer) {
        boolean search = panel == npcPanel;
        if (panel.getParent() == null) {
            panel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
            panel.setBorder(createEmptyBorder(8, 8, 8, 8));
            floating.setContent(search ? panel : scroll(panel));
        }
        panel.removeAll();
        if (section != null) {
            var built = section.apply(search ? SEARCH_TEXT : FLOATING_TEXT);
            boolean parts = built instanceof Sections;
            panel.add(parts ? ((Sections) built).scrolling(MapScreen::scroll) : built,
                parts ? BorderLayout.CENTER : BorderLayout.NORTH);
        }
        if (floating.getParent() != layers) {
            layers.add(floating, layer);
        }
        floating.setVisible(section != null);
        placeFloats();
        panel.revalidate();
        layers.repaint();
    }

    static JScrollPane scroll(JComponent content) {
        var scroll = new JScrollPane(content, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    void placeFloats() {
        int w = layers.getWidth(), h = layers.getHeight();
        view.setBounds(0, 0, w, h);
        if (iconsFloat.isVisible() && iconsFloat.getParent() == layers) {
            // Left of the card, so a clicked place's details stay in view; clear of the zoom buttons.
            int right = cardFloat.isVisible() ? w - FLOATING_WIDTH - 24 : w - 12;
            iconsFloat.setBounds(max(12, right - FLOATING_WIDTH), 58, FLOATING_WIDTH, fit(iconsFloat, iconsPanel, h - 204));
            iconsFloat.revalidate();
        }
        // Clear of the zoom and follow buttons bottom right.
        int height = cardFloat.height(max(120, min(card.getPreferredSize().height + 4, h - 58 - 170)));
        cardFloat.setBounds(w - FLOATING_WIDTH - 12, 58, FLOATING_WIDTH, height);
        int searchWidth = min(260, max(120, w / 4));
        search.setBounds(12, 52, searchWidth, 28);
        searchMode.setBounds(12 + searchWidth + 4, 52, 28, 28);
        for (Floating floating : new Floating[]{npcFloat, houseFloat}) {
            if (floating.isVisible()) {
                floating.setBounds(12, 90, floating == npcFloat ? SEARCH_WIDTH : FLOATING_WIDTH,
                    fit(floating, floating == npcFloat ? npcPanel : housePanel, h - 90 - 70));
                floating.revalidate();
            }
        }
        if (toursFloat.isVisible()) {
            int bottomY = widgetBottom(h), toursHeight = fit(toursFloat, toursPanel, bottomY - 60);
            var at = new Rectangle(20 + floorGroupWidth(view.getFontMetrics(CONTROL_FONT)), bottomY - toursHeight,
                FLOATING_WIDTH, toursHeight);
            // Aside only while the search results or the house would cover it (open, unfolded, tall enough), where
            // that fits; back in its place once they no longer do.
            for (Floating floating : new Floating[]{npcFloat, houseFloat}) {
                var beside = floating.getBounds();
                if (floating.isVisible() && beside.intersects(at) && beside.x + beside.width + 8 + FLOATING_WIDTH
                    <= (cardFloat.isVisible() ? w - FLOATING_WIDTH - 24 : w - 12)) {
                    at.x = beside.x + beside.width + 8;
                }
            }
            toursFloat.setBounds(at);
            toursFloat.revalidate();
        }
        cardFloat.revalidate();
    }

    static int fit(Floating floating, JPanel panel, int room) {
        return floating.height(max(90, min(panel.getPreferredSize().height + 20, room - Floating.BAR)));
    }

    void showMapPicker(Point at) {
        var menu = new JPopupMenu();
        var mine = myMap();
        if (mine != null) {
            menu.add(mapItem("Where I am: " + mine.name, mine));
        }
        var rest = new ArrayList<BaseMap>();
        for (var map : view.maps() == null ? List.<BaseMap>of() : view.maps().sorted) {
            if (map.id == BaseMap.SURFACE || map.id == BaseMap.FULL) {
                menu.add(mapItem(map.name, map));
            } else {
                rest.add(map);
            }
        }
        menu.addSeparator();
        for (String range : t("msRanges").split(" ")) {
            var sub = new JMenu(range);
            char from = range.charAt(0), to = range.charAt(2);
            for (var map : rest) {
                char first = map.name.isEmpty() ? ' ' : Character.toUpperCase(map.name.charAt(0));
                if (first >= from && first <= to || range.equals("T–Z") && !Character.isLetter(first)) {
                    sub.add(mapItem(map.name, map));
                }
            }
            if (sub.getItemCount() > 0) {
                menu.add(sub);
            }
        }
        menu.show(view, at.x, at.y);
    }

    JMenuItem mapItem(String label, BaseMap map) {
        var item = plainItem(label);
        item.addActionListener(e -> openMap(map));
        return item;
    }

    /** The card's close button: the place is selected no longer, so a click on it opens its details again. */
    void closeCard() {
        view.select(null);
        showCard(false);
    }

    void showCard(boolean show) {
        cardFloat.setVisible(show);
        placeFloats();
        layers.repaint();
    }

    /** Shows or hides the card, scrolled back to the top: for content just set on it, not a route still running. */
    void showCardAtTop(boolean show) {
        showCard(show);
        cardScroll.getVerticalScrollBar().setValue(0);
    }

    void opened() {
        // On a route: its steps show whenever the map opens, not only when the route was asked for.
        if (card.route != null) {
            view.select(null);
            card.showRoute();
            showCard(true);
        }
        view.setFollowing(false);
        if (view.player() != null && config.followPlayer()) {
            view.jumpToPlayer();
        }
    }

    /** What {@link #setData} gave, with the place search and the kinds of places. */
    MapData data;

    void setData(BaseMaps baseMaps, MapData data) {
        var previous = view.selected();
        view.setMaps(baseMaps);
        view.setPois(data.pois, data.hidden);
        view.setLabels(data.labels);
        view.select(previous == null ? null : data.pois.stream()
            .filter(poi -> poi.type == previous.type && poi.location.equals(previous.location)).findFirst().orElse(null));
        if (card.nearestTo != null) {
            // Its list holds the old version's places, which are matched to their maps by identity.
            card.showNearest(card.nearestTo, nearestTeleports(card.nearestTo));
        }
        this.data = data;
        icons.setData(data);
    }

    void setUnlocks(Unlocks unlocks) {
        view.setUnlocks(unlocks);
        if (card.poi != null) {
            // Only a place's details show what the unlocks decide: requirements met, a quest's state.
            card.refresh();
        }
    }

    public void nearestRequested(WorldPoint point) {
        card.showNearest(point, nearestTeleports(point));
        showCardAtTop(true);
    }

    /** The places a teleport or transport arrives at on the map drawing {@code point}, the nearest first. */
    List<Poi> nearestTeleports(WorldPoint point) {
        var baseMaps = view.maps();
        var target = baseMaps == null ? null : baseMaps.find(point);
        var found = new ArrayList<Poi>();
        for (var poi : view.pois()) {
            boolean arrives = poi.type == PoiType.TELEPORT || poi.type.layer == Layer.TRANSPORTS && !poi.links().isEmpty()
                || poi.nearby().stream().anyMatch(p -> p.type == PoiType.TELEPORT);
            if (arrives && poi.map != null && poi.map == target && view.usable(poi) && view.kindShown(poi)) {
                found.add(poi);
            }
        }
        found.sort(Comparator.comparingInt(p -> p.location.distanceTo2D(point)));
        return found.subList(0, min(NEAREST, found.size()));
    }

    void setNpcPictures(NpcImages npcPictures) {
        npcs.setNpcPictures(npcPictures);
        card.setNpcPictures(npcPictures);
    }

    void setStopAdder(Consumer<Tour.Stop> adder) {
        card.setStopAdder(adder);
        npcs.setStopAdder(adder);
    }

    /** A kind of place by its label, from the data shown: never waits for the search index. */
    KindIndex.Kind kind(String label) {
        return data == null ? null : data.kinds.all().stream().filter(kind -> kind.label.equalsIgnoreCase(label))
            .findFirst().orElse(null);
    }

    /** {@code bringUp}: show in front of a selected icon, for a route the user just asked for. */
    void showRoute(IntFunction<JComponent> route, boolean bringUp) {
        if (bringUp && route != null) {
            view.select(null);
        }
        card.setRoute(route, bringUp);
        if (bringUp || route == null) {
            showCard(view.selected() != null || route != null || card.nearestTo != null && cardFloat.isVisible());
        }
        view.repaint();
    }

    void stop() {
        typing.stop();
        view.stop();
        card.stop();
    }

    public void selectionChanged(Poi poi) {
        card.show(poi);
        showCardAtTop(poi != null || card.route != null);
    }

    BaseMap myMap() {
        return view.mapOf(view.player());
    }

    void openMap(BaseMap map) {
        view.setFollowing(false);
        view.remember(map);
        boolean mine = myMap() == map;
        view.showMap(map, mine ? shownOn(map, view.player()) : null, mine ? view.playerZoom() : fitZoom(map));
    }

    double fitZoom(BaseMap map) {
        // Broken bounds must not make the zoom infinite or NaN.
        double zoom = MapView.fit(map, max(100, view.getWidth()), max(100, view.getHeight()));
        return Double.isFinite(zoom) ? max(MIN_ZOOM, min(2, zoom)) : view.playerZoom();
    }

    void updateSearchMode() {
        searchMode.setIcon(new ImageIcon(mode == MONSTERS ? dotIcon()
            : icon(14, mode == ITEMS ? "ITEM" : mode == PLACES ? "PIN" : "ALL")));
        searchMode.setToolTipText(mode.tooltip);
        search.repaint();
    }

    /** Results while typing only after a short pause. */
    final Timer typing = new Timer(250, e -> showResults());
    JPopupMenu results;

    void showResults() {
        String typed = search.getText().trim();
        if (results != null) {
            results.setVisible(false);
            results = null;
        }
        if (typed.length() < 2 || !search.isShowing()) {
            return;
        }
        var menu = new JPopupMenu();
        if (mode == ALL) {
            // A few of each kind, under a heading that searches that kind alone.
            for (var kind : new SearchMode[]{PLACES, MONSTERS, ITEMS}) {
                // Each kind's best 8.
                var found = found(kind, typed, 8);
                if (!found.isEmpty()) {
                    menu.add(heading(kind));
                    found.forEach(menu::add);
                }
            }
            if (menu.getComponentCount() == 0) {
                menu.add(disabled("Nothing found"));
            }
        } else {
            var found = found(mode, typed, MAX_RESULTS);
            found.forEach(menu::add);
            if (found.isEmpty()) {
                menu.add(disabled(mode == PLACES ? "Nothing found" : "Not in the lists"));
            }
        }
        menu.setFocusable(false);
        results = menu;
        highlighted = -1;
        keepFocus(search, () -> menu.show(search, 0, search.getHeight()));
    }

    boolean resultsShown() {
        return results != null && results.isVisible();
    }

    /** The suggestion the arrow keys moved to, lit in the popup; -1 for none. */
    int highlighted = -1;

    /** The suggestions that can be chosen: not the kinds' headings, nor "Nothing found". */
    List<JMenuItem> choosable() {
        var items = new ArrayList<JMenuItem>();
        if (resultsShown()) {
            for (Component item : results.getComponents()) {
                if (item instanceof JMenuItem && item.isEnabled()
                    && !Boolean.TRUE.equals(((JMenuItem) item).getClientProperty("heading"))) {
                    items.add((JMenuItem) item);
                }
            }
        }
        return items;
    }

    /** Lights the suggestion {@code by} places on from the lit one, round the ends. */
    void move(int by) {
        if (!resultsShown()) {
            showResults();
        }
        var items = choosable();
        if (items.isEmpty()) {
            return;
        }
        highlighted = highlighted < 0 ? (by > 0 ? 0 : items.size() - 1)
            : ((highlighted + by) % items.size() + items.size()) % items.size();
        for (int i = 0; i < items.size(); i++) {
            items.get(i).setArmed(i == highlighted);
        }
    }

    /** Enter: the suggestion lit by the arrow keys, else the first that can be chosen. */
    void chooseFirst() {
        typing.stop();
        if (!resultsShown()) {
            showResults();
        }
        var menu = results;
        var items = choosable();
        if (menu == null || items.isEmpty()) {
            return;
        }
        var chosen = items.get(highlighted >= 0 && highlighted < items.size() ? highlighted : 0);
        menu.setVisible(false);
        chosen.doClick(0);
    }

    List<JMenuItem> found(SearchMode kind, String typed, int max) {
        return kind == PLACES ? placeResults(typed.toLowerCase(Locale.ROOT), max) : worldResults(typed, kind == ITEMS, max);
    }

    /** A kind's heading over its results: clicked, the search looks at that kind alone. */
    JMenuItem heading(SearchMode kind) {
        String name = kind == PLACES ? "Places" : kind == MONSTERS ? "Monsters and NPCs" : "Items";
        var heading = new JMenuItem(name + "  ›");
        heading.putClientProperty("heading", Boolean.TRUE);
        bold(heading);
        heading.setToolTipText("Search " + name.toLowerCase(Locale.ROOT) + " only");
        heading.addActionListener(e -> keepFocus(search, () -> {
            mode = kind;
            updateSearchMode();
            showResults();
        }));
        return heading;
    }

    List<JMenuItem> placeResults(String query, int max) {
        var menu = new ArrayList<JMenuItem>();
        var index = data == null ? new SearchIndex(Collections.emptyList()) : data.search;
        // The game labels some spots with the monsters there ("Abyssal demons"), some maps are a boss's ("Nex"): those
        // are found as monsters.
        var hits = index.find(query, MAX_RESULTS * 2, hit -> hit.type == ICON
            ? ((Poi) hit.target).map != null && view.usable((Poi) hit.target)
            : hit.type != PLACE && hit.type != MAP || !SearchIndex.namesMonsters(hit, world.get()));
        int shown = 0, shownGameIcons = 0;
        for (var hit : hits) {
            // At most 8 game icons.
            if (shown >= max || hit.type == GAME_ICON && ++shownGameIcons > 8) {
                continue;
            }
            shown++;
            var item = plainItem(hit.label);
            if (hit.icon != null) {
                // As the map draws it; a kind of the game's icons ("Task board") as its first place.
                var poi = hit.target instanceof Poi ? (Poi) hit.target
                    : hit.icon == PoiType.GAME_ICON ? ((KindIndex.Kind) hit.target).entries.get(0).poi : null;
                item.setIcon(new ImageIcon(poi != null ? view.image(poi, 14) : image(hit.icon, null, false, 14)));
            } else if (hit.type == KIND || hit.type == KIND_PLACE) {
                item.setIcon(new ImageIcon(dotIcon()));
            }
            item.addActionListener(e -> choose(hit));
            menu.add(item);
        }
        return menu;
    }

    static JMenuItem disabled(String text) {
        var item = new JMenuItem(text);
        item.setEnabled(false);
        return item;
    }

    void choose(SearchIndex.Hit hit) {
        if (hit.type != KIND && hit.type != KIND_PLACE) {
            // A place, map or icon: the last search's results (a monster, an item) go, not stay beside it.
            npcs.clear();
        }
        if (hit.type == PLACE) {
            view.focus(((Place) hit.target).point);
        } else if (hit.type == MAP) {
            openMap((BaseMap) hit.target);
        } else if (hit.type == KIND || hit.type == KIND_PLACE) {
            npcs.showKind((KindIndex.Kind) hit.target);
            if (hit.type == KIND_PLACE) {
                npcs.lookAtPoint(hit.point);
            }
        } else {
            view.focus((Poi) hit.target);
        }
    }

    /** The monsters and NPCs the server's lists place, or the items they know, whose names hold what is typed. */
    List<JMenuItem> worldResults(String typed, boolean items, int max) {
        var menu = new ArrayList<JMenuItem>();
        Consumer<String> find = items ? npcs::findItem : npcs::findMonster;
        var titles = items ? world.get().itemSuggestions(typed, max) : world.get().npcSuggestions(typed, max);
        for (String title : titles) {
            var suggestion = plainItem(title);
            suggestion.setIcon(new ImageIcon(items ? icon(14, "ITEM") : dotIcon()));
            suggestion.addActionListener(e -> {
                search.setText(title);
                typing.stop();
                find.accept(title);
            });
            menu.add(suggestion);
        }
        return menu;
    }

    String placeName(WorldPoint point) {
        return placeName(view.maps(), view.labels(), point);
    }

    static String placeName(BaseMaps baseMaps, List<Place> labels, WorldPoint point) {
        var map = baseMaps == null ? null : baseMaps.find(point);
        return map != null && map.id != BaseMap.SURFACE && map.id != BaseMap.FULL ? map.name
            : MapData.settlement(labels, point);
    }

    /** The direction of {@code to} from {@code from}: "north", "south-east", ... */
    static String compass(WorldPoint from, WorldPoint to) {
        double angle = toDegrees(atan2(to.getY() - from.getY(), to.getX() - from.getX()));
        String[] names = {"east", "north-east", "north", "north-west", "west", "south-west", "south", "south-east"};
        return names[floorMod(round(angle / 45), 8)];
    }
}
