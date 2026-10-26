package com.hdmapreforged;

import static javax.swing.BorderFactory.*;
import static com.hdmapreforged.MapView.*;
import static com.hdmapreforged.PoiIcons.*;
import static com.hdmapreforged.SearchResults.*;
import static javax.swing.JLayeredPane.PALETTE_LAYER;
import static com.hdmapreforged.MapScreen.SearchMode.*;
import static com.hdmapreforged.SearchIndex.Type.*;
import static java.lang.Math.*;

import com.google.common.collect.Lists;
import java.awt.*;
import java.awt.event.*;
import java.util.*;
import java.util.List;
import java.util.function.*;
import java.util.stream.*;
import javax.swing.*;
import javax.swing.Timer;
import lombok.*;
import net.runelite.api.coords.*;
import net.runelite.client.ui.*;
import com.hdmapreforged.MapData.Place;

/** The map with its controls and detail card: in a window of its own, or over the game. */
final class MapScreen extends JPanel implements Listener {
    enum Layout {
        WINDOW,
        /** Over the game: floating controls and card; the search field takes keys only once clicked. */
        FULL
    }

    static final int MAX_RESULTS = 20, NEAREST = 10, FLOATING_WIDTH = 290;
    /** The search's panel is wider: its lists hold pictures and places with their words. */
    static final int SEARCH_WIDTH = 380, SEARCH_TEXT = 195 + SEARCH_WIDTH - FLOATING_WIDTH, FLOATING_TEXT = 195;

    final MapView view;
    final InfoCard card;
    final JScrollPane cardScroll;
    final Floating cardFloat = new Floating("Details", () -> showCard(false));
    /** Closed, the search's arrows and labels leave the map too. */
    final Floating npcFloat = new Floating("Search", () -> clearSearch());
    final Floating toursFloat = new Floating("Custom routes", () -> {
        if (this.tours != null) {
            this.tours.close();
        }
    });
    final HintField search = new HintField(() -> this.mode.hint);
    final JComboBox<BaseMap> maps = plainChoices(new JComboBox<>());
    final JComboBox<String> floors = new JComboBox<>(new String[]{"Floor 0", "Floor 1", "Floor 2", "Floor 3"});
    final JToggleButton follow = new JToggleButton(new ImageIcon(icon(14, "FOLLOW")));
    final JButton popOut = new JButton();
    final JButton zoomIn, zoomOut;
    final JPanel controls = new JPanel(new GridBagLayout());
    JSplitPane split;
    boolean syncing;
    /** Null until the window or the game-view map shows this screen. */
    Layout layout;

    final MapConfig config;
    final Supplier<WorldData> world;
    @RequiredArgsConstructor
    enum SearchMode {
        ALL("Search places, monsters, items…", PoiType.t("dSearchAll")),
        PLACES("Search places, patches, spots…", PoiType.t("t6")),
        MONSTERS("Find a monster or NPC…", PoiType.t("t7")),
        ITEMS("Find an item…", PoiType.t("dSearchItems"));

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

