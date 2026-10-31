package com.hdmapreforged;

import static com.hdmapreforged.PoiType.t;
import static javax.swing.BorderFactory.*;
import static com.hdmapreforged.SearchResults.*;
import static net.runelite.client.ui.ColorScheme.*;

import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.util.*;
import java.util.List;
import java.util.function.*;
import javax.swing.*;
import net.runelite.api.coords.*;
import net.runelite.client.ui.*;

/** The sidebar: buttons to open the map, the route's steps, custom routes and the party. Swing thread. */
class MapPanel extends PluginPanel {
    /** Text width inside the sidebar's padding. */
    static final int TEXT_WIDTH = PANEL_WIDTH - 52;

    final Consumer<WorldPoint> showOnMap;
    final PartyMap friends;
    final Function<WorldPoint, String> placeName;
    final JPanel routeHolder = column();
    /** Shown only with no route; a route's steps have their own title. */
    final JLabel routeHeading = heading("Route");
    /** A route's steps not built yet: it changed while the sidebar was hidden. */
    IntFunction<JComponent> waitingRoute;
    final JPanel toursHolder = column();
    TourPanel.Actions tours;
    /** The custom route open for editing, or null for the list. */
    IntFunction<JComponent> editorSection;
    TourPanel editor;
    /** The party list as the map's party button paints it ({@link FriendsWidget#paintList}). */
    FriendsWidget party;
    final Painted friendsView;

    MapPanel(Runnable openMap, Runnable openWindow, Consumer<WorldPoint> showOnMap, PartyMap friends,
        Function<WorldPoint, String> placeName) {
        super(false);
        this.showOnMap = showOnMap;
        this.friends = friends;
        friendsView = new Painted(PANEL_WIDTH - 36, (g, clicks) -> party.paintList(g, PANEL_WIDTH - 36, clicks, this));
        party = new FriendsWidget(friends, null, new MemberPages(friends), friendsView::repaint);
        this.placeName = placeName;
        setLayout(new BorderLayout());
        setBackground(DARK_GRAY_COLOR);

        JPanel content = column();
        content.setBorder(createEmptyBorder(10, 10, 10, 10));
        JPanel buttons = new JPanel(new GridLayout(1, 2, 6, 0));
        buttons.add(tip(button("Open map", openMap), t("t16")));
        buttons.add(tip(button("In a window", openWindow), t("mpWindow")));
        content.add(fitted(buttons));
        content.add(Box.createVerticalStrut(10));
        content.add(card(routeHeading, routeHolder));
        content.add(Box.createVerticalStrut(8));
        content.add(card(heading("Custom routes"), toursHolder));
        content.add(Box.createVerticalStrut(8));
        content.add(card(heading("Party"), friendsView));

        // Never wider than the sidebar: what does not fit wraps.
        JPanel top = new Fitted(new BorderLayout(), 0);
        top.setBackground(DARK_GRAY_COLOR);
        top.add(content, BorderLayout.NORTH);
        add(MapScreen.scroll(top), BorderLayout.CENTER);

        // Shown (its tab picked, the sidebar opened): a route that waited is built now.
        addHierarchyListener(e -> {
            if (waitingRoute != null && isShowing()) {
                showRoute(waitingRoute);
            }
        });
        showRoute(null);
        // Run on the Swing thread whenever members' news came (a party joined, a step, a drop, experience).
        friends.sidebarNews = friendsView::repaint;
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
            routeHolder.add(text(t("t17")));
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
        }, showOnMap, t("mpAll"));
        redoTours();
    }

    void redoTours() {
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
            toursHolder.add(text(t("t18")));
            JButton make = tip(button("New route", () -> edit(new Tour(Tour.unique(saved, "My route"),
                Collections.emptyList()))), t("t19"));
            make.setAlignmentX(LEFT_ALIGNMENT);
            toursHolder.add(make);
        }
        for (Tour tour : saved) {
            boolean runs = tour.name.equals(running);
            String what = plural(tour.stops.size(), "stop");
            toursHolder.add(entry(tour.name, runs ? "Running, " + what : what, runs ? new Color(120, 220, 140) : null,
                t("t20"), () -> edit(tour)));
        }
        refresh(toursHolder);
    }

    void stop() {
        party.ticker.stop();
    }

    /** Each member's place name and where it was worked out: again only once they moved off it (a scan of the labels). */
    final Map<Long, Object[]> placeNames = new HashMap<>();

    /** Where a member is, by name, for the party list. */
    String where(PartyMembers.Marker row) {
        if (row.point == null) {
            return t("mpNoShare");
        }
        Object[] known = placeNames.get(row.id);
        // A name not found (the labels still loading) is asked again.
        if (known == null || known[1] == null || ((WorldPoint) known[0]).getPlane() != row.point.getPlane()
            || ((WorldPoint) known[0]).distanceTo2D(row.point) > 8) {
            known = new Object[]{row.point, placeName.apply(row.point)};
            placeNames.put(row.id, known);
        }
        return known[1] != null ? (String) known[1] : "on the map";
    }

    static void refresh(JComponent c) {
        c.revalidate();
        c.repaint();
    }

    /** What a painter paints as on the map ({@link MapView.Clicks}), with its clicks and tooltips; as tall as it painted. */
    static class Painted extends JComponent {
        final List<MapView.Control> areas = new ArrayList<>();
        final ToIntBiFunction<Graphics2D, MapView.Clicks> painter;
        java.awt.Point mouse;

        Painted(int width, ToIntBiFunction<Graphics2D, MapView.Clicks> painter) {
            this.painter = painter;
            setPreferredSize(new Dimension(width, 40));
            setAlignmentX(LEFT_ALIGNMENT);
            var mice = new MouseAdapter() {
                public void mouseMoved(MouseEvent e) {
                    mouse = e.getPoint();
                    repaint();
                }

                public void mouseExited(MouseEvent e) {
                    mouse = null;
                    repaint();
                }

                public void mouseReleased(MouseEvent e) {
                    var at = area(e.getPoint());
                    if (at != null) {
                        at.action.run();
                    }
                }
            };
            addMouseListener(mice);
            addMouseMotionListener(mice);
            ToolTipManager.sharedInstance().registerComponent(this);
        }

        protected void paintComponent(Graphics graphics) {
            var g = (Graphics2D) graphics;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            areas.clear();
            int height = painter.applyAsInt(g, new MapView.Clicks() {
                public void add(RoundRectangle2D shape, Runnable action, String tooltip) {
                    areas.add(new MapView.Control(shape, action, tooltip));
                }

                public boolean hovered(RoundRectangle2D shape) {
                    return mouse != null && shape.contains(mouse);
                }
            });
            if (height != getPreferredSize().height) {
                setPreferredSize(new Dimension(getPreferredSize().width, height));
                revalidate();
            }
        }

        public String getToolTipText(MouseEvent e) {
            var at = area(e.getPoint());
            return at == null ? null : at.tooltip;
        }

        MapView.Control area(java.awt.Point p) {
            return areas.stream().filter(c -> c.shape.contains(p)).findFirst().orElse(null);
        }
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
