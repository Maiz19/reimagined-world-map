package com.hdmapreforged;

import static com.hdmapreforged.BaseMap.FULL;
import static com.hdmapreforged.BaseMap.SURFACE;
import static java.awt.BasicStroke.*;
import static com.hdmapreforged.PoiType.*;
import static java.awt.RenderingHints.*;
import static com.hdmapreforged.route.HousePlan.*;
import static com.hdmapreforged.TileCache.*;
import static java.lang.Math.*;

import com.hdmapreforged.route.HousePlan;
import java.util.concurrent.*;
import java.awt.*;
import java.io.*;
import java.nio.charset.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.*;
import java.util.*;
import java.util.List;
import java.util.function.*;
import java.util.stream.*;
import javax.swing.*;
import javax.swing.Timer;
import lombok.*;
import lombok.experimental.Accessors;
import net.runelite.api.coords.*;

/** The map: tiles, icons, transport lines and the player. x grows east, y north, one unit per tile; zoom is log2 pixels per tile. */
@Accessors(fluent = true)
final class MapView extends JComponent
{
    interface Listener
    {
        void selectionChanged(Poi poi);

        void viewChanged();

        void nearestRequested(WorldPoint point);
    }

    interface Chrome
    {
        void pickMap(Point at);
    }

    interface WindowControls
    {
        void close();

        void toggleMaximised();

        boolean isMaximised();
    }

    /** Where world points appear in the frame being painted; add 0.5 to a tile for its middle. */
    interface Projection
    {
        double screenX(double worldX);

        double screenY(double worldY);

        /** Log2 of screen pixels per game tile. */
        double zoom();

        BaseMap map();

        int plane();

        int width();

        int height();

        boolean shows(WorldPoint point);

        /** Where a game point is drawn on the map in view (the Kalphite Lair is drawn beside the desert caves). */
        default WorldPoint shown(WorldPoint point)
        {
            return point;
        }
    }

    interface Overlay
    {
        void paint(Graphics2D g, Projection projection);
    }

    interface Widget
    {
        void paint(Graphics2D g, int width, int height, int bottom, Clicks clicks);
    }

    /** Takes a left click before the icons do; true when used. */
    interface ClickCatcher
    {
        boolean clicked(Point at, Projection projection);
    }

    interface Clicks
    {
        void add(RoundRectangle2D shape, Runnable action, String tooltip);

        boolean hovered(RoundRectangle2D shape);
    }

    /** Which of our icons the game's own icon stands for (drawn by MapIconLayer), so no badge is drawn; once a frame. */
    interface BadgeCover
    {
        Predicate<Poi> covers(Projection projection);
    }

    interface MenuContributor
    {
        void contribute(JPopupMenu menu, WorldPoint point);
    }

    /** Clickable icons drawn by others (the game's); asked only when none of ours is under the mouse. */
    interface HitLayer
    {
        Poi hit(Point point, Projection projection);

        default Point2D labelAnchor(Poi poi, Projection projection)
        {
            return null;
        }

        default Point2D iconCenter(Poi poi, Projection projection)
        {
            return null;
        }
    }

    @RequiredArgsConstructor
    static final class Control
    {
        final RoundRectangle2D shape;
        final Runnable action;
        final String tooltip;
    }