    MapScreen(TileCache tiles, MapConfig config, Supplier<WorldData> world, ItemNames itemNames,
        Runnable togglePopOut) {
        super(new BorderLayout());
        this.config = config;
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        view = new MapView(tiles, config);
        view.listener = this;
        card = new InfoCard(view, world, itemNames, config);
        this.world = world;
        npcs = new SearchResults(view, world, new Card() {
            public void show(IntFunction<JComponent> section) {
                searchSection = section;
                if (tours != null && layout != Layout.FULL) {
                    // A search took the card: a fastest order being worked out goes on and is saved.
                    tours.open = false;
                }
                showSection(section);
            }

            public void update(IntFunction<JComponent> section) {
                // Late answers never reopen or replace what the user opened since (a place's details, a route).
                if (searchSection == null) {
                    return;
                }
                if (layout == Layout.FULL) {
                    if (npcFloat.isVisible()) {
                        searchSection = section;
                        showFloating(npcPanel, npcFloat, section, PALETTE_LAYER);
                    }
                } else if (card.updateExtra(searchSection, section)) {
                    searchSection = section;
                }
            }
        });
        npcs.setItemPictures(itemNames::picture);
        npcs.setPrices(itemNames);
        card.setItemSearch(npcs::findItem);
        npcs.setPlaces(this::place);
        cardScroll = scroll(card);
        for (Floating floating : new Floating[]{cardFloat, npcFloat, toursFloat, houseFloat, iconsFloat}) {
            floating.addPropertyChangeListener("folded", e -> placeFloats());
        }

        search.setToolTipText(PoiType.t("t8"));
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
        plain(searchMode, null, 3, e -> keepFocus(search, () -> {
            mode = mode.next();
            updateSearchMode();
            showResults();
        }));
        updateSearchMode();
        maps.setToolTipText(PoiType.t("t9"));
        maps.addActionListener(e -> {
            var chosen = (BaseMap) maps.getSelectedItem();
            if (!syncing && chosen != null && chosen != view.map()) {
                openMap(chosen);
            }
        });
        floors.setToolTipText("Floor (plane)");
        floors.addActionListener(e -> {
            if (!syncing) {
                view.choosePlane(floors.getSelectedIndex());
            }
        });
        plain(follow, "Follow: keep your character in view", 4, e -> {
            if (follow.isSelected()) {
                view.centerOnPlayer();
            } else {
                view.setFollowing(false);
            }
        });
        plain(popOut, null, 4, e -> togglePopOut.run());
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
        zoomIn = plain(new JButton("+"), "Zoom in", 6, e -> view.zoomBy(1));
        zoomOut = plain(new JButton("−"), "Zoom out", 6, e -> view.zoomBy(-1));

        controls.setBackground(ColorScheme.DARK_GRAY_COLOR);
        controls.setBorder(createEmptyBorder(4, 4, 4, 4));
        viewChanged();
    }

    static <B extends AbstractButton> B plain(B button, String tip, int side, ActionListener action) {
        button.setToolTipText(tip);
        button.setFocusable(false);
        button.setMargin(new Insets(1, side, 1, side));
        button.addActionListener(action);
        return button;
    }

    void setScreenLayout(Layout layout) {
        this.layout = layout;
        removeAll();
        layers.removeAll();
        if (split != null) {
            split.removeAll();
            split = null;
        }
        view.setChrome(layout == Layout.FULL ? this::showMapPicker : null);
        // The map in the layered pane also in a window: the Icons panel floats over it, not in the card.
        layers.add(view, JLayeredPane.DEFAULT_LAYER);
        if (layout == Layout.FULL) {
            card.setTextWidth(FLOATING_TEXT);
            cardFloat.setContent(cardScroll);
            for (var part : new JComponent[]{cardFloat, search, searchMode}) {
                layers.add(part, PALETTE_LAYER);
            }
            cardFloat.setVisible(view.selected() != null || card.route != null || card.extra != null);
            add(layers, BorderLayout.CENTER);
            placeFloats();
        } else {
            cardScroll.setVisible(true);
            controls.removeAll();
            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(2, 2, 2, 2);
            c.fill = GridBagConstraints.HORIZONTAL;
            c.gridy = 0;
            // One row: search, map, floor, zoom, follow and the button that closes the window.
            JComponent[] parts = {search, searchMode, maps, floors, zoomOut, zoomIn, follow, popOut};
            double[] weight = {1, 0, 0.6, 0, 0, 0, 0, 0};
            for (int i = 0; i < parts.length; i++) {
                c.weightx = weight[i];
                controls.add(parts[i], c);
            }
            popOut.setText("Dock");
            popOut.setToolTipText(PoiType.t("t10"));
            maps.setPrototypeDisplayValue(new BaseMap(0, "Gielinor Surface", 0, 0, 0, 0, 0, 0, 0));
            add(controls, BorderLayout.NORTH);
            split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, layers, cardScroll);
            split.setBorder(createEmptyBorder());
            split.setContinuousLayout(true);
            split.setResizeWeight(0.78);
            cardScroll.setPreferredSize(new Dimension(300, 400));
            card.setTextWidth(250);
            add(split, BorderLayout.CENTER);
        }
        if (iconsShown) {
            showIcons(true);
        }
        revalidate();
        repaint();
    }

    /** Null until the route feature is set up. */
    TourPanel tours;

