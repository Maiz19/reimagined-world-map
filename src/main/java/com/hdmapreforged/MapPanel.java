package com.hdmapreforged;

import static javax.swing.BorderFactory.*;
import static java.awt.Component.*;
import static com.hdmapreforged.FriendsWidget.*;
import static com.hdmapreforged.SearchResults.*;
import static net.runelite.client.ui.ColorScheme.*;

import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.*;
import java.util.*;
import java.util.List;
import java.util.function.*;
import javax.swing.*;
import javax.swing.Timer;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import net.runelite.client.ui.*;

/** The sidebar: buttons to open the map, the route's steps, custom routes and the party. Swing thread. */
final class MapPanel extends PluginPanel {
    interface Friends {
        boolean inGroup();

        List<Row> rows();

        /** The world the player is on, or 0. */
        int world();

        Images images();

        /** A member's details as the map's card paints them, with a page of its own. */
        MemberPages pages();

        boolean canRejoin();

        /** Rejoins the last party. */
        void join(Component from);

        /** Has the maps follow a member. */
        void follow(long id);

        /** A member's experience drops rising now; what they got this session, or null. */
        List<PartyMapMembers.Drop> xp(long id);

        PartyMapMembers.Session session(long id);

        /** {@code news} runs (Swing) whenever members' news came (a drop, experience). */
        void watch(Runnable news);
    }

    /** Text width inside the sidebar's padding. */
    static final int TEXT_WIDTH = PANEL_WIDTH - 52;

    final Consumer<WorldPoint> showOnMap;
    final Friends friends;
    final Function<WorldPoint, String> placeName;
    final JPanel routeHolder = column();
    /** Shown only with no route; a route's steps have their own title. */
    final JLabel routeHeading = heading("Route");
    /** A route's steps not built yet: it changed while the sidebar was hidden. */
    IntFunction<JComponent> waitingRoute;
    /** The party list: members' experience drops rise from their rows' right ends over it, as beside their markers. */
    final JPanel friendsHolder = new JPanel() {
        protected void paintChildren(Graphics g) {
            super.paintChildren(g);
            for (Component row : getComponents()) {
                Object id = row instanceof JComponent ? ((JComponent) row).getClientProperty(MEMBER) : null;
                if (id != null) {
                    PartyMapOverlay.paintXp((Graphics2D) g, friends.xp((Long) id), friends.images()::skill,
                        row.getX() + row.getWidth() - 64, row.getY() + row.getHeight() - 5);
                }
            }
        }
    };
    final JPanel toursHolder = column();
    TourPanel.Actions tours;
    /** The custom route open for editing, or null for the list. */
    IntFunction<JComponent> editorSection;
    TourPanel editor;
    long openFriend = -1;
    /** The open member's details, its page and the mouse over it kept while it is rebuilt. */
    final MemberPages pages;
    final java.awt.Point[] pagesMouse = new java.awt.Point[1];
    final Timer friendsTimer;
    /** Repaints the party list while experience drops rise in it. */
    final Timer xpTimer = new Timer(40, e -> risingXp());
    /** The members the party list shows. */
    List<Long> listed = List.of();
    String friendsShown;

    MapPanel(Runnable openMap, Runnable openWindow, Consumer<WorldPoint> showOnMap, Friends friends,
        Function<WorldPoint, String> placeName) {
        super(false);
        this.showOnMap = showOnMap;
        this.friends = friends;
        pages = friends.pages();
        friendsHolder.setLayout(new BoxLayout(friendsHolder, BoxLayout.Y_AXIS));
        friendsHolder.setOpaque(false);
        friendsHolder.setAlignmentX(LEFT_ALIGNMENT);
        this.placeName = placeName;
        setLayout(new BorderLayout());
        setBackground(DARK_GRAY_COLOR);

        JPanel content = column();
        content.setBorder(createEmptyBorder(10, 10, 10, 10));
        JPanel buttons = new JPanel(new GridLayout(1, 2, 6, 0));
        buttons.add(tip(button("Open map", openMap), "The map over the game (also with the map key)"));
        buttons.add(tip(button("In a window", openWindow), "The map in a window of its own"));
        content.add(fitted(buttons));
        content.add(Box.createVerticalStrut(10));
        content.add(card(routeHeading, routeHolder));
        content.add(Box.createVerticalStrut(8));
        content.add(card(heading("Custom routes"), toursHolder));
        content.add(Box.createVerticalStrut(8));
        content.add(card(heading("Party"), friendsHolder));

        // Never wider than the sidebar: what does not fit wraps.
        JPanel top = new Fitted(new BorderLayout(), 0);
        top.setBackground(DARK_GRAY_COLOR);
        top.add(content, BorderLayout.NORTH);
        add(MapScreen.scroll(top), BorderLayout.CENTER);

        // Shown (its tab picked, the sidebar opened): a route that waited is built now, and the party as it is (joined
        // in RuneLite's Party panel meanwhile), not as it was when hidden.
        addHierarchyListener(e -> {
            if (waitingRoute != null && isShowing()) {
                showRoute(waitingRoute);
            }
            if (isShowing()) {
                refreshFriends();
            }
        });
        showRoute(null);
        // Every 2 seconds.
        friendsTimer = new Timer(2000, e -> refreshFriends());
        friendsTimer.start();
        // Members' news come with every message (each step they take): looked at once a fifth of a second at most.
        var news = new Timer(200, e -> {
            refreshFriends();
            friendsHolder.repaint();
            if (isShowing() && listed.stream().anyMatch(id -> !friends.xp(id).isEmpty())) {
                xpTimer.start();
            }
        });
        news.setRepeats(false);
        friends.watch(() -> {
            if (isShowing() && !news.isRunning()) {
                news.start();
            }
        });
        refreshFriends();
    }