    static final Color CONTROL_FILL = new Color(22, 24, 28, 225);
    static final Color CONTROL_HOVER = new Color(52, 56, 64, 235);
    static final Color CONTROL_EDGE = new Color(255, 255, 255, 45);
    static final Font CONTROL_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 13);
    static final int CONTROL = 34;

    static int floorGroupWidth(FontMetrics metrics)
    {
        return CONTROL * 2 + 4 + metrics.stringWidth("Floor 0") + 24;
    }

    static int widgetBottom(int height)
    {
        return height - MARGIN - 18 - CONTROL - 8;
    }

    static final int MARGIN = 12;

    static final double MIN_ZOOM = -5;
    static final double MAX_ZOOM = 6;
    static final Color BACKGROUND = new Color(10, 12, 16);
    static final Color LABEL_BACKGROUND = new Color(16, 16, 20, 225);
    static final Font LABEL_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 12);
    static final Font SMALL_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 10);
    static final Color VERSION_TEXT = new Color(255, 255, 255, 140);
    static final Color NOTICE_TEXT = new Color(200, 200, 200);
    /** Null when the build left none. */
    static final String VERSION = version();

    static String version()
    {
        try (var in = MapView.class.getResourceAsStream("version.txt"))
        {
            String version = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            // Unexpanded ("${version}") when built without the build's own resources step.
            return version.isEmpty() || version.startsWith("$") ? null : "v" + version;
        }
        catch (IOException e)
        {
            return null;
        }
    }
    static final BasicStroke THIN = new BasicStroke(1f);
    static final BasicStroke ICON_LINE = new BasicStroke(1.8f);
    static final BasicStroke ROUND_LINE = new BasicStroke(2.2f, CAP_ROUND, JOIN_ROUND);
    static final Font REGION_FONT = new Font(Font.SERIF, Font.BOLD, 17);
    static final Color WILDERNESS_TEXT = new Color(255, 190, 180);
    static final Color WILDERNESS_STRONG = new Color(255, 70, 50, 210);
    static final Color WILDERNESS_FAINT = new Color(255, 70, 50, 130);
    static final BasicStroke WILDERNESS_LINE = new BasicStroke(2f, CAP_BUTT, JOIN_MITER, 10f,
        null, 0f);
    static final BasicStroke WILDERNESS_DASHED = new BasicStroke(1f, CAP_BUTT, JOIN_MITER,
        10f, new float[]{5f, 4f}, 0f);
    static final Color REGION_COLOR = new Color(255, 236, 190);
    /** The game's own colour of its oceans' names; its seas' are lighter here, unlike towns'. */
    static final Color OCEAN_COLOR = new Color(0x1de0e0);
    static final Color SEA_COLOR = new Color(170, 225, 240);
    /** The game's own colour of a floor's name ("Upper level") on a map drawing floors side by side. */
    static final Color FLOOR_COLOR = new Color(0xffff00);
    /** The game's small places ("Altar" in the Ourania Cave), in the game's small size. */
    static final Font SMALL_PLACE_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 11);
    /** Small places from about three pixels a tile: the game's map shows them zoomed in too. */
    static final double PLACE_ZOOM = 1.5;
    static final int[] WILDERNESS_STARTS = {3520, 9920};
    static final double EASE = 0.28;

    @RequiredArgsConstructor
    static final class Drawn
    {
        final Poi poi;
        final double x;
        final double y;
        final double radius;
    }

    final TileCache tiles;
    final HdMapReforgedConfig config;
    Listener listener;

    @Getter
    BaseMaps maps;
    @Getter
    BaseMap map;
    @Getter
    int plane;
    /** Volatile: the route's extras thread reads them while Swing sets them. */
    @Getter
    volatile List<Poi> pois = Collections.emptyList();
    Set<Poi> hidden = Collections.emptySet();
    @Getter
    List<Poi> searchExtras = Collections.emptyList();
    @Getter
    List<MapData.Place> labels = Collections.emptyList();
    @Getter
    volatile Unlocks unlocks;
    @Getter
    Poi selected;
    @Getter
    Poi hovered;
    @Getter
    WorldPoint player;
    WorldPoint outside;
    /** The player is in a house, shown where it stands. */
    boolean home;
    boolean following;
    /** A party member followed instead of the player (their latest spot), and where they were; else null. */
    Supplier<WorldPoint> member;
    WorldPoint memberAt;

    double centerX = 3222;
    double centerY = 3218;
    double zoom = 1;
    double targetX = centerX;
    double targetY = centerY;
    @Getter
    double targetZoom = zoom;
    Point anchorScreen;
    Point2D anchorWorld;
    static final int STEP_MS = 15;
    final Timer animator = new Timer(STEP_MS, e -> step());
    long lastStep;
    /** The animator's step repaints. */
    boolean stepping;

    List<Drawn> drawn = new ArrayList<>();
    Chrome chrome;
    List<Control> controls = new ArrayList<>();
    final List<Widget> widgets = new CopyOnWriteArrayList<>();
    /** Kinds whose destination lines are hidden. */
    Set<PoiType> linesOff = EnumSet.noneOf(PoiType.class);
    Consumer<String> linesChoice = value -> { };
    Runnable checkForMap = () -> { };
    volatile boolean mapUnavailable;
    Control pressedControl;
    Control hoveredControl;
    Point pressed;
    Point lastDrag;
    boolean dragging;
    /** Animating or dragged: tiles scaled the fast way. */
    boolean moving;
    final List<Overlay> overlays = new ArrayList<>();
    final ScaledTiles scaled = new ScaledTiles();
    BadgeCover badgeCover;
    WindowControls windowControls;
    final TextSprites text = new TextSprites();
    final List<MenuContributor> menuContributors = new ArrayList<>();
    final List<HitLayer> hitLayers = new ArrayList<>();
    @Getter
    final Projection projection = new Projection()
    {
        public double screenX(double worldX)
        {
            return MapView.this.screenX(worldX);
        }

        public double screenY(double worldY)
        {
            return MapView.this.screenY(worldY);
        }

        public double zoom()
        {
            return zoom;
        }

        public BaseMap map()
        {
            return map;
        }

        public int plane()
        {
            return plane;
        }

        public int width()
        {
            return getWidth();
        }

        public int height()
        {
            return getHeight();
        }

        public boolean shows(WorldPoint point)
        {
            // One's house: on the surface map, at its edge.
            return point != null && maps != null && map != null && (HousePlan.contains(point.getX(), point.getY())
                ? map.id == SURFACE || map.id == FULL : map.id == FULL
                ? maps.find(point.getX(), point.getY()) != null || WorldMapMoves.drawn(point) != null
                : WorldMapMoves.toDrawn(map.id, point) != null || maps.drawsGame(map, point));
        }

        public WorldPoint shown(WorldPoint point)
        {
            return MapView.this.shown(point);
        }
    };

    WorldPoint gamePoint(WorldPoint drawn)
    {
        if (map == null)
        {
            return drawn;
        }
        if (HousePlan.contains(drawn.getX() - DRAWN_X + HousePlan.X, drawn.getY() - DRAWN_Y + HousePlan.Y))
        {
            return drawn.dx(HousePlan.X - DRAWN_X).dy(HousePlan.Y - DRAWN_Y);
        }
        var game = map.id == FULL ? WorldMapMoves.fromDrawnAnywhere(drawn)
            : WorldMapMoves.toWorld(map.id, drawn);
        // Ground the map draws on another floor than it is walked on (the region table's floor lines): that floor.
        return maps == null ? game : maps.gameFloor(map, game, drawn.getPlane(), ground);
    }

    /** Whether a game point is ground one stands on (the route data's), to tell which floor a click on the map means. */
    @Getter
    volatile Predicate<WorldPoint> ground;

    WorldPoint shown(WorldPoint game)
    {
        return shownOn(map, game);
    }

    static WorldPoint shownOn(BaseMap on, WorldPoint game)
    {
        if (game == null || on == null)
        {
            return game;
        }
        if (HousePlan.contains(game.getX(), game.getY()))
        {
            return game.dx(DRAWN_X - HousePlan.X).dy(DRAWN_Y - HousePlan.Y);
        }
        WorldPoint drawn;
        if (on.id == FULL)
        {
            var moved = WorldMapMoves.drawn(game);
            drawn = moved != null ? moved.point : game;
        }
        else
        {
            var moved = WorldMapMoves.toDrawn(on.id, game);
            drawn = moved != null ? moved : game;
        }
        // Ground the map draws on another floor than it is walked on (the region table's floor lines).
        int floor = on.maps == null ? -1 : on.maps.drawnFloor(on, game);
        return floor < 0 || floor == drawn.getPlane() ? drawn : new WorldPoint(drawn.getX(), drawn.getY(), floor);
    }

    /** The map showing a game point (one drawing it elsewhere first), or null. */
    BaseMap mapOf(WorldPoint game)
    {
        return maps == null || game == null ? null : maps.find(game);
    }

    MapView(TileCache tiles, HdMapReforgedConfig config)
    {
        this.tiles = tiles;
        this.config = config;
        setLinesOff(config.linesOff());
        following = config.followPlayer();
        setOpaque(true);
        // Never takes focus, so typing goes to the game.
        setFocusable(false);
        setPreferredSize(new Dimension(225, 300));
        setMinimumSize(new Dimension(120, 120));
        var mouse = new MouseAdapter()
        {
            public void mousePressed(MouseEvent e)
            {
                pressedControl = control(e.getPoint());
                if (pressedControl != null)
                {
                    pressed = null;
                    return;
                }
                pressed = e.getPoint();
                lastDrag = e.getPoint();
                dragging = false;
                if (e.isPopupTrigger())
                {
                    showContextMenu(e);
                }
            }

            public void mouseDragged(MouseEvent e)
            {
                if (pressed == null)
                {
                    return;
                }
                if (!dragging && pressed.distance(e.getPoint()) > 3)
                {
                    dragging = true;
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                }
                if (dragging)
                {
                    double scale = scale();
                    centerX -= (e.getX() - lastDrag.x) / scale;
                    centerY += (e.getY() - lastDrag.y) / scale;
                    clampCenter();
                    targetHere();
                    targetZoom = zoom;
                    anchorScreen = null;
                    lastDrag = e.getPoint();
                    setFollowing(false);
                    moving = true;
                    repaint();
                }
            }

            public void mouseReleased(MouseEvent e)
            {
                if (pressedControl != null)
                {
                    Control target = pressedControl;
                    pressedControl = null;
                    // Checked against the pressed shape: repaints while the mouse is down make new controls.
                    if (target.shape.contains(e.getPoint()) && e.getButton() == MouseEvent.BUTTON1)
                    {
                        target.action.run();
                    }
                    return;
                }
                if (e.isPopupTrigger())
                {
                    showContextMenu(e);
                }
                else if (e.getButton() == 4)
                {
                    // The mouse's own back button.
                    goBack();
                }
                else if (!dragging && pressed != null && e.getButton() == MouseEvent.BUTTON1)
                {
                    if (caught(e.getPoint()))
                    {
                        repaint();
                    }
                    else if (iconAt(e.getPoint()) != null || e.getClickCount() < 2)
                    {
                        var hit = iconAt(e.getPoint());
                        select(hit);
                        var inside = mapBelow(hit);
                        if (inside != null)
                        {
                            // A map link: go in; Back returns.
                            goIn(inside);
                        }
                    }
                    else
                    {
                        zoomAt(1, e.getPoint());
                    }
                }
                pressed = null;
                if (dragging)
                {
                    moving = animator.isRunning();
                    repaint();
                }
                dragging = false;
                updateHover(e.getPoint());
            }

            public void mouseMoved(MouseEvent e)
            {
                updateHover(e.getPoint());
            }

            public void mouseExited(MouseEvent e)
            {
                if (hovered != null || hoveredControl != null)
                {
                    hovered = null;
                    hoveredControl = null;
                    // Else a control's tooltip would show anywhere over the map afterwards.
                    setToolTipText(null);
                    repaint();
                }
            }

            public void mouseWheelMoved(MouseWheelEvent e)
            {
                zoomAt(-e.getPreciseWheelRotation() * 0.5, e.getPoint());
            }
        };
        addMouseListener(mouse);
        addMouseMotionListener(mouse);
        addMouseWheelListener(mouse);
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && !isShowing())
            {
                // Its window closed: the copies go, the tile cache keeps a few, and the next opening reads from disk.
                scaled.clear();
                tiles.closed(this);
                drawnTiles = 0;
            }
        });
        addComponentListener(new ComponentAdapter()
        {
            public void componentResized(ComponentEvent e)
            {
                double z = clampZoom(targetZoom);
                if (z != targetZoom)
                {
                    zoom = targetZoom = z;
                }
                clampCenter();
                if (!animator.isRunning())
                {
                    targetHere();
                }
                repaint();
            }
        });
    }

    void addOverlay(Overlay overlay)
    {
        overlays.add(overlay);
        repaint();
    }

    void removeOverlay(Overlay overlay)
    {
        overlays.remove(overlay);
        repaint();
    }

    boolean linesShown(PoiType type)
    {
        return !linesOff.contains(type);
    }

    void setLinesShown(PoiType type, boolean shown)
    {
        Set<PoiType> now = EnumSet.copyOf(linesOff);
        if (shown)
        {
            now.remove(type);
        }
        else
        {
            now.add(type);
        }
        linesOff = now;
        repaint();
        linesChoice.accept(now.stream().map(Enum::name).collect(Collectors.joining(",")));
    }

    /** Setting format: "FAIRY_RING,SPIRIT_TREE". */
    void setLinesOff(String value)
    {
        Set<PoiType> off = EnumSet.noneOf(PoiType.class);
        for (String name : value == null ? new String[0] : value.split(","))
        {
            for (PoiType t : PoiType.values())
            {
                if (t.name().equals(name.trim()))
                {
                    off.add(t);
                }
            }
        }
        linesOff = off;
        repaint();
    }

    final List<ClickCatcher> clickCatchers = new CopyOnWriteArrayList<>();

    boolean caught(Point at)
    {
        return clickCatchers.stream().anyMatch(catcher -> catcher.clicked(at, projection));
    }

    void addWidget(Widget widget)
    {
        widgets.add(widget);
        repaint();
    }

    void removeWidget(Widget widget)
    {
        widgets.remove(widget);
        repaint();
    }

    void setWindowControls(WindowControls controls)
    {
        windowControls = controls;
        repaint();
    }

    void setBadgeCover(BadgeCover cover)
    {
        badgeCover = cover;
        repaint();
    }

    void setMaps(BaseMaps maps)
    {
        boolean first = map == null;
        this.maps = maps;
        var current = map == null ? null : maps.byId(map.id);
        map = current != null ? current : maps.surface();
        shownCache.clear();
        labelsOnMapFor = null;
        if (first && following && player != null)
        {
            // Opened before any map was had: placed as opening does.
            jumpToPlayer();
        }
        repaint();
    }

    /**
     * Ordered so the most useful icons win when they overlap; {@code hidden} icons are not drawn but still serve routes
     * and search.
     */
    void setPois(List<Poi> pois, Set<Poi> hidden)
    {
        var ordered = new ArrayList<Poi>(pois);
        ordered.sort(Comparator.comparingInt((Poi p) -> p.type.layer.ordinal()));
        this.pois = ordered;
        this.hidden = hidden;
        shownCache.clear();
        repaint();
    }

    void setLabels(List<MapData.Place> labels)
    {
        this.labels = labels;
        repaint();
    }

    void setUnlocks(Unlocks unlocks)
    {
        this.unlocks = unlocks;
        repaint();
    }

    static boolean visible(Poi poi, Filter filter)
    {
        if (filter.shows(poi))
        {
            return true;
        }
        // An icon also stands for what is at the same place (a teleport landing there).
        for (var other : poi.nearby())
        {
            if (filter.shows(other))
            {
                return true;
            }
        }
        return false;
    }

    boolean usable(Poi poi)
    {
        return usable(poi, config.onlyUsable());
    }

    boolean usable(Poi poi, boolean onlyUsable)
    {
        for (var member : poi.members())
        {
            if (!onlyUsable || unlocks == null || unlocks.usable(member.needs))
            {
                return true;
            }
        }
        return false;
    }

    boolean usable(Needs needs)
    {
        return !config.onlyUsable() || unlocks == null || unlocks.usable(needs);
    }

    void setFollowing(boolean following)
    {
        member = following ? member : null;
        if (this.following == following)
        {
            return;
        }
        this.following = following;
        if (following && player != null)
        {
            follow();
        }
        fireViewChanged();
    }

    void setPlane(int plane)
    {
        this.plane = max(0, min(3, plane));
        repaint();
        fireViewChanged();
    }

    /** A floor the user chose: off the character's floor, following would take the view back at their next step. */
    void choosePlane(int plane)
    {
        int chosen = max(0, min(3, plane));
        if (following && player != null && shown(player).getPlane() != chosen)
        {
            setFollowing(false);
        }
        setPlane(chosen);
    }

    void showMap(BaseMap map, WorldPoint focus, double zoomLevel)
    {
        boolean changed = map != this.map;
        this.map = map;
        if (focus != null)
        {
            plane = focus.getPlane();
        }
        jumpOrAnimate(changed, focus != null ? focus.getX() + 0.5 : map.centerX, focus != null ? focus.getY() + 0.5
            : map.centerY, zoomLevel);
        fireViewChanged();
    }

    void focus(Poi poi)
    {
        setFollowing(false);
        lookAt(poi.map != null && !poi.isOn(map) ? poi.map : null, poi.drawn());
        select(poi);
    }

    void focus(WorldPoint point)
    {
        setFollowing(false);
        var target = mapOf(point);
        boolean notFull = map == null || map.id != FULL;
        if (target == null && maps != null && notFull)
        {
            // On no map of its own (an instance): the map of everything draws it.
            target = maps.byId(FULL);
        }
        lookAt(target != null && target != map && notFull ? target : null, point);
    }

    void lookAt(BaseMap other, WorldPoint game)
    {
        if (other != null)
        {
            rememberForBack(other);
        }
        else
        {
            other = map;
        }
        showMap(other, shownOn(other, game), max(targetZoom, 1));
    }

    void select(Poi poi)
    {
        if (poi == selected)
        {
            return;
        }
        selected = poi;
        repaint();
        if (listener != null)
        {
            listener.selectionChanged(poi);
        }
    }

    void zoomBy(double delta)
    {
        zoomAt(delta, new Point(getWidth() / 2, getHeight() / 2));
    }

    /** Follows a party member as it follows the player, until the view is moved. */
    void follow(Supplier<WorldPoint> member)
    {
        focus(memberAt = member.get());
        this.member = member;
        following = true;
        fireViewChanged();
    }

    /** To where the member followed is now. Swing thread. */
    void followMember()
    {
        var at = following && member != null ? member.get() : null;
        if (following && member != null && at == null)
        {
            // The member left the party or is no longer shown: the player again.
            member = null;
            if (player != null)
            {
                follow();
            }
        }
        else if (at != null && !at.equals(memberAt))
        {
            memberAt = at;
            follow();
        }
    }

    void setPlayer(WorldPoint player)
    {
        boolean moved = !Objects.equals(player, this.player);
        this.player = player;
        followMember();
        if (moved && following && member == null && player != null)
        {
            follow();
        }
        else if (moved)
        {
            repaint();
        }
    }

    /** Where "nearest" counts from: the player, in one's house its portal outside. */
    WorldPoint near()
    {
        return player != null && HousePlan.contains(player.getX(), player.getY()) ? outside : player;
    }

    void setHome(boolean home)
    {
        this.home = home;
        repaint();
    }

    /** The default zoom on the player: two to the third pixels a tile, setting 6. */
    static final double PLAYER_ZOOM = 3;

    /** On the player, as set ("Default zoom": each step one press of +). */
    double playerZoom()
    {
        return clampZoom(PLAYER_ZOOM + config.defaultZoom() - 6);
    }

    void centerOnPlayer()
    {
        back.clear();
        member = null;
        if (player != null)
        {
            following = false;
            targetZoom = playerZoom();
        }
        setFollowing(true);
    }

    void jumpToPlayer()
    {
        if (player == null)
        {
            setFollowing(true);
            return;
        }
        var containing = mapOf(player);
        if (containing != null)
        {
            map = containing;
        }
        var at = shown(player);
        plane = at.getPlane();
        jumpOrAnimate(true, at.getX() + 0.5, at.getY() + 0.5, playerZoom());
        following = true;
        member = null;
        fireViewChanged();
    }

    void follow()
    {
        var who = member != null ? memberAt : player;
        var containing = mapOf(who);
        if (containing != null && containing != map)
        {
            var area = areaOf(containing, who);
            showMap(containing, shownOn(containing, who), area != null ? fitZoom(area) : targetZoom);
            return;
        }
        var at = shown(who);
        if (plane != at.getPlane())
        {
            plane = at.getPlane();
            fireViewChanged();
        }
        animateTo(at.getX() + 0.5, at.getY() + 0.5, targetZoom);
    }

    double scale()
    {
        return pow(2, zoom);
    }

    double screenX(double worldX)
    {
        return getWidth() / 2.0 + (worldX - centerX) * scale();
    }

    double screenY(double worldY)
    {
        return getHeight() / 2.0 - (worldY - centerY) * scale();
    }

    Point2D toWorld(Point p)
    {
        double scale = scale();
        return new Point2D.Double(centerX + (p.x - getWidth() / 2.0) / scale, centerY - (p.y - getHeight() / 2.0) / scale);
    }

    void jumpOrAnimate(boolean jump, double x, double y, double z)
    {
        if (jump)
        {
            animator.stop();
            moving = false;
            anchorScreen = null;
            centerX = x;
            centerY = y;
            zoom = targetZoom = clampZoom(z);
            clampCenter();
            targetHere();
            repaint();
        }
        else
        {
            animateTo(x, y, z);
        }
    }

    void animateTo(double x, double y, double z)
    {
        anchorScreen = null;
        targetZoom = clampZoom(z);
        double scale = pow(2, targetZoom);
        boolean sized = map != null && getWidth() > 0 && getHeight() > 0;
        targetX = sized ? clampAxis(x, map.minX, map.maxX) : x;
        targetY = sized ? clampAxis(y, map.minY, map.maxY) : y;
        double pixels = hypot(targetX - centerX, targetY - centerY) * scale;
        if (!isShowing() || getWidth() > 0 && pixels > 3 * max(getWidth(), getHeight()))
        {
            // Closed (following the player), or far away: flying would load every tile on the way; jump.
            jumpOrAnimate(true, x, y, targetZoom);
            return;
        }
        moving = true;
        animator.start();
    }

    void zoomAt(double delta, Point at)
    {
        double next = clampZoom(targetZoom + delta);
        if (next == targetZoom)
        {
            return;
        }
        anchorScreen = at;
        anchorWorld = toWorld(at);
        targetZoom = next;
        moving = true;
        animator.start();
        fireViewChanged();
    }

    double clampZoom(double z)
    {
        if (!Double.isFinite(z))
        {
            // A NaN zoom (broken map bounds) would blank the view for good.
            return Double.isFinite(targetZoom) ? targetZoom : 1;
        }
        return max(minZoom(), min(MAX_ZOOM, z));
    }

    /** The whole map just fits the view, so it never shrinks to a small picture in a black field. */
    double minZoom()
    {
        if (map == null || getWidth() <= 0 || getHeight() <= 0)
        {
            return MIN_ZOOM;
        }
        return max(MIN_ZOOM, min(1, fit(map, getWidth(), getHeight())));
    }

    /** Log2 of the scale at which a map's bounds just fit {@code width} by {@code height}. */
    static double fit(BaseMap map, double width, double height)
    {
        return log(min(width / max(1, map.maxX - map.minX), height / max(1, map.maxY - map.minY))) / log(2);
    }

    /** Once the view has a size, its centre stays on the map. */
    void clampCenter()
    {
        if (map == null || getWidth() <= 0 || getHeight() <= 0)
        {
            return;
        }
        centerX = clampAxis(centerX, map.minX, map.maxX);
        centerY = clampAxis(centerY, map.minY, map.maxY);
    }

    /** The view stays where it is now. */
    void targetHere()
    {
        targetX = centerX;
        targetY = centerY;
    }

    /** The map's edge may come as far as the middle of the view: room to look past it, also on a small dungeon map. */
    static double clampAxis(double center, double min, double max)
    {
        return max(min, min(max, center));
    }

    /**
     * The share of the way left a step covers, {@code steps} animator periods after the last: a late step (after a slow
     * frame) catches up rather than slowing the animation down. A first step, or one after a long stall, counts as one.
     */
    static double ease(double steps)
    {
        return 1 - pow(1 - EASE, steps > 0 && steps <= 4 ? steps : 1);
    }

    void step()
    {
        long now = System.nanoTime();
        double ease = ease((now - lastStep) / (STEP_MS * 1e6));
        lastStep = now;
        double dz = targetZoom - zoom;
        boolean done = !(abs(dz) > 0.002);
        zoom = done ? targetZoom : zoom + dz * ease;
        if (anchorScreen != null)
        {
            double scale = scale();
            centerX = targetX = anchorWorld.getX() - (anchorScreen.x - getWidth() / 2.0) / scale;
            centerY = targetY = anchorWorld.getY() + (anchorScreen.y - getHeight() / 2.0) / scale;
            if (done)
            {
                anchorScreen = null;
            }
        }
        else
        {
            double dx = targetX - centerX;
            double dy = targetY - centerY;
            if (hypot(dx, dy) * scale() > 0.3)
            {
                centerX += dx * ease;
                centerY += dy * ease;
                done = false;
            }
            else
            {
                centerX = targetX;
                centerY = targetY;
            }
        }
        clampCenter();
        if (anchorScreen != null || done)
        {
            targetHere();
        }
        if (done)
        {
            animator.stop();
            moving = false;
        }
        stepping = true;
        repaint();
        stepping = false;
    }

    /**
     * While the view animates, its next step (within 15 ms) paints it whole: other repaints, a tile arriving, only added
     * frames, two or three a step zooming out.
     */
    public void repaint(long tm, int x, int y, int width, int height)
    {
        if (stepping || animator == null || !animator.isRunning())
        {
            super.repaint(tm, x, y, width, height);
        }
    }

    void stop()
    {
        animator.stop();
        toastTimer.stop();
        scaled.clear();
        shownCache.clear();
    }

    void fireViewChanged()
    {
        if (listener != null)
        {
            listener.viewChanged();
        }
    }

    protected void paintComponent(Graphics graphics)
    {
        var g = (Graphics2D) graphics.create();
        try
        {
            g.setColor(BACKGROUND);
            g.fillRect(0, 0, getWidth(), getHeight());
            if (map == null)
            {
                paintNoMap(g);
                return;
            }
            paintTiles(g);
            g.setRenderingHint(KEY_ANTIALIASING, VALUE_ANTIALIAS_ON);
            g.setRenderingHint(KEY_STROKE_CONTROL, VALUE_STROKE_PURE);
            g.setRenderingHint(KEY_TEXT_ANTIALIASING, VALUE_TEXT_ANTIALIAS_ON);
            if (config.showWilderness())
            {
                paintWilderness(g);
            }
            if (config.showLabels())
            {
                paintPlaceNames(g);
            }
            paintLinks(g);
            paintIcons(g);
            for (Overlay overlay : new ArrayList<>(overlays))
            {
                var layer = (Graphics2D) g.create();
                try
                {
                    overlay.paint(layer, projection);
                }
                finally
                {
                    layer.dispose();
                }
            }
            paintPlayer(g);
            if (hovered != null && hovered != selected)
            {
                paintLabel(g, hovered);
            }
            if (selected != null && selected.isOn(map))
            {
                paintLabel(g, selected);
            }
            paintControls(g);
        }
        finally
        {
            g.dispose();
        }
    }

    /** {x0, x1, y0, y1}: the tiles of a level in a view centred on a world point, {@code scale} pixels a game tile. */
    int[] tilesInView(int level, double x, double y, double scale)
    {
        double w = getWidth() / 2.0 / scale;
        double h = getHeight() / 2.0 / scale;
        // Another map's tiles draw beyond the bounds: cut at the edges of the tiles of zoom 1, not at those of the level
        // drawn, or each zoom would end it at another line (Dorgesh-Kaan South Dungeon's agility course).
        int edge = map.tiles != map.id ? (int) worldPerTile(1) : 1;
        return new int[]{max(tileIndex(x - w, level), tileIndex(snap(map.minX, edge), level)),
            min(tileIndex(x + w, level), tileIndex(snap(map.maxX + edge - 1, edge) - 1, level)),
            max(tileIndex(y - h, level), tileIndex(snap(map.minY, edge), level)),
            min(tileIndex(y + h, level), tileIndex(snap(map.maxY + edge - 1, edge) - 1, level))};
    }

    static int snap(int v, int edge)
    {
        return floorDiv(v, edge) * edge;
    }

    /** A range's tiles, the farthest from its middle first: queued in this order, the middle loads first. */
    static List<int[]> middleLast(int[] range)
    {
        var order = new ArrayList<int[]>();
        for (int ty = range[2]; ty <= range[3]; ty++)
        {
            for (int tx = range[0]; tx <= range[1]; tx++)
            {
                order.add(new int[]{tx, ty});
            }
        }
        double midX = (range[0] + range[1]) / 2.0;
        double midY = (range[2] + range[3]) / 2.0;
        order.sort(Comparator.comparingDouble((int[] t) -> -hypot(t[0] - midX, t[1] - midY)));
        return order;
    }

    /** An upper floor shows the ground floor under it: two tiles a spot. */
    int count(int[] range)
    {
        return max(0, range[1] - range[0] + 1) * max(0, range[3] - range[2] + 1) * (plane > 0 ? 2 : 1);
    }

    void paintTiles(Graphics2D g)
    {
        if (tiles.version == null)
        {
            String notice = "Loading map…";
            g.setColor(NOTICE_TEXT);
            g.setFont(LABEL_FONT);
            g.drawString(notice, (getWidth() - g.getFontMetrics().stringWidth(notice)) / 2, getHeight() / 2);
            return;
        }
        int level = MapIconLayer.tileLevel(zoom);
        double scale = scale();
        var range = tilesInView(level, centerX, centerY, scale);
        // Past the memory cap (a 4K window's upper floor), a coarser level: a quarter of the tiles.
        while (level > TileCache.MIN_ZOOM && !tiles.fits(this, count(range)))
        {
            range = tilesInView(--level, centerX, centerY, scale);
        }
        // Zooming out, the tiles the zoom ends on load first and stand in for the levels on the way, which are drawn only
        // from memory: loading those too (hundreds of tiles, each shown a few frames) made zooming out stutter, and it
        // flashed black where nothing stood in yet.
        int[] end = null;
        endLevel = level;
        if (targetZoom < zoom && animator.isRunning())
        {
            double endScale = pow(2, targetZoom);
            double x = anchorScreen == null ? targetX : anchorWorld.getX() - (anchorScreen.x - getWidth() / 2.0) / endScale;
            double y = anchorScreen == null ? targetY : anchorWorld.getY() + (anchorScreen.y - getHeight() / 2.0) / endScale;
            x = clampAxis(x, map.minX, map.maxX);
            y = clampAxis(y, map.minY, map.maxY);
            endLevel = min(level, MapIconLayer.tileLevel(targetZoom));
            end = tilesInView(endLevel, x, y, endScale);
            while (endLevel > TileCache.MIN_ZOOM && !tiles.fits(this, count(end)))
            {
                end = tilesInView(--endLevel, x, y, endScale);
            }
        }
        // Not under an upper floor: the ground floor below it has no stand-ins.
        passing = endLevel < level && plane == 0;
        double span = worldPerTile(level);
        int visible = count(range);
        tiles.beginFrame(this, end == null ? visible : max(visible, count(end)));
        // A frame after one with no tile at all, not even a stand-in (the map just opened or switched), may read some tiles
        // from disk. At a zoom's change of level the stand-ins show meanwhile: reading there stalled the zoom.
        int diskNow = drawnTiles == 0 ? FIRST_FRAME_DISK_TILES : 0;
        long diskUntil = System.nanoTime() + FIRST_FRAME_DISK_NANOS;
        drawnTiles = 0;
        scaled.beginFrame(need(visible));
        boolean settled = zoom == targetZoom;
        // Past the most detailed tiles, crisp squares: smoothing blurred them and cost a copy of each.
        boolean smooth = scale * span <= TILE_SIZE * 1.01;
        // Smooth scaling is costly on large windows and invisible while moving.
        g.setRenderingHint(KEY_INTERPOLATION, moving || !smooth
            ? VALUE_INTERPOLATION_NEAREST_NEIGHBOR : VALUE_INTERPOLATION_BILINEAR);
        for (var t : middleLast(range))
        {
            int tx = t[0];
            int ty = t[1];
            int left = (int) floor(screenX(tx * span));
            int right = (int) floor(screenX((tx + 1) * span));
            int top = (int) floor(screenY((ty + 1) * span));
            int bottom = (int) floor(screenY(ty * span));
            if (plane > 0)
            {
                paintGroundBelow(g, level, tx, ty, left, top, right, bottom, settled, smooth);
            }
            var key = new TileCache.Key(map.tiles, level, plane, tx, ty);
            BufferedImage image = passing ? tiles.peek(key) : tiles.get(key);
            if (image == null && diskNow > 0 && System.nanoTime() < diskUntil)
            {
                diskNow--;
                image = tiles.fromDiskNow(key);
            }
            if (image != null)
            {
                drawnTiles++;
                scaled.draw(g, key, image, left, top, right - left, bottom - top, settled, smooth);
            }
            else if (paintFallback(g, level, tx, ty, left, top, right, bottom))
            {
                drawnTiles++;
            }
        }
        if (end != null)
        {
            for (var t : middleLast(end))
            {
                var key = new TileCache.Key(map.tiles, endLevel, plane, t[0], t[1]);
                // Where nothing stands in yet, it is asked for last below: loaded first.
                if (!standIns.contains(key))
                {
                    tiles.get(key);
                }
            }
        }
        // Where nothing is loaded yet, a tile two levels coarser (one covers 16; zooming out further, the end's) shows the
        // view blurry at once.
        for (var coarse : standIns)
        {
            tiles.get(coarse);
        }
        standIns.clear();
        tiles.endFrame();
        // Scaled copies are made a few a frame, so the end of a zoom has no hitch: paint again until all are there.
        if (scaled.endFrame())
        {
            repaint();
        }
    }

    /**
     * Under an upper floor the ground floor, dimmed, as the game's own map draws upper floors: our tiles of a floor hold
     * only what is on it.
     */
    void paintGroundBelow(Graphics2D g, int level, int tx, int ty, int left, int top, int right, int bottom,
        boolean settled, boolean smooth)
    {
        var ground = new TileCache.Key(map.tiles, level, 0, tx, ty);
        BufferedImage below = tiles.get(ground);
        if (below != null)
        {
            scaled.draw(g, ground, below, left, top, right - left, bottom - top, settled, smooth);
            g.setColor(BELOW_SHADE);
            g.fillRect(left, top, right - left, bottom - top);
        }
    }

    static final Color BELOW_SHADE = new Color(0, 0, 0, 150);

    /**
     * Other floors' icons show (faded) above ground when zoomed in, and where the game's map draws a place's floors
     * flat (Keldagrim's houses); elsewhere underground, floors on one spot are different places.
     */
    boolean showsOtherFloor(Poi poi, WorldPoint at)
    {
        // Further out they crowd the floor in view.
        return targetZoom >= 2 && (at.getY() < UNDERGROUND_Y
            || at.getPlane() < WorldMapMoves.floorsDrawnFlat(poi.map.id, at.getX(), at.getY()));
    }

    static final int UNDERGROUND_Y = 4800;

    /** True when a coarser or finer tile stood in. */
    boolean paintFallback(Graphics2D g, int level, int tx, int ty, int left, int top, int right, int bottom)
    {
        boolean drawn = false;
        int standInLevel = passing ? max(TileCache.MIN_ZOOM, min(endLevel, level - 2)) : level - 2;
        int span = 1 << (level - standInLevel);
        var standIn = new TileCache.Key(map.tiles, standInLevel, plane, floorDiv(tx, span),
            floorDiv(ty, span));
        if (standInLevel >= TileCache.MIN_ZOOM)
        {
            standIns.add(standIn);
        }
        for (int coarser = level - 1; coarser >= TileCache.MIN_ZOOM; coarser--)
        {
            int factor = 1 << (level - coarser);
            int px = floorDiv(tx, factor);
            int py = floorDiv(ty, factor);
            BufferedImage parent = tiles.peek(new TileCache.Key(map.tiles, coarser, plane, px, py));
            if (parent != null)
            {
                int part = TILE_SIZE / factor;
                int sx = (tx - px * factor) * part;
                int sy = (factor - 1 - (ty - py * factor)) * part;
                g.drawImage(parent, left, top, right, bottom, sx, sy, sx + part, sy + part, null);
                drawn = true;
                if (coarser >= standInLevel)
                {
                    standIns.remove(standIn);
                }
                break;
            }
        }
        // Zooming out, the finer tiles of the level before are usually still loaded: drawn over, so nothing flashes.
        if (level < TileCache.MAX_ZOOM)
        {
            int midX = (left + right) / 2;
            int midY = (top + bottom) / 2;
            for (int dy = 0; dy < 2; dy++)
            {
                for (int dx = 0; dx < 2; dx++)
                {
                    BufferedImage child = tiles.peek(new TileCache.Key(map.tiles, level + 1, plane, tx * 2 + dx, ty * 2 + dy));
                    if (child != null)
                    {
                        int x0 = dx == 0 ? left : midX;
                        int x1 = dx == 0 ? midX : right;
                        // Tile rows grow northwards, screen rows southwards.
                        int y0 = dy == 0 ? midY : top;
                        int y1 = dy == 0 ? bottom : midY;
                        g.drawImage(child, x0, y0, x1 - x0, y1 - y0, null);
                        drawn = true;
                    }
                }
            }
        }
        return drawn;
    }

    final LinkedHashSet<TileCache.Key> standIns = new LinkedHashSet<>();
    int drawnTiles;
    /** Zooming out, the level the zoom ends on: its tiles stand in for those of the levels passed. */
    int endLevel;
    /** Zooming out through this frame's level: its tiles are drawn only from memory. */
    boolean passing;
    static final int FIRST_FRAME_DISK_TILES = 24;
    static final long FIRST_FRAME_DISK_NANOS = 60_000_000L;

    static final Layer[] LAYERS = Layer.values();

    /** Which layers show, read once per frame rather than per icon. */
    final class Filter
    {
        final boolean[] layers = new boolean[LAYERS.length];
        final boolean shortcuts;
        final boolean onlyUsable = config.onlyUsable();

        Filter()
        {
            for (Layer layer : LAYERS)
            {
                layers[layer.ordinal()] = shown(layer);
            }
            shortcuts = config.showSkilling() && targetZoom >= 2;
        }

        /** Hidden below a zoom level each, so the zoomed-out map stays readable. */
        boolean shown(Layer layer)
        {
            return toggled(layer) && targetZoom >= layer.minZoom;
        }

        /** Shown and usable. */
        boolean shows(Poi poi)
        {
            return (poi.type == AGILITY_SHORTCUT && poi.type.layer == Layer.SKILLING ? shortcuts
                : layers[poi.type.layer.ordinal()]) && usable(poi, onlyUsable);
        }
    }

    /** The setting of a layer: the game's own icons of its kinds follow it too (a bank's sprite with the banks). */
    boolean toggled(Layer layer)
    {
        return layer.setting.test(config);
    }

    boolean inGroup(Poi poi)
    {
        return selected != null && selected.type == TELEPORT && poi != selected && selected.group != null
            && poi.hasGroup(selected.group);
    }

    /** Lines from the selected icon to where it leads: one path per style, stroked together (fairy rings have 50+). */
    void paintLinks(Graphics2D g)
    {
        if (selected == null || !selected.isOn(map) || !linesShown(selected.type))
        {
            return;
        }
        var from = shown(selected.drawn());
        var icon = fromLayers(layer -> layer.iconCenter(selected, projection));
        double sx = icon != null ? icon.getX() : screenX(from.getX() + 0.5);
        double sy = icon != null ? icon.getY() : screenY(from.getY() + 0.5);
        Color color = selected.type.color;
        var links = selected.links();
        float width = links.size() > 12 ? 1.6f : 2.4f;
        var usableLines = new Path2D.Double();
        var lockedLines = new Path2D.Double();
        var ends = new ArrayList<double[]>();
        for (var link : links)
        {
            boolean usable = unlocks == null || unlocks.usable(link.needs);
            if (!onThisMap(link.map) || !usable && config.onlyUsable())
            {
                continue;
            }
            var to = shown(link.point);
            double dx = screenX(to.getX() + 0.5);
            double dy = screenY(to.getY() + 0.5);
            if (hypot(dx - sx, dy - sy) < 2)
            {
                continue;
            }
            double mx = (sx + dx) / 2 - (dy - sy) * 0.18;
            double my = (sy + dy) / 2 + (dx - sx) * 0.18;
            if (max(sx, max(dx, mx)) < -8 || min(sx, min(dx, mx)) > getWidth() + 8
                || max(sy, max(dy, my)) < -8 || min(sy, min(dy, my)) > getHeight() + 8)
            {
                continue;
            }
            var into = usable ? usableLines : lockedLines;
            into.moveTo(sx, sy);
            into.quadTo(mx, my, dx, dy);
            if (dx > -8 && dy > -8 && dx < getWidth() + 8 && dy < getHeight() + 8)
            {
                ends.add(new double[]{dx, dy, usable ? 1 : 0});
            }
        }
        // Each stroke costs about 80 ns a pixel of line (fairy rings: 2.4-6.4 ms): no shadow while dragged or zoomed by
        // hand. Following the player keeps it, or it would blink each tick.
        boolean shadow = !dragging && anchorScreen == null;
        // Dashed: a random destination, or a locked one (grey).
        stroke(g, lockedLines, LOCKED_LINK, width, LINK_DASH, shadow);
        stroke(g, usableLines, color, width, selected.type.isRandomDestination() ? LINK_DASH : null, shadow);
        for (var end : ends)
        {
            PoiIcons.paintDot(g, end[0], end[1], (end[2] > 0 ? color : LOCKED_LINK).darker());
        }
    }

    /** The first hit layer's answer, or null. */
    <T> T fromLayers(Function<HitLayer, T> ask)
    {
        for (var layer : hitLayers)
        {
            T at = ask.apply(layer);
            if (at != null)
            {
                return at;
            }
        }
        return null;
    }

    static final Color LOCKED_LINK = new Color(150, 150, 150);
    static final float[] LINK_DASH = {6f, 5f};
    static final Color LINK_SHADOW = new Color(0, 0, 0, 120);

    static void stroke(Graphics2D g, Path2D lines, Color color, float width, float[] dash, boolean shadow)
    {
        if (lines.getCurrentPoint() == null)
        {
            return;
        }
        // Wide antialiased curves are costly: one dark stroke under the colored one, butt caps.
        if (shadow)
        {
            g.setStroke(linkStroke(width + 2.2f, dash));
            g.setColor(LINK_SHADOW);
            g.draw(lines);
        }
        g.setStroke(linkStroke(width, dash));
        g.setColor(LINK_COLORS.computeIfAbsent(color, c -> new Color(c.getRed(), c.getGreen(), c.getBlue(), 220)));
        g.draw(lines);
    }

    static final Map<String, BasicStroke> LINK_STROKES = new ConcurrentHashMap<>();
    static final Map<Color, Color> LINK_COLORS = new ConcurrentHashMap<>();

    static BasicStroke linkStroke(float width, float[] dash)
    {
        return LINK_STROKES.computeIfAbsent(width + (dash == null ? "" : Arrays.toString(dash)),
            key -> new BasicStroke(width, CAP_BUTT, JOIN_ROUND, 10f, dash, 0f));
    }

    boolean onThisMap(BaseMap other)
    {
        return other != null && (map.id == FULL || other == map);
    }

    void paintIcons(Graphics2D g)
    {
        double size = iconSize(zoom);
        // Zoomed out, icons need more room. Which make way is decided on a world-fixed grid, in half-zoom steps of the
        // target zoom, so dragging or zoom animation never makes icons blink.
        double step = floor(targetZoom * 2) / 2;
        double cellWorld = iconSize(step) * (step < -1 ? 1.5 : step < 1 ? 1.15 : 0.9) / pow(2, step);
        occupied.clear();
        Filter filter = new Filter();
        Predicate<Poi> covered = badgeCover != null ? badgeCover.covers(projection) : poi -> false;
        var frame = new ArrayList<Drawn>();
        var last = new ArrayList<Poi>();
        Composite normal = g.getComposite();
        int w = getWidth();
        int h = getHeight();
        double scale = scale();
        // Farther out of view an icon shares no cell with one in it: skipped before anything else.
        double reach = size + cellWorld * scale;
        for (var poi : pois)
        {
            if (hidden.contains(poi) && poi != selected || !poi.isOn(map))
            {
                continue;
            }
            var at = shownCached(poi);
            double x = w / 2.0 + (at.getX() + 0.5 - centerX) * scale;
            double y = h / 2.0 - (at.getY() + 0.5 - centerY) * scale;
            if (x < -reach || y < -reach || x > w + reach || y > h + reach)
            {
                continue;
            }
            boolean special = poi == selected || poi == hovered || inGroup(poi);
            if (!special && (!visible(poi, filter) || at.getPlane() != plane && !showsOtherFloor(poi, at)))
            {
                continue;
            }
            // Every icon claims its cell, even hovered or just out of view, so no neighbour pops up in its place.
            boolean free = occupied.add(cellKey(at.getX() + 0.5, at.getY() + 0.5, cellWorld));
            // Where the game's icon is drawn, that acts as this one: no second icon on top, also when selected.
            if (x < -size || y < -size || x > w + size || y > h + size || covered.test(poi))
            {
                continue;
            }
            if (special)
            {
                last.add(poi);
            }
            else if (free)
            {
                g.setComposite(at.getPlane() == plane ? normal : AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));
                PoiIcons.paintCached(g, poi.type, x, y, size, false);
                frame.add(new Drawn(poi, x, y, size / 2 + 2));
            }
        }
        g.setComposite(normal);
        for (var poi : last)
        {
            var at = shownCached(poi);
            double x = screenX(at.getX() + 0.5);
            double y = screenY(at.getY() + 0.5);
            double s = poi == selected ? size * 1.25 : size * 1.1;
            PoiIcons.paintCached(g, poi.type, x, y, s, poi == selected || inGroup(poi));
            frame.add(new Drawn(poi, x, y, s / 2 + 2));
        }
        drawn = frame;
    }

    final LongSet occupied = new LongSet();
    final Map<Poi, WorldPoint> shownCache = new IdentityHashMap<>();
    BaseMap shownCacheMap;

    WorldPoint shownCached(Poi poi)
    {
        if (shownCacheMap != map)
        {
            shownCache.clear();
            shownCacheMap = map;
        }
        var at = shownCache.get(poi);
        if (at == null)
        {
            at = shown(poi.drawn());
            shownCache.put(poi, at);
        }
        return at;
    }

    /** A set of longs without boxing (open addressing), emptied every frame. */
    static final class LongSet
    {
        long[] keys = new long[256];
        boolean[] used = new boolean[256];
        int size;

        /** False when it was there already. */
        boolean add(long key)
        {
            if (size * 2 >= keys.length)
            {
                grow();
            }
            int mask = keys.length - 1;
            int i = (int) (mix(key) & mask);
            while (used[i])
            {
                if (keys[i] == key)
                {
                    return false;
                }
                i = (i + 1) & mask;
            }
            used[i] = true;
            keys[i] = key;
            size++;
            return true;
        }

        void clear()
        {
            if (size > 0)
            {
                Arrays.fill(used, false);
                size = 0;
            }
        }

        void grow()
        {
            var oldKeys = keys;
            var oldUsed = used;
            keys = new long[oldKeys.length * 2];
            used = new boolean[oldKeys.length * 2];
            size = 0;
            for (int i = 0; i < oldKeys.length; i++)
            {
                if (oldUsed[i])
                {
                    add(oldKeys[i]);
                }
            }
        }

        static long mix(long key)
        {
            long h = key * 0x9E3779B97F4A7C15L;
            return h ^ (h >>> 29);
        }
    }

    static long cellKey(double worldX, double worldY, double cellWorld)
    {
        return ((long) (int) floor(worldX / cellWorld) << 32) | ((int) floor(worldY / cellWorld) & 0xffffffffL);
    }

    double iconSize(double z)
    {
        return config.iconSize() * iconScale(z);
    }

    /** Icon scale at a zoom, smooth so icons never jump: smaller far out, full from zoom 0, growing past the finest tiles. */
    static double iconScale(double z)
    {
        return z <= -3 ? 0.75 : z < 0 ? 0.75 + (z + 3) / 3 * 0.25 : z > 3 ? min(3, pow(2, z - 3)) : 1.0;
    }

    /** A label of the game's map on the map in view, where that map draws it. */
    @RequiredArgsConstructor
    static final class DrawnLabel
    {
        final MapData.Place place;
        final WorldPoint at;
        final int pass;
        final Color color;
        /** In its pass's font, measured when first shown. */
        int width = -1;
    }

    List<DrawnLabel> labelsOnMap = Collections.emptyList();
    BaseMap labelsOnMapFor;
    List<MapData.Place> labelsOnMapFrom;

    /** The labels on the map in view, found once per map and label list. */
    List<DrawnLabel> labelsOnMap()
    {
        if (labelsOnMapFor != map || labelsOnMapFrom != labels)
        {
            labelsOnMap = labelsOn(maps, map, labels);
            labelsOnMapFor = map;
            labelsOnMapFrom = labels;
        }
        return labelsOnMap;
    }

    /**
     * The game's labels a map shows, where it draws them: a dungeon map those of its own part of the game (the Ourania
     * Altar's "Altar"), the surface and the map of everything all within their bounds.
     */
    static List<DrawnLabel> labelsOn(BaseMaps maps, BaseMap map, List<MapData.Place> labels)
    {
        var found = new ArrayList<DrawnLabel>();
        if (map == null)
        {
            return found;
        }
        for (var place : labels)
        {
            var at = place.point;
            boolean shown = map.id == FULL || map.id == SURFACE ? map.contains(at.getX(), at.getY())
                : WorldMapMoves.toDrawn(map.id, at) != null || maps != null && maps.drawsGame(map, at);
            if (shown)
            {
                found.add(new DrawnLabel(place, shownOn(map, at), labelPass(place.kind), labelColor(place.kind)));
            }
        }
        return found;
    }

    void paintPlaceNames(Graphics2D g)
    {
        var taken = new ArrayList<Rectangle2D>();
        int w = getWidth();
        int h = getHeight();
        double scale = scale();
        // As the game's map: kingdoms and oceans far out, seas between, towns close in, the small places closer still.
        for (int pass = 0; pass < 4; pass++)
        {
            double z = targetZoom;
            if (!(pass == 0 ? z < -0.5 : pass == 1 ? z >= -2 && z < 2.5 : pass == 2 ? z >= -1.2 && z < 3.5
                : z >= PLACE_ZOOM))
            {
                continue;
            }
            Font font = pass == 0 ? REGION_FONT : pass == 3 ? SMALL_PLACE_FONT : LABEL_FONT;
            g.setFont(font);
            var metrics = g.getFontMetrics();
            places:
            for (var label : labelsOnMap())
            {
                if (label.pass != pass)
                {
                    continue;
                }
                if (label.width < 0)
                {
                    label.width = metrics.stringWidth(label.place.name);
                }
                double x = w / 2.0 + (label.at.getX() + 0.5 - centerX) * scale;
                double top = h / 2.0 - (label.at.getY() + 0.5 - centerY) * scale - metrics.getAscent();
                // Most are off screen when zoomed in: no box for them.
                if (top > h || top + metrics.getHeight() < 0 || abs(x - w / 2.0) > (w + label.width) / 2.0 + 3)
                {
                    continue;
                }
                var box = new Rectangle2D.Double(x - label.width / 2.0 - 3, top, label.width + 6, metrics.getHeight());
                for (var other : taken)
                {
                    if (other.intersects(box))
                    {
                        continue places;
                    }
                }
                taken.add(box);
                text.draw(g, label.place.name, font, (float) (x - label.width / 2.0), (float) (top + metrics.getAscent()),
                    label.color);
            }
        }
    }

    static Color labelColor(String kind)
    {
        return "region".equals(kind) ? REGION_COLOR : "ocean".equals(kind) ? OCEAN_COLOR : "sea".equals(kind) ? SEA_COLOR
            : "floor".equals(kind) ? FLOOR_COLOR : Color.WHITE;
    }

    /** 0 kingdoms and oceans, 1 seas, 2 towns and floors ("Upper level"), 3 small places ("Altar"); -1 a kind not drawn. */
    static int labelPass(String kind)
    {
        return "region".equals(kind) || "ocean".equals(kind) ? 0 : "sea".equals(kind) ? 1
            : "settlement".equals(kind) || "floor".equals(kind) ? 2 : "place".equals(kind) ? 3 : -1;
    }

    /** Wilderness level lines every five levels, with the level 20 and 30 teleport limits; level n starts 8(n-1) tiles north. */
    void paintWilderness(Graphics2D g)
    {
        if (zoom < -1.5 || maps == null)
        {
            return;
        }
        double left = screenX(2944);
        double right = screenX(3392);
        g.setFont(SMALL_FONT);
        for (int start : WILDERNESS_STARTS)
        {
            if (plane != 0 || !onThisMap(maps.find(3100, start + 100)))
            {
                continue;
            }
            for (int level = 1; level <= 56; level++)
            {
                boolean limit = level == 21 || level == 31;
                if (level != 1 && level % 5 != 0 && !limit)
                {
                    continue;
                }
                double y = screenY(start + (level - 1) * 8);
                if (y < -2 || y > getHeight() + 2)
                {
                    continue;
                }
                boolean strong = level == 1 || limit;
                g.setStroke(strong ? WILDERNESS_LINE : WILDERNESS_DASHED);
                g.setColor(strong ? WILDERNESS_STRONG : WILDERNESS_FAINT);
                line(g, left, y, right, y);
                if (zoom >= -0.5 || strong)
                {
                    text.draw(g, "Level " + level + (limit ? ": no teleports above " + (level - 1) : ""), SMALL_FONT, (float) max(4, left + 4), (float) y - 3, WILDERNESS_TEXT);
                }
            }
        }
    }

    void paintPlayer(Graphics2D g)
    {
        if (!onThisMap(mapOf(player)))
        {
            return;
        }
        var at = shown(player);
        double x = screenX(at.getX() + 0.5);
        double y = screenY(at.getY() + 0.5);
        if (!(x < -4 || y < -4 || x > getWidth() + 4 || y > getHeight() + 4))
        {
            PlayerMarker.paint(g, text, x, y, zoom, at.getPlane() != plane, home);
            return;
        }
        // Out of view: a pointer at the edge.
        double cx = getWidth() / 2.0;
        double cy = getHeight() / 2.0;
        double dx = x - cx;
        double dy = y - cy;
        double t = min(dx == 0 ? Double.MAX_VALUE : (cx - 18) / abs(dx),
            dy == 0 ? Double.MAX_VALUE : (cy - 18) / abs(dy));
        PlayerMarker.pointer(g, text, cx + dx * t, cy + dy * t, atan2(dy, dx));
    }

    void paintLabel(Graphics2D g, Poi poi)
    {
        if (!poi.isOn(map))
        {
            return;
        }
        var at = shown(poi.drawn());
        var anchor = fromLayers(layer -> layer.labelAnchor(poi, projection));
        double x = anchor != null ? anchor.getX() : screenX(at.getX() + 0.5);
        double y = anchor != null ? anchor.getY() : screenY(at.getY() + 0.5) + iconSize(zoom) * 0.62 + 4;
        g.setFont(LABEL_FONT);
        var metrics = g.getFontMetrics();
        int width = metrics.stringWidth(poi.name);
        double left = max(2, min(getWidth() - width - 12, x - width / 2.0 - 5));
        var box = new RoundRectangle2D.Double(left, y, width + 10, metrics.getHeight() + 4, 8, 8);
        g.setColor(LABEL_BACKGROUND);
        g.fill(box);
        g.setColor(poi.type.color);
        g.setStroke(THIN);
        g.draw(box);
        g.setColor(Color.WHITE);
        g.drawString(poi.name, (float) (left + 5), (float) (y + 2 + metrics.getAscent()));
    }

    /** The icon a click here would take (tests use it as a click). */
    Poi iconAt(Point p)
    {
        var frame = drawn;
        for (int i = frame.size() - 1; i >= 0; i--)
        {
            var d = frame.get(i);
            if (hypot(d.x - p.x, d.y - p.y) <= d.radius)
            {
                if (mapBelow(d.poi) == null)
                {
                    // One of ours on the game's map link: the click goes where the link does.
                    for (var layer : hitLayers)
                    {
                        var under = layer.hit(p, projection);
                        if (under != null && mapBelow(under) != null)
                        {
                            return under;
                        }
                    }
                }
                return d.poi;
            }
        }
        return fromLayers(layer -> layer.hit(p, projection));
    }

    void setChrome(Chrome chrome)
    {
        this.chrome = chrome;
        repaint();
    }

    /** Painted after the other controls, so those win a click where they overlap. */
    void paintWidgets(Graphics2D g, List<Control> painted, int bottom)
    {
        var clicks = new Clicks()
        {
            public void add(RoundRectangle2D shape, Runnable action, String tooltip)
            {
                painted.add(new Control(shape, action, tooltip));
            }

            public boolean hovered(RoundRectangle2D shape)
            {
                return isHovered(shape);
            }
        };
        for (Widget widget : widgets)
        {
            widget.paint(g, getWidth(), getHeight(), bottom, clicks);
        }
    }

    Control control(Point p)
    {
        return controls.stream().filter(control -> control.shape.contains(p)).findFirst().orElse(null);
    }

    void paintControls(Graphics2D g)
    {
        var painted = new ArrayList<Control>();
        paintVersion(g);
        if (chrome == null)
        {
            backOffset = 0;
            paintBack(g, painted);
            paintWidgets(g, painted, getHeight() - MARGIN - 18);
            controls = painted;
            return;
        }
        int w = getWidth();
        g.setFont(CONTROL_FONT);
        var metrics = g.getFontMetrics();
        String mapName = (map == null ? "Map" : map.name) + "  ▾";
        int mapWidth = metrics.stringWidth(mapName) + 28;
        text(g, mapName, control(g, painted, MARGIN, MARGIN, mapWidth,
            () -> chrome.pickMap(new Point(MARGIN, MARGIN + CONTROL + 4)), "Choose a map"), metrics);
        backOffset = mapWidth + 6;
        paintBack(g, painted);
        g.setFont(CONTROL_FONT);
        var window = windowControls;
        if (window != null)
        {
            Control close = control(g, painted, w - MARGIN - CONTROL, MARGIN, CONTROL, window::close, "Close (Esc)");
            Control max = control(g, painted, w - MARGIN - 2 * CONTROL - 4, MARGIN, CONTROL, window::toggleMaximised,
                window.isMaximised() ? "Make smaller" : "Fill the game view");
            g.setColor(Color.WHITE);
            g.setStroke(ROUND_LINE);
            double cx = close.shape.getCenterX();
            double cy = close.shape.getCenterY();
            line(g, cx - 6, cy - 6, cx + 6, cy + 6);
            line(g, cx + 6, cy - 6, cx - 6, cy + 6);
            double mx = max.shape.getCenterX();
            double my = max.shape.getCenterY();
            g.setStroke(ICON_LINE);
            if (window.isMaximised())
            {
                g.draw(new Rectangle2D.Double(mx - 6, my - 3, 9, 9));
                line(g, mx - 3, my - 6, mx + 6, my - 6);
                line(g, mx + 6, my - 6, mx + 6, my + 3);
            }
            else
            {
                g.draw(new Rectangle2D.Double(mx - 6, my - 6, 12, 12));
            }
        }
        int right = w - MARGIN - CONTROL;
        int bottom = getHeight() - MARGIN - 18 - CONTROL;
        Control out = control(g, painted, right, bottom, CONTROL, () -> zoomBy(-1), "Zoom out");
        Control in = control(g, painted, right, bottom - CONTROL - 2, CONTROL, () -> zoomBy(1), "Zoom in");
        Control follow = control(g, painted, right, bottom - 2 * CONTROL - 12, CONTROL,
            () -> {
                if (following)
                {
                    setFollowing(false);
                }
                else
                {
                    centerOnPlayer();
                }
            }, following ? "Stop following" : "Follow my character");
        g.setColor(Color.WHITE);
        g.setStroke(ROUND_LINE);
        double x = in.shape.getCenterX();
        double y = in.shape.getCenterY();
        line(g, x - 7, y, x + 7, y);
        line(g, x, y - 7, x, y + 7);
        y = out.shape.getCenterY();
        line(g, x - 7, y, x + 7, y);
        y = follow.shape.getCenterY();
        g.setColor(following ? PlayerMarker.YELLOW : Color.WHITE);
        g.setStroke(ICON_LINE);
        g.draw(PlayerMarker.circle(x, y, 7));
        for (int s = -1; s <= 1; s += 2)
        {
            line(g, x, y + s * 7, x, y + s * 11);
            line(g, x + s * 7, y, x + s * 11, y);
        }
        g.fill(PlayerMarker.circle(x, y, 2.5));
        Control up = control(g, painted, MARGIN, bottom, CONTROL, () -> choosePlane(plane + 1), "Floor up");
        String floor = "Floor " + plane;
        int floorWidth = metrics.stringWidth(floor) + 24;
        Control label = new Control(new RoundRectangle2D.Double(MARGIN + CONTROL + 2, bottom, floorWidth, CONTROL, 10, 10),
            () -> { }, "Floor");
        box(g, label.shape, CONTROL_FILL, CONTROL_EDGE);
        Control down = control(g, painted, MARGIN + CONTROL + 4 + floorWidth, bottom, CONTROL,
            () -> choosePlane(plane - 1), "Floor down");
        text(g, floor, label, metrics);
        if (plane > 0)
        {
            // Back to the ground in one click from any floor above it.
            String ground = "To floor 0";
            text(g, ground, control(g, painted, MARGIN + 2 * CONTROL + 12 + floorWidth, bottom,
                metrics.stringWidth(ground) + 24, () -> choosePlane(0), "Back to the ground floor"), metrics);
        }
        g.setColor(plane < 3 ? Color.WHITE : Color.GRAY);
        g.fill(triangle(up.shape.getCenterX(), up.shape.getCenterY(), true));
        g.setColor(plane > 0 ? Color.WHITE : Color.GRAY);
        g.fill(triangle(down.shape.getCenterX(), down.shape.getCenterY(), false));
        paintWidgets(g, painted, widgetBottom(getHeight()));
        controls = painted;
    }

    void setMapUnavailable(boolean unavailable)
    {
        mapUnavailable = unavailable;
        repaint();
    }

    void paintNoMap(Graphics2D g)
    {
        var painted = new ArrayList<Control>();
        g.setRenderingHint(KEY_ANTIALIASING, VALUE_ANTIALIAS_ON);
        g.setRenderingHint(KEY_TEXT_ANTIALIASING, VALUE_TEXT_ANTIALIAS_ON);
        paintVersion(g);
        if (mapUnavailable)
        {
            String notice = "The map could not be loaded";
            g.setFont(LABEL_FONT);
            g.setColor(NOTICE_TEXT);
            int cy = getHeight() / 2;
            g.drawString(notice, (getWidth() - g.getFontMetrics().stringWidth(notice)) / 2, cy - 14);
            String check = "Check for a new map";
            g.setFont(CONTROL_FONT);
            var metrics = g.getFontMetrics();
            int width = metrics.stringWidth(check) + 28;
            text(g, check, control(g, painted, (getWidth() - width) / 2.0, cy, width, () -> {
                mapUnavailable = false;
                repaint();
                checkForMap.run();
            }, "Ask the map server for its current map"), metrics);
        }
        controls = painted;
    }

    void paintVersion(Graphics2D g)
    {
        if (VERSION == null || !config.showVersion())
        {
            return;
        }
        g.setFont(SMALL_FONT);
        float x = getWidth() - MARGIN - g.getFontMetrics().stringWidth(VERSION);
        text.draw(g, VERSION, SMALL_FONT, x, getHeight() - 6, VERSION_TEXT);
    }

    /** A short message on the map ("Added to My route"), or null. */
    String toast;
    long toastUntil;
    final Timer toastTimer = new Timer(3000, e -> repaint());

    void showToast(String text)
    {
        toast = text;
        toastUntil = System.currentTimeMillis() + 2800;
        toastTimer.setRepeats(false);
        toastTimer.restart();
        repaint();
    }

    void paintToast(Graphics2D g)
    {
        String text = toast;
        if (text == null || System.currentTimeMillis() > toastUntil)
        {
            return;
        }
        g.setFont(CONTROL_FONT);
        var metrics = g.getFontMetrics();
        int width = metrics.stringWidth(text) + 24;
        double x = max(2, (getWidth() - width) / 2.0);
        double y = getHeight() - MARGIN - 18 - CONTROL - 40;
        var box = new RoundRectangle2D.Double(x, y, width, 26, 10, 10);
        g.setColor(new Color(20, 20, 24, 225));
        g.fill(box);
        g.setColor(CONTROL_EDGE);
        g.draw(box);
        g.setColor(Color.WHITE);
        g.drawString(text, (float) (x + 12), (float) (y + 13 + (metrics.getAscent() - metrics.getDescent()) / 2.0));
    }

    int backOffset;

    void paintBack(Graphics2D g, List<Control> painted)
    {
        paintToast(g);
        g.setFont(CONTROL_FONT);
        var metrics = g.getFontMetrics();
        double x = MARGIN + backOffset;
        // Off the world map: a button to it first, then Back (unless Back goes there).
        var to = backMap();
        var world = maps == null || map == null || map.id == SURFACE || map.id == FULL ? null
            : maps.byId(SURFACE);
        if (world != null && world != to)
        {
            int width = metrics.stringWidth("World map") + 24;
            text(g, "World map", control(g, painted, x, MARGIN, width, () -> toWorld(world), "Open the world map"), metrics);
            x += width + 6;
        }
        if (to == null)
        {
            return;
        }
        double cy = control(g, painted, x, MARGIN, metrics.stringWidth(to.name) + 40, this::goBack, "Back to " + to.name
            + " (or the mouse's back button)").shape.getCenterY();
        double ax = x + 15;
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(2f, CAP_ROUND, JOIN_ROUND));
        line(g, ax - 5, cy, ax + 5, cy);
        line(g, ax - 5, cy, ax - 1, cy - 4);
        line(g, ax - 5, cy, ax - 1, cy + 4);
        g.drawString(to.name, (float) (x + 27), middle(cy, metrics));
    }

    /** A painted button of the control height. */
    Control control(Graphics2D g, List<Control> into, double x, double y, double width, Runnable action, String tip)
    {
        Control control = new Control(new RoundRectangle2D.Double(x, y, width, CONTROL, 10, 10), action, tip);
        into.add(control);
        box(g, control.shape, isHovered(control.shape) ? CONTROL_HOVER : CONTROL_FILL, CONTROL_EDGE);
        return control;
    }

    boolean isHovered(RoundRectangle2D shape)
    {
        return hoveredControl != null && hoveredControl.shape.equals(shape);
    }

    /** A rounded box with a soft shadow, as the map's controls and widgets are drawn. */
    static void box(Graphics2D g, RoundRectangle2D shape, Color fill, Color edge)
    {
        g.setColor(BOX_SHADOW);
        g.fill(new RoundRectangle2D.Double(shape.getX() + 1, shape.getY() + 2, shape.getWidth(), shape.getHeight(), 10, 10));
        g.setColor(fill);
        g.fill(shape);
        g.setColor(edge);
        g.setStroke(THIN);
        g.draw(shape);
    }

    static final Color BOX_SHADOW = new Color(0, 0, 0, 70);

    static void text(Graphics2D g, String text, Control control, FontMetrics metrics)
    {
        g.setColor(Color.WHITE);
        g.drawString(text, (float) (control.shape.getCenterX() - metrics.stringWidth(text) / 2.0),
            middle(control.shape.getCenterY(), metrics));
    }

    static float middle(double cy, FontMetrics metrics)
    {
        return (float) (cy + metrics.getAscent() / 2.0 - 2);
    }

    static void line(Graphics2D g, double x1, double y1, double x2, double y2)
    {
        g.draw(new Line2D.Double(x1, y1, x2, y2));
    }

    static Path2D triangle(double x, double y, boolean up)
    {
        double d = up ? -1 : 1;
        var path = new Path2D.Double();
        path.moveTo(x, y + d * 6);
        path.lineTo(x + 7, y - d * 4);
        path.lineTo(x - 7, y - d * 4);
        path.closePath();
        return path;
    }

    /** Repaints only when what is highlighted changes: a full repaint per mouse move is expensive. */
    void updateHover(Point p)
    {
        Control overControl = control(p);
        // Controls are made anew every paint: the same button has the same shape.
        boolean full = overControl == null ? hoveredControl != null : !isHovered(overControl.shape);
        if (overControl != hoveredControl)
        {
            hoveredControl = overControl;
            setToolTipText(overControl == null ? null : overControl.tooltip);
        }
        var hit = overControl == null ? iconAt(p) : null;
        if (hit != hovered)
        {
            hovered = hit;
            full = true;
        }
        if (overControl != null || !dragging)
        {
            setCursor(Cursor.getPredefinedCursor(overControl != null || hit != null ? Cursor.HAND_CURSOR
                : Cursor.DEFAULT_CURSOR));
        }
        if (full)
        {
            repaint();
        }
    }

    static String goInText(Poi.Link inside)
    {
        return inside.map.id == SURFACE ? "Go out" : "Go in";
    }

    @RequiredArgsConstructor
    static final class Back
    {
        final BaseMap map;
        final double x;
        final double y;
        final double zoom;
        final int plane;
    }

    static final int MAX_BACK = 12;
    final ArrayDeque<Back> back = new ArrayDeque<>();

    /** Before the player opens another map: Back returns here. Automatic changes call {@link #showMap} alone. */
    void rememberForBack(BaseMap next)
    {
        if (map == null || next == map)
        {
            return;
        }
        back.push(new Back(map, targetX, targetY, targetZoom, plane));
        while (back.size() > MAX_BACK)
        {
            back.removeLast();
        }
    }

    BaseMap backMap()
    {
        Back top = back.peek();
        return top == null || top.map == map ? null : top.map;
    }

    void toWorld(BaseMap world)
    {
        setFollowing(false);
        rememberForBack(world);
        var me = player != null && world == mapOf(player) ? shownOn(world, player) : null;
        showMap(world, me, me != null ? playerZoom() : min(targetZoom, 1));
    }

    void goBack()
    {
        Back top = back.poll();
        if (top == null)
        {
            return;
        }
        setFollowing(false);
        showMap(top.map, new WorldPoint((int) floor(top.x), (int) floor(top.y), top.plane), top.zoom);
    }

    void goIn(Poi.Link inside)
    {
        setFollowing(false);
        rememberForBack(inside.map);
        var area = areaOf(inside.map, inside.point);
        if (area != null)
        {
            showMap(inside.map, new WorldPoint((area[0] + area[2]) / 2, (area[1] + area[3]) / 2,
                shownOn(inside.map, inside.point).getPlane()), fitZoom(area));
            return;
        }
        showMap(inside.map, shownOn(inside.map, inside.point), max(targetZoom, playerZoom()));
    }

    /** Walkable area around a point as {minX, minY, maxX, maxY}; null when unknown or too large. Set by the route feature. */
    Function<WorldPoint, int[]> areaBounds = p -> null;

    int[] areaOf(BaseMap on, WorldPoint point)
    {
        if (on == null || on.id == SURFACE || on.id == FULL)
        {
            return null;
        }
        var area = areaBounds.apply(point);
        var drawn = shownOn(on, point);
        if (area == null || drawn == point)
        {
            return area;
        }
        int dx = drawn.getX() - point.getX();
        int dy = drawn.getY() - point.getY();
        return new int[]{area[0] + dx, area[1] + dy, area[2] + dx, area[3] + dy};
    }

    double fitZoom(int[] area)
    {
        double w = max(100, getWidth() - 80);
        double h = max(100, getHeight() - 80);
        double fit = min(w / (area[2] - area[0] + 8), h / (area[3] - area[1] + 8));
        return clampZoom(min(playerZoom(), log(fit) / log(2)));
    }

    /** Where an icon leads when that is another map or another dungeon floor; null otherwise. */
    static Poi.Link mapBelow(Poi poi)
    {
        if (poi == null || poi.target == null && poi.type != DUNGEON_ENTRANCE && poi.type != MAP_EXIT
            && poi.type != MAP_LINK)
        {
            return null;
        }
        for (var link : poi.links())
        {
            boolean elsewhere = link.map != poi.map || poi.location.getY() >= UNDERGROUND_Y
                && link.point.getPlane() != poi.location.getPlane()
                // The game's map links jump within one map too (Keldagrim's tunnel and city).
                || poi.type == MAP_LINK && (link.point.getPlane() != poi.location.getPlane()
                    || max(abs(link.point.getX() - poi.location.getX()),
                        abs(link.point.getY() - poi.location.getY())) > 12);
            // Also into the map of everything: it draws the ground no other map shows.
            if (link.map != null && elsewhere && (poi.target == null || link.map == poi.target))
            {
                return link;
            }
        }
        return null;
    }

    void showContextMenu(MouseEvent e)
    {
        var world = toWorld(e.getPoint());
        var menu = new JPopupMenu();
        var clicked = iconAt(e.getPoint());
        if (clicked != null)
        {
            select(clicked);
        }
        var inside = mapBelow(clicked);
        if (inside != null)
        {
            JMenuItem open = menu.add("Open " + inside.map.name);
            open.setFont(open.getFont().deriveFont(Font.BOLD));
            open.addActionListener(a -> goIn(inside));
            menu.addSeparator();
        }
        menu.add("Center here").addActionListener(a -> {
            setFollowing(false);
            animateTo(world.getX(), world.getY(), targetZoom);
        });
        if (player != null)
        {
            menu.add("Show my location").addActionListener(a -> centerOnPlayer());
        }
        // A route or stop goes to the game's own spot, which the map may draw elsewhere (Dagannoth Kings' lair).
        var point = gamePoint(new WorldPoint((int) floor(world.getX()), (int) floor(world.getY()), plane));
        menu.add("Nearest teleports to here").addActionListener(a -> {
            if (listener != null)
            {
                listener.nearestRequested(point);
            }
        });
        for (var contributor : menuContributors)
        {
            contributor.contribute(menu, point);
        }
        menu.show(this, e.getX(), e.getY());
    }
}