    void setTours(TourPanel.Actions actions) {
        boolean first = tours == null;
        tours = actions == null ? null : new TourPanel(actions, this::showTours, view::focus, null);
        if (first && tours != null) {
            // Only once the window or the game-view map shows this screen.
            view.addWidget(new RoutesButton(this::toggleTours, () -> tours != null && tours.isOpen(),
                () -> layout != null));
        }
    }

    /** Not before the window or the game-view map first shows this screen. */
    void toggleTours() {
        if (tours != null && layout != null) {
            tours.toggle();
        }
    }

    void openTours() {
        if (tours != null && tours.isOpen() && layout != null) {
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

    /** A panel of its own over the game in {@link Layout#FULL}; elsewhere the card's extra section. */
    void showFloatOr(JPanel panel, Floating floating, int layer, IntFunction<JComponent> section) {
        if (layout == Layout.FULL) {
            showFloating(panel, floating, section, layer);
        } else {
            showSection(section);
        }
    }

    /** Over the game in their own panel beside the Routes button; elsewhere in the card. */
    void showTours(IntFunction<JComponent> section) {
        // Above the card and search results, which could otherwise steal its clicks on a narrow map.
        showFloatOr(toursPanel, toursFloat, PALETTE_LAYER + 1, section);
    }

    /** A final field: a lambda in a field initializer may not read it before the constructor sets it. */
    void clearSearch() {
        npcs.clear();
    }

    void showSection(IntFunction<JComponent> section) {
        if (layout == Layout.FULL) {
            showFloating(npcPanel, npcFloat, section, PALETTE_LAYER);
            return;
        }
        card.setExtra(section);
        showCardAtTop(section != null || view.selected() != null || card.route != null);
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

    /** The "Your house" panel: over the game in a panel of its own, elsewhere in the card; null closes it. */
    void showHouse(IntFunction<JComponent> section) {
        showFloatOr(housePanel, houseFloat, PALETTE_LAYER + 1, section);
    }

    /** The "Your house" panel built again, only where it shows. */
    void updateHouse(IntFunction<JComponent> section) {
        if (layout != Layout.FULL) {
            card.updateExtra(section, section);
        } else if (houseFloat.getParent() != null && houseFloat.isVisible()) {
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
        JScrollPane scroll = new JScrollPane(content, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
            ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        return scroll;
    }

    void placeFloats() {
        int w = layers.getWidth(), h = layers.getHeight();
        view.setBounds(0, 0, w, h);
        if (iconsFloat.isVisible() && iconsFloat.getParent() == layers) {
            // Over the game left of the card, so a clicked place's details stay in view; clear of the zoom buttons.
            boolean full = layout == Layout.FULL;
            int right = full && cardFloat.isVisible() ? w - FLOATING_WIDTH - 24 : w - 12;
            iconsFloat.setBounds(max(12, right - FLOATING_WIDTH), 58, FLOATING_WIDTH,
                fit(iconsFloat, iconsPanel, full ? h - 204 : h - 70));
            iconsFloat.revalidate();
        }
        if (layout != Layout.FULL) {
            return;
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
        for (var map : IntStream.range(0, maps.getItemCount()).mapToObj(maps::getItemAt).collect(Collectors.toList())) {
            if (map.id == BaseMap.SURFACE || map.id == BaseMap.FULL) {
                menu.add(mapItem(map.name, map));
            } else {
                rest.add(map);
            }
        }
        menu.addSeparator();
        for (String range : new String[]{"A–C", "D–F", "G–K", "L–O", "P–S", "T–Z"}) {
            var sub = new JMenu(range);
            char from = range.charAt(0), to = range.charAt(2);
            for (var map : rest) {
                char first = Character.toUpperCase(map.name.charAt(0));
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
        JMenuItem item = plainItem(label);
        item.addActionListener(e -> openMap(map));
        return item;
    }

    void showCard(boolean show) {
        if (layout == Layout.FULL) {
            cardFloat.setVisible(show);
            placeFloats();
            layers.repaint();
        }
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
        syncing = true;
        maps.setModel(new DefaultComboBoxModel<>(baseMaps.sorted.toArray(new BaseMap[0])));
        syncing = false;
        view.setMaps(baseMaps);
        view.setPois(data.pois, data.hidden);
        view.setLabels(data.labels);
        view.select(previous == null ? null : data.pois.stream()
            .filter(poi -> poi.type == previous.type && poi.location.equals(previous.location)).findFirst().orElse(null));
        viewChanged();
        this.data = data;
        icons.setData(data);
    }

    void setUnlocks(Unlocks unlocks) {
        view.setUnlocks(unlocks);
        card.refresh();
    }

    public void nearestRequested(WorldPoint point) {
        var baseMaps = view.maps();
        var target = baseMaps == null ? null : baseMaps.find(point.getX(), point.getY());
        var found = new ArrayList<Poi>();
        for (var poi : view.pois()) {
            boolean arrives = poi.type == PoiType.TELEPORT || poi.type.layer == Layer.TRANSPORTS && !poi.links().isEmpty()
                || poi.nearby().stream().anyMatch(p -> p.type == PoiType.TELEPORT);
            if (arrives && poi.map != null && poi.map == target && view.usable(poi) && view.kindShown(poi)) {
                found.add(poi);
            }
        }
        found.sort(Comparator.comparingInt(p -> p.location.distanceTo2D(point)));
        card.showNearest(point, found.subList(0, min(NEAREST, found.size())));
        showCardAtTop(true);
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
            showCard(view.selected() != null || route != null);
        }
        view.repaint();
    }

    void stop() {
        typing.stop();
        view.stop();
    }

    public void selectionChanged(Poi poi) {
        card.show(poi);
        showCardAtTop(poi != null || card.route != null || card.extra != null);
    }

    public void viewChanged() {
        syncing = true;
        if (view.map() != null && maps.getSelectedItem() != view.map()) {
            maps.setSelectedItem(view.map());
        }
        if (floors.getSelectedIndex() != view.plane()) {
            floors.setSelectedIndex(view.plane());
        }
        follow.setSelected(view.following);
        syncing = false;
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
        JMenuItem chosen = items.get(highlighted >= 0 && highlighted < items.size() ? highlighted : 0);
        menu.setVisible(false);
        chosen.doClick(0);
    }

    List<JMenuItem> found(SearchMode kind, String typed, int max) {
        return kind == PLACES ? placeResults(typed.toLowerCase(Locale.ROOT), max)
            : worldResults(typed, kind == ITEMS, max);
    }

    /** A kind's heading over its results: clicked, the search looks at that kind alone. */
    JMenuItem heading(SearchMode kind) {
        String name = kind == PLACES ? "Places" : kind == MONSTERS ? "Monsters and NPCs" : "Items";
        JMenuItem heading = new JMenuItem(name + "  ›");
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
        var hits = index.find(query, MAX_RESULTS * 2, hit -> {
            switch (hit.type) {
                case ICON:
                    var poi = (Poi) hit.target;
                    return poi.map != null && view.usable(poi);
                case PLACE:
                case MAP:
                    // The game labels some spots with the monsters there ("Abyssal demons"), some maps are a boss's
                    // ("Nex"): those are found as monsters.
                    return !SearchIndex.namesMonsters(hit, world.get());
                default:
                    return true;
            }
        });
        int shown = 0, shownGameIcons = 0;
        for (var hit : hits) {
            // At most 8 game icons.
            if (shown >= max || hit.type == GAME_ICON && ++shownGameIcons > 8) {
                continue;
            }
            shown++;
            JMenuItem item = plainItem(hit.label);
            if (hit.icon != null) {
                item.setIcon(new ImageIcon(image(hit.icon, 14)));
            } else if (hit.type == KIND || hit.type == KIND_PLACE) {
                item.setIcon(new ImageIcon(dotIcon()));
            }
            item.addActionListener(e -> choose(hit));
            menu.add(item);
        }
        return menu;
    }

    static JMenuItem disabled(String text) {
        JMenuItem item = new JMenuItem(text);
        item.setEnabled(false);
        return item;
    }

    void choose(SearchIndex.Hit hit) {
        if (hit.type != KIND && hit.type != KIND_PLACE) {
            // A place, map or icon: the last search's results (a monster, an item) go, not stay beside it.
            npcs.clear();
        }
        switch (hit.type) {
            case PLACE:
                view.focus(((Place) hit.target).point);
                break;
            case MAP:
                openMap((BaseMap) hit.target);
                break;
            case KIND:
            case KIND_PLACE:
                npcs.showKind((KindIndex.Kind) hit.target, this::placeName);
                if (hit.type == KIND_PLACE) {
                    npcs.lookAtPoint(hit.point);
                }
                break;
            default:
                view.focus((Poi) hit.target);
                break;
        }
    }

    /** The monsters and NPCs the server's lists place, or the items they know, whose names hold what is typed. */
    List<JMenuItem> worldResults(String typed, boolean items, int max) {
        var menu = new ArrayList<JMenuItem>();
        Consumer<String> find = items ? npcs::findItem : npcs::findMonster;
        var titles = items ? world.get().itemSuggestions(typed, max) : world.get().npcSuggestions(typed, max);
        for (String title : titles) {
            JMenuItem suggestion = plainItem(title);
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

    Place place(WorldPoint point) {
        return place(view.maps(), view.labels(), point);
    }

    static String placeName(BaseMaps baseMaps, List<Place> labels, WorldPoint point) {
        var map = baseMaps == null ? null : baseMaps.find(point);
        return map != null && map.id != BaseMap.SURFACE ? map.name : MapData.settlement(labels, point);
    }

    /** A town up to this far names a place out of reach of {@link MapData#named} with a direction. */
    static final int FAR_TOWN = 400;

    /**
     * What to call where NPCs stand, with the point that name stands for (so places of one name can say which side
     * they are): a dungeon's map, with its Wilderness level; the Wilderness with its level; the name places go by
     * ({@link MapData#named}); the nearest town further off with a direction ("north of Edgeville"); else the nearest
     * region. Null without labels.
     */
    static Place place(BaseMaps baseMaps, List<Place> labels, WorldPoint point) {
        int level = wildernessLevel(point);
        var map = baseMaps == null ? null : baseMaps.find(point);
        if (map != null && map.id != BaseMap.SURFACE && map.id != BaseMap.FULL) {
            return mapPlace(map, point);
        }
        if (level > 0) {
            return new Place(new WorldPoint(3168, point.getY(), point.getPlane()), "Wilderness, level " + level,
                null);
        }
        // Of equals, the last the game lists. Named as places are everywhere (the town it is in, else the nearer of a
        // town and a landmark: the Farming Guild, not Molch); far from both, by the nearest town's direction.
        var last = Lists.reverse(labels);
        var named = MapData.named(last, point);
        if (named != null) {
            return named;
        }
        var town = MapData.nearest(last, point, "settlement", FAR_TOWN, 0);
        if (town != null) {
            return new Place(town.point, compass(town.point, point) + " of " + town.name, null);
        }
        // A region's label names a place up to 600 tiles away; not a region a world away (Last Man Standing's Wild
        // Varrock for a cave under Rellekka): none.
        return MapData.nearest(last, point, "region", 600, 0);
    }

    /** A dungeon's map as the name of a place in it, with its Wilderness level. */
    static Place mapPlace(BaseMap map, WorldPoint point) {
        int level = wildernessLevel(point);
        return new Place(new WorldPoint(map.centerX, map.centerY, point.getPlane()),
            map.name + (level > 0 ? ", level " + level : ""), null);
    }

    /** The Wilderness level of a point (level n starts 8(n-1) tiles north of its edge, above or below ground); else 0. */
    static int wildernessLevel(WorldPoint point) {
        int x = point.getX(), y = point.getY(), start = y >= 9920 ? 9920 : 3520;
        return x >= 2944 && x < 3392 && y >= start && y < start + 56 * 8 ? (y - start) / 8 + 1 : 0;
    }

    /** The direction of {@code to} from {@code from}: "north", "south-east", ... */
    static String compass(WorldPoint from, WorldPoint to) {
        double angle = toDegrees(atan2(to.getY() - from.getY(), to.getX() - from.getX()));
        String[] names = {"east", "north-east", "north", "north-west", "west", "south-west", "south", "south-east"};
        return names[(int) floorMod(round(angle / 45), 8)];
    }
}