    /** The route's steps ({@code section} builds them for a width), or null for none. */
    void showRoute(IntFunction<JComponent> section) {
        // The steps take milliseconds to build: while the sidebar is hidden they wait, built once it shows.
        waitingRoute = section != null && !isShowing() ? section : null;
        if (waitingRoute != null) {
            return;
        }
        routeHolder.removeAll();
        routeHeading.setVisible(section == null);
        if (section == null) {
            routeHolder.add(text("No route. Right-click a place on the map, Path to here."));
        } else {
            var steps = section.apply(TEXT_WIDTH);
            steps.setAlignmentX(LEFT_ALIGNMENT);
            routeHolder.add(steps);
        }
        refresh(routeHolder);
    }

    void setTours(TourPanel.Actions actions) {
        tours = actions;
        editor = new TourPanel(actions, section -> {
            editorSection = section;
            showTours();
        }, showOnMap, "\u2039 All routes");
        refreshTours();
    }

    void refreshTours() {
        if (editor != null && editor.isOpen()) {
            editor.refresh();
            return;
        }
        showTours();
    }

    void edit(Tour tour) {
        tours.save(tours.tours(), tour.name);
        if (!editor.isOpen()) {
            editor.toggle();
        } else {
            editor.refresh();
        }
    }

    void showTours() {
        toursHolder.removeAll();

        if (editorSection != null) {
            // HTML widths come out wider than asked in RuneLite's font: some room to spare.
            var section = editorSection.apply(TEXT_WIDTH - 40);
            section.setAlignmentX(LEFT_ALIGNMENT);
            toursHolder.add(section);
            refresh(toursHolder);
            return;
        }
        List<Tour> saved = tours == null ? Collections.emptyList() : tours.tours();
        String running = tours == null ? null : tours.runningTour();
        if (saved.isEmpty()) {
            toursHolder.add(text("None yet. Right-click the map, Add to custom route."));
            JButton make = tip(button("New route", () -> edit(new Tour(Tour.unique(saved, "My route"),
                Collections.emptyList()))), "Make a route; add its stops from the map");
            make.setAlignmentX(LEFT_ALIGNMENT);
            toursHolder.add(make);
        }
        for (Tour tour : saved) {
            boolean runs = tour.name.equals(running);
            String what = plural(tour.stops.size(), "stop");
            toursHolder.add(entry(tour.name, runs ? "Running, " + what : what, runs ? new Color(120, 220, 140) : null,
                "Open this route: its stops, their order, Run", () -> edit(tour)));
        }
        refresh(toursHolder);
    }

    void stop() {
        friendsTimer.stop();
        xpTimer.stop();
    }

    void risingXp() {
        friendsHolder.repaint();
        if (!isShowing() || listed.stream().allMatch(id -> friends.xp(id).isEmpty())) {
            xpTimer.stop();
        }
    }

    /** Rebuilt only when something shown changed. */
    void refreshFriends() {
        if (!isShowing() && friendsShown != null) {
            return;
        }
        boolean in = friends.inGroup();
        List<Row> rows = in ? friends.rows() : Collections.emptyList();
        boolean rejoin = !in && friends.canRejoin();
        StringBuilder key = new StringBuilder(in ? "in" : rejoin ? "out, rejoin" : "out");
        var places = new String[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            places[i] = place(row);
            key.append('|').append(row.name).append(',').append(row.world).append(',').append(places[i])
                .append(',').append(row.point != null).append(',').append(row.color);
            var got = friends.session(row.id);
            key.append(',').append(got == null || got.latest.isEmpty() ? 0 : got.latest.get(0).at);
            if (row.id == openFriend) {
                // Its pages paint what is shared as it comes: no rebuild for it.
                key.append(",open");
            }
        }
        if (key.toString().equals(friendsShown)) {
            return;
        }
        friendsShown = key.toString();
        friendsHolder.removeAll();
        if (!in) {
            if (rejoin) {
                JButton join = tip(button("Rejoin party", () -> friends.join(this)), "Join your last party again");
                join.setAlignmentX(LEFT_ALIGNMENT);
                friendsHolder.add(capped(join));
                friendsHolder.add(Box.createVerticalStrut(4));
            }
            friendsHolder.add(text("Not in a party. In one (RuneLite's Party panel), its members show here: where "
                + "they are and what they carry."));
        } else if (rows.isEmpty()) {
            friendsHolder.add(text("No one else in your party yet."));
        }
        int me = friends.world();
        var ids = new ArrayList<Long>();
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            ids.add(row.id);
            String where = row.point == null ? "Not sharing where they are"
                : (row.world > 0 && row.world != me ? "World " + row.world + ", " : "")
                    + (places[i] != null ? places[i] : "on the map");
            var line = friend(row, where);
            line.putClientProperty(MEMBER, row.id);
            friendsHolder.add(line);
            var got = friends.session(row.id);
            if (got != null && !got.latest.isEmpty()) {
                friendsHolder.add(latest(got));
            }
            if (row.id == openFriend) {
                friendsHolder.add(details(row, me));
            }
        }
        listed = ids;
        placeNames.keySet().retainAll(ids);
        refresh(friendsHolder);
    }

    /** The party member a row of the list is. */
    static final String MEMBER = "partyMember";

    /** A member's latest shared drops this session, newest first: picture and worth (an untradeable's name on hover). */
    JComponent latest(PartyMapMembers.Session got) {
        JPanel line = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        line.setOpaque(false);
        var images = friends.images();
        for (var drop : got.latest.subList(0, Math.min(3, got.latest.size()))) {
            BufferedImage picture = images.item(drop.item, drop.quantity);
            JLabel label = new JLabel(drop.name != null ? "" : PartyMapOverlay.coins(drop.value),
                picture == null ? null : new ImageIcon(picture), JLabel.LEFT);
            label.putClientProperty("html.disable", Boolean.TRUE);
            label.setFont(FontManager.getRunescapeSmallFont());
            label.setForeground(drop.name != null ? PartyMapOverlay.UNTRADEABLE : LIGHT_GRAY_COLOR);
            label.setToolTipText(drop.name);
            line.add(label);
        }
        return fitted(line);
    }

    /** Each member's place name and where it was worked out: again only once they moved off it (a scan of the labels). */
    final Map<Long, Object[]> placeNames = new HashMap<>();

    String place(Row row) {
        if (row.point == null) {
            return null;
        }
        Object[] known = placeNames.get(row.id);
        // A name not found (the labels still loading) is asked again.
        if (known == null || known[1] == null || ((WorldPoint) known[0]).getPlane() != row.point.getPlane()
            || ((WorldPoint) known[0]).distanceTo2D(row.point) > 8) {
            known = new Object[]{row.point, placeName.apply(row.point)};
            placeNames.put(row.id, known);
        }
        return (String) known[1];
    }

    static void refresh(JComponent c) {
        c.revalidate();
        c.repaint();
    }

    void rebuildFriends() {
        friendsShown = null;
        refreshFriends();
    }

    JComponent friend(Row row, String where) {
        return entry(row.name, where, row.color != null ? row.color : MEDIUM_GRAY_COLOR,
            row.id == openFriend ? "Close" : "What " + row.name + " carries, wears and can do", () -> {
                openFriend = openFriend == row.id ? -1 : row.id;
                rebuildFriends();
            });
    }

    /** A member's details: an on-map button and their world, their orbs and one page of what they share. */
    JComponent details(Row row, int me) {
        JPanel box = column();
        box.setBorder(createEmptyBorder(0, 6, 8, 0));
        JPanel actions = new JPanel(new GridLayout(1, 2, 4, 0));
        JButton show = tip(button("On map", () -> {
            showOnMap.accept(row.point);
            friends.follow(row.id);
        }), "Open the map where they are");
        show.setEnabled(row.point != null);
        actions.add(show);
        if (row.world > 0 && me > 0 && row.world != me) {
            JLabel world = new JLabel("World " + row.world, JLabel.CENTER);
            world.setToolTipText("On another world: hop there with the game's world switcher");
            actions.add(world);
        }
        box.add(fitted(actions));
        box.add(Box.createVerticalStrut(6));
        box.add(pagesView(row.id));
        return box;
    }

    /** A member's pages as the map's card paints them ({@link MemberPages}), with their clicks and tooltips. */
    JComponent pagesView(long id) {
        var areas = new ArrayList<MapView.Control>();
        var mouse = pagesMouse;
        JComponent view = new JComponent() {
            protected void paintComponent(Graphics g) {
                areas.clear();
                pages.paint((Graphics2D) g, id, 0, 0, getWidth(), new MapView.Clicks() {
                    public void add(RoundRectangle2D shape, Runnable action, String tooltip) {
                        areas.add(new MapView.Control(shape, action, tooltip));
                    }

                    public boolean hovered(RoundRectangle2D shape) {
                        return mouse[0] != null && shape.contains(mouse[0]);
                    }
                }, this::repaint);
            }

            public String getToolTipText(MouseEvent e) {
                var at = area(e.getPoint());
                return at == null ? null : at.tooltip;
            }

            MapView.Control area(java.awt.Point p) {
                return areas.stream().filter(c -> c.shape.contains(p)).findFirst().orElse(null);
            }
        };
        var mice = new MouseAdapter() {
            public void mouseMoved(MouseEvent e) {
                mouse[0] = e.getPoint();
                view.repaint();
            }

            public void mouseExited(MouseEvent e) {
                mouse[0] = null;
                view.repaint();
            }

            public void mouseReleased(MouseEvent e) {
                areas.stream().filter(c -> c.shape.contains(e.getPoint())).findFirst().ifPresent(c -> c.action.run());
            }
        };
        view.addMouseListener(mice);
        view.addMouseMotionListener(mice);
        ToolTipManager.sharedInstance().registerComponent(view);
        int width = PANEL_WIDTH - 36;
        view.setPreferredSize(new Dimension(width, pages.height(width)));
        view.setAlignmentX(LEFT_ALIGNMENT);
        return view;
    }

    /** A name over a smaller line; with {@code action}, clickable. */
    static JComponent entry(String title, String under, Color dot, String tip, Runnable action) {
        JPanel line = new JPanel(new BorderLayout(6, 0));
        line.setBackground(DARK_GRAY_COLOR);
        line.setBorder(createEmptyBorder(5, 6, 5, 6));
        line.setAlignmentX(LEFT_ALIGNMENT);
        if (dot != null) {
            JLabel mark = new JLabel("\u25CF");
            mark.setForeground(dot);
            line.add(mark, BorderLayout.WEST);
        }
        JPanel texts = new JPanel(new GridLayout(2, 1));
        texts.setOpaque(false);
        JLabel name = InfoCard.plain(title);
        name.setForeground(action != null ? Color.WHITE : LIGHT_GRAY_COLOR);
        name.setFont(FontManager.getRunescapeBoldFont());
        texts.add(name);
        JLabel small = InfoCard.plain(under);
        small.setForeground(LIGHT_GRAY_COLOR);
        small.setFont(FontManager.getRunescapeSmallFont());
        texts.add(small);
        line.add(texts, BorderLayout.CENTER);
        if (action != null) {
            clickableRow(line, action).setToolTipText(tip);
            line.addMouseListener(new MouseAdapter() {
                public void mouseEntered(MouseEvent e) {
                    line.setBackground(DARK_GRAY_HOVER_COLOR);
                }

                public void mouseExited(MouseEvent e) {
                    line.setBackground(DARK_GRAY_COLOR);
                }
            });
        }
        return RoutePanels.wrapped(line, 4);
    }

    static JComponent card(JComponent heading, JComponent body) {
        JPanel card = column();
        card.setOpaque(true);
        card.setBackground(DARKER_GRAY_COLOR);
        card.setBorder(createEmptyBorder(8, 8, 8, 8));
        card.add(heading);
        card.add(body);
        return card;
    }

    /** Transparent, left-aligned, no taller than it needs. */
    static JComponent fitted(JComponent c) {
        c.setOpaque(false);
        c.setAlignmentX(LEFT_ALIGNMENT);
        return capped(c);
    }

    static JLabel heading(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(Color.WHITE);
        label.setFont(FontManager.getRunescapeBoldFont().deriveFont(Font.BOLD));
        label.setBorder(createEmptyBorder(0, 0, 6, 0));
        label.setAlignmentX(LEFT_ALIGNMENT);
        return label;
    }

    static JLabel text(String text) {
        return SearchResults.text(text, LIGHT_GRAY_COLOR, Font.PLAIN, 0, TEXT_WIDTH);
    }
}
