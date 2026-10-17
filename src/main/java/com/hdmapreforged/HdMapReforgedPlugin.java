package com.hdmapreforged;

import static com.hdmapreforged.HdMapReforgedConfig.*;

import com.hdmapreforged.route.*;
import com.google.gson.*;
import com.google.inject.*;
import java.awt.event.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.stream.*;
import javax.inject.*;
import javax.inject.Inject;
import javax.swing.*;
import javax.swing.Timer;
import lombok.extern.slf4j.*;
import net.runelite.api.*;
import net.runelite.api.coords.*;
import net.runelite.api.events.*;
import net.runelite.api.gameval.*;
import net.runelite.client.callback.*;
import net.runelite.client.config.*;
import net.runelite.client.eventbus.*;
import net.runelite.client.events.*;
import net.runelite.client.game.*;
import net.runelite.client.input.*;
import net.runelite.client.input.KeyListener;
import net.runelite.client.plugins.*;
import net.runelite.client.ui.*;
import net.runelite.client.util.*;
import okhttp3.*;

@Slf4j
@PluginDescriptor(
    name = "Reimagined World Map",
    internalName = "reimagined-world-map",
    description = "A zoomable world map with routes, teleports, dungeons and a search for monsters, shops and items",
    tags = {"map", "world", "worldmap", "teleport", "dungeon", "route", "sailing", "search"}
)
public class HdMapReforgedPlugin extends Plugin
{
    /** Unlocks are read at most this often while things change; quest states this often at all. */
    static final int UNLOCK_TICKS = 5;
    static final int QUEST_TICKS = 100;

    @Inject
    Client client;

    @Inject
    ClientThread clientThread;

    @Inject
    ClientToolbar clientToolbar;

    @Inject
    OkHttpClient okHttpClient;

    @Inject
    @Named("developerMode")
    boolean developerMode;

    @Inject
    Gson gson;

    @Inject
    ItemManager itemManager;

    @Inject
    HdMapReforgedConfig config;

    @Inject
    KeyManager keyManager;

    @Inject
    ConfigManager configManager;

    @Inject
    PartyMap party;

    @Inject
    RouteFeature route;

    ExecutorService io;
    /** One task at a time so results arrive in order. */
    ExecutorService dataIo;
    final AtomicInteger dataGeneration = new AtomicInteger();
    final AtomicBoolean repaintPending = new AtomicBoolean();
    Filepath home;
    TileCache tiles;
    /** The server's lists and pictures, kept once seen. */
    ServerCache serverCache;
    /** The lists our map server publishes: where NPCs stand, the shops, drops and items on the ground. */
    volatile WorldData world = WorldData.NONE;
    /** Its data version; null before the first download. */
    volatile String worldVersion;
    /** A data version whose search.json could not be used, said once in the log. */
    volatile String worldRefused;
    /** When the server was last asked for a newer version of it. */
    volatile long worldAsked;
    /** Asked again at most this often, and only when the search, a card or a route uses it. */
    static final long WORLD_ASK_MS = 60 * 60_000L;
    /** In developer mode, against a map server on this computer. */
    static final long DEVELOPER_WORLD_ASK_MS = 30_000L;
    volatile long worldAskMs = WORLD_ASK_MS;
    MapServer server;
    MapScreen screen;
    MapScreen fullScreen;
    FullMapWindow fullMap;
    MapIconLayer[] gameIcons = new MapIconLayer[0];
    GraveMarker[] graves = new GraveMarker[0];
    final HotkeyListener mapKey = new HotkeyListener(() -> config.openMapKey())
    {
        public void keyPressed(KeyEvent e)
        {
            // A key that types goes to the chat or input box taking text, not to the map.
            if (!config.openMapKey().matches(e) || !typingInGame(e))
            {
                super.keyPressed(e);
            }
        }

        public void hotkeyPressed()
        {
            toggleFullMap();
        }
    };
    final KeyListener escape = new Escape();

    /** Escape closes the map over the game while it is open. */
    final class Escape extends KeyAdapter implements KeyListener
    {
        public void keyPressed(KeyEvent e)
        {
            FullMapWindow open = fullMap;
            if (e.getKeyCode() == KeyEvent.VK_ESCAPE && open != null && open.isOpen())
            {
                e.consume();
                SwingUtilities.invokeLater(open::close);
            }
        }
    }
    MapPanel panel;
    MapWindow window;
    NavigationButton button;
    WorldPoint lastPlayer;
    boolean atHome;
    /** Null until a version's maps and places are shown. */
    volatile String currentVersion;
    /** What the requirements of the data shown read; null before it loads. */
    volatile Unlocks.Watched watched;
    /** Each skill's real level as last seen, so only a level gained marks unlocks for a new look. Client thread. */
    final int[] realLevels = new int[Skill.values().length];
    /** Before a map version's files, or the server's versions, are asked for again. */
    static final long RETRY_MS = 60_000;
    static final long MAX_RETRY_MS = 30 * RETRY_MS;
    /** Map versions that failed in a row. Data thread. */
    int failedMaps;
    /** The latest such request waiting, stopped when the plugin stops. */
    volatile Timer retry;
    /** Counts starts and stops, so late callbacks of an earlier run do nothing. */
    final AtomicInteger life = new AtomicInteger();
    volatile String wantedVersion;
    Unlocks unlocks;
    boolean unlocksDirty = true;
    int ticks;
    int lastUnlockTick = -UNLOCK_TICKS;
    int lastQuestTick = -QUEST_TICKS;

    @Provides
    HdMapReforgedConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(HdMapReforgedConfig.class);
    }

    /** When this throws, RuneLite calls shutDown, which undoes only what was made. */
    protected void startUp() throws Exception
    {
        int started = life.incrementAndGet();
        currentVersion = null;
        io = Executors.newFixedThreadPool(4, runnable -> daemon(runnable, "Reimagined World Map tiles"));
        dataIo = Executors.newSingleThreadExecutor(runnable -> daemon(runnable, "Reimagined World Map data"));
        home = getPluginDirectory();
        MapServer.use(developerMode);
        server = new MapServer(okHttpClient, gson);
        tiles = new TileCache(okHttpClient, io, home.joinSegment("map-tiles"), this::tileLoaded);
        serverCache = new ServerCache(home.joinSegment("server-cache"), io);
        configureTiles();

        ItemNames itemNames = new ItemNames(clientThread, itemManager);
        screen = new MapScreen(tiles, config, this::worldInUse, itemNames, this::togglePopOut);
        fullScreen = new MapScreen(tiles, config, this::worldInUse, itemNames, () -> fullMap.close());
        fullMap = new FullMapWindow(client, fullScreen, config.fullMapBounds(),
            bounds -> configManager.setConfiguration(GROUP, "fullMapBounds", bounds),
            key -> config.openMapKey().matches(key));
        keyManager.registerKeyListener(escape);
        keyManager.registerKeyListener(mapKey);
        panel = new MapPanel(() -> fullMap.open(), this::togglePopOut, this::showOnMap, party,
            fullScreen::placeName);
        button = NavigationButton.builder()
            .tooltip("Reimagined World Map")
            .icon(PoiIcons.navigationIcon())
            .priority(6)
            .panel(panel)
            .build();
        clientToolbar.addNavigation(button);
        party.start(screen.view, fullScreen.view);
        route.setHouseFiles(home.joinSegment("house"), io);
        route.itemNames(itemNames);
        route.start(screen, fullScreen);
        route.setSidebar(panel::showRoute, this::showOnMap);
        panel.setTours(route.sidebarTours(panel::refreshTours));
        gameIcons = new MapIconLayer[]{gameIconLayer(screen), gameIconLayer(fullScreen)};
        graves = new GraveMarker[]{new GraveMarker(screen.view), new GraveMarker(fullScreen.view)};
        clientThread.invoke(this::checkGrave);
        NpcPictures pictures = new NpcPictures((id, done) -> {
            String version = tiles.version;
            serverCache.bytes("npcs", version, id + ".png", fetch -> server.npcPictureBytes(version, id, fetch),
                bytes -> done.accept(MapServer.decode(bytes)));
        });
        for (MapScreen on : new MapScreen[]{screen, fullScreen})
        {
            on.view.linesChoice = value -> configManager.setConfiguration(GROUP, "linesOff", value);
            on.view.checkForMap = () -> runData(() -> resolveVersions(true, false));
            on.setNpcPictures(pictures);
        }

        worldAsked = System.currentTimeMillis();
        worldAskMs = developerMode ? DEVELOPER_WORLD_ASK_MS : WORLD_ASK_MS;
        // The screens start empty until a map version's files are had and read on the data thread.
        runData(() -> resolveVersions(false, true));
        whenGameLoaded(started, () -> GameIconSprites.load(client), PoiIcons::clearCache);
    }

    void whenGameLoaded(int started, BooleanSupplier load, Runnable after)
    {
        clientThread.invokeLater(() -> {
            if (started != life.get())
            {
                return true;
            }
            if (client.getGameState().getState() < GameState.LOGIN_SCREEN.getState() || !load.getAsBoolean())
            {
                return false;
            }
            after.run();
            refreshLater();
            return true;
        });
    }

    MapIconLayer gameIconLayer(MapScreen on)
    {
        MapIconLayer layer = new MapIconLayer(on.view, config::showGameIcons, config::iconSize);
        on.view.addOverlay(layer);
        on.view.hitLayers.add(layer);
        on.view.setBadgeCover(layer::covers);
        return layer;
    }

    static Thread daemon(Runnable runnable, String name)
    {
        Thread thread = new Thread(runnable, name);
        thread.setDaemon(true);
        return thread;
    }

    /** Also safe after a startUp that failed half-way: only what was made is undone. */
    protected void shutDown()
    {
        life.incrementAndGet();
        wantedVersion = null;
        Timer waiting = retry;
        retry = null;
        if (waiting != null)
        {
            waiting.stop();
        }
        GameIconSprites.clear();
        PoiIcons.clearCache();
        party.stop();
        route.stop();
        if (button != null)
        {
            clientToolbar.removeNavigation(button);
        }
        dock();
        keyManager.unregisterKeyListener(escape);
        keyManager.unregisterKeyListener(mapKey);
        if (fullMap != null)
        {
            fullMap.dispose();
        }
        dataGeneration.incrementAndGet();
        if (screen != null)
        {
            screen.stop();
        }
        if (fullScreen != null)
        {
            fullScreen.stop();
        }
        if (io != null)
        {
            io.shutdownNow();
        }
        if (dataIo != null)
        {
            dataIo.shutdownNow();
        }
        if (tiles != null)
        {
            tiles.clear();
        }
        lastPlayer = null;
        unlocks = null;
        unlocksDirty = true;
        watched = null;
        Arrays.fill(realLevels, 0);
        // Nothing of the data is kept while the plugin is off: the files are on disk for the next start.
        world = WorldData.NONE;
        worldVersion = null;
        currentVersion = null;
        screen = null;
        gameIcons = new MapIconLayer[0];
        graves = new GraveMarker[0];
        fullScreen = null;
        fullMap = null;
        if (panel != null)
        {
            panel.stop();
        }
        panel = null;
        button = null;
    }

    void screens(Consumer<MapScreen> change)
    {
        SwingUtilities.invokeLater(() -> {
            if (screen != null)
            {
                change.accept(screen);
                change.accept(fullScreen);
            }
        });
    }

    @Subscribe
    public void onGameTick(GameTick tick)
    {
        ticks++;
        Player player = client.getLocalPlayer();
        if (player == null)
        {
            return;
        }
        WorldPoint location = playerLocation(player);
        route.tick(location);
        WorldPoint home = route.home();
        // In a house the party sees its portal (the house's own place is a copy of the game's rooms).
        WorldPoint door = home == null || !HousePlan.contains(home.getX(), home.getY()) ? home : route.outside();
        party.tick(home != null ? door : location, home != null, ticks);
        WorldPoint shown = home != null ? home : location;
        // On the house's plan, or in a box at its portal while that plan is not known (a friend's house).
        boolean boxed = home != null && !HousePlan.contains(home.getX(), home.getY());
        WorldPoint outside = route.outside();
        if (shown != null && (!shown.equals(lastPlayer) || boxed != atHome))
        {
            lastPlayer = shown;
            atHome = boxed;
            screens(s -> {
                s.view.setHome(boxed);
                s.view.outside = outside;
                s.view.setPlayer(shown);
            });
        }
        updateUnlocks();
    }

    /** As the game's maps show it: in instances the copied real location, on a boat the boat's main-world place. */
    WorldPoint playerLocation(Player player)
    {
        LocalPoint local = player.getLocalLocation();
        WorldView view = player.getWorldView();
        if (local != null && view != null && !view.isTopLevel())
        {
            WorldEntity boat = client.getTopLevelWorldView().worldEntities().byIndex(view.getId());
            local = boat != null ? boat.transformToMainWorld(local) : null;
        }
        if (local == null)
        {
            return null;
        }
        WorldPoint at = WorldPoint.fromLocalInstance(client, local);
        // An instance copying a place on the map (the Watchtower's top floor): that place's tile.
        int[] place = InstanceCopies.place(at.getX(), at.getY(), at.getPlane());
        return place == null ? at : new WorldPoint(place[0], place[1], place[2]);
    }

    void updateUnlocks()
    {
        boolean due = unlocksDirty && ticks - lastUnlockTick >= UNLOCK_TICKS || ticks - lastUnlockTick >= QUEST_TICKS;
        Unlocks.Watched reads = watched;
        if (!due || reads == null || client.getGameState() != GameState.LOGGED_IN)
        {
            return;
        }
        boolean quests = ticks - lastQuestTick >= QUEST_TICKS;
        Unlocks previous = unlocks;
        unlocks = Unlocks.capture(client, reads, unlocks, quests);
        unlocksDirty = false;
        lastUnlockTick = ticks;
        if (quests)
        {
            lastQuestTick = ticks;
        }
        if (unlocks.sameAs(previous))
        {
            // Varbits change all the time; rebuilding the detail card for nothing made the map stutter.
            unlocks = previous;
            return;
        }
        Unlocks current = unlocks;
        screens(s -> s.setUnlocks(current));
    }

    /** A RuneLite-only orb entry; the game's "World Map" stays in the menu. Every entry of every frame: cheap tests first. */
    @Subscribe
    public void onMenuEntryAdded(MenuEntryAdded event)
    {
        int widget = event.getActionParam1();
        if (!isOrb(widget) || !config.orbOpensMap() || !isWorldMapOption(event.getOption()))
        {
            return;
        }
        ourEntryAdded = true;
        // The orb can list several map options; one entry of ours is enough.
        if (ownEntryIndex(client.getMenu().getMenuEntries()) >= 0)
        {
            return;
        }
        client.getMenu().createMenuEntry(-1)
            .setOption(MENU_OPTION)
            .setTarget("")
            .setType(MenuAction.RUNELITE)
            .setParam1(widget)
            .onClick(entry -> toggleFullMap());
    }

    /** Our orb entry is in this frame's menu: the sort that follows has something to do. Client thread. */
    boolean ourEntryAdded;

    /**
     * Whether the game takes typed keys as text now: an input box is open (an amount, a bank search, the world map's
     * search) or the chat takes letters (with Key Remapping's "Press Enter to Chat" shown it takes none), also while the
     * chat is closed. The game's own chat key script ignores keys when a dialogue hides the chat's lines. Set on the
     * client thread each client tick; the key thread only reads it.
     */
    volatile boolean gameTakesText;

    @Subscribe
    public void onClientTick(ClientTick tick)
    {
        boolean text = false;
        if (client.getGameState() == GameState.LOGGED_IN)
        {
            var chat = client.getWidget(InterfaceID.Chatbox.UNIVERSE);
            var input = client.getWidget(InterfaceID.Chatbox.INPUT);
            text = client.getFocusedInputFieldWidget() != null || client.getVarcIntValue(VarClientID.MESLAYERMODE) != 0
                || client.getVarcIntValue(VarClientID.WORLDMAP_SEARCHING) == 1
                || chat != null && chat.getOnKeyListener() != null && input != null && shown(InterfaceID.Chatbox.CHATDISPLAY)
                && shown(InterfaceID.Chatbox.MES_LAYER_HIDE) && client.getWidget(InterfaceID.Reportabuse.UNIVERSE) == null
                && !String.valueOf(input.getText()).contains("Press Enter to Chat");
        }
        gameTakesText = text;
    }

    /** Not hidden by itself; a closed chat hides only its parents. */
    boolean shown(int component)
    {
        var w = client.getWidget(component);
        return w != null && !w.isSelfHidden();
    }

    /** Whether the game takes this key as text: it types a character (no Ctrl, Alt or Meta) while it takes text. */
    boolean typingInGame(KeyEvent e)
    {
        return e.getKeyChar() != KeyEvent.CHAR_UNDEFINED && !e.isControlDown() && !e.isAltDown() && !e.isMetaDown()
            && gameTakesText;
    }

    void toggleFullMap()
    {
        SwingUtilities.invokeLater(() -> {
            if (fullMap != null)
            {
                fullMap.toggle();
            }
        });
    }

    /** Keeps "Reimagined Map" as the orb's left-click option, as later entries would take the top; only reorders. */
    @Subscribe
    public void onPostMenuSort(PostMenuSort event)
    {
        if (!ourEntryAdded || client.isMenuOpen())
        {
            return;
        }
        ourEntryAdded = false;
        MenuEntry[] entries = client.getMenu().getMenuEntries();
        int ours = ownEntryIndex(entries);
        if (ours >= 0 && ours != entries.length - 1)
        {
            client.getMenu().setMenuEntries(moveToTop(entries, ours));
        }
    }

    static final String MENU_OPTION = "Reimagined Map";

    static boolean isOrb(int widget)
    {
        return widget == InterfaceID.Orbs.ORB_WORLDMAP || widget == InterfaceID.Orbs.WORLDMAP;
    }

    static boolean isWorldMapOption(String option)
    {
        return option != null && Text.removeTags(option).toLowerCase(Locale.ROOT).contains("world map");
    }

    static boolean isOurEntry(MenuEntry entry)
    {
        return entry.getType() == MenuAction.RUNELITE && MENU_OPTION.equals(entry.getOption()) && isOrb(entry.getParam1());
    }

    static int ownEntryIndex(MenuEntry[] entries)
    {
        for (int i = 0; i < entries.length; i++)
        {
            if (isOurEntry(entries[i]))
            {
                return i;
            }
        }
        return -1;
    }

    /** The last entry is the top of the menu, which is what a left click does. */
    static MenuEntry[] moveToTop(MenuEntry[] entries, int index)
    {
        var reordered = new ArrayList<MenuEntry>(Arrays.asList(entries));
        reordered.add(reordered.remove(index));
        return reordered.toArray(new MenuEntry[0]);
    }

    @Subscribe
    public void onStatChanged(StatChanged event)
    {
        int skill = event.getSkill().ordinal();
        if (realLevels[skill] != event.getLevel())
        {
            realLevels[skill] = event.getLevel();
            unlocksDirty = true;
        }
    }

    /** Varbits change all the time; only those (and varps) a requirement reads matter, and the teleport timers. */
    @Subscribe
    public void onVarbitChanged(VarbitChanged event)
    {
        Unlocks.Watched reads = watched;
        if (reads != null && (reads.watches(event.getVarbitId()) || reads.watchesVarp(event.getVarpId()))
            || Unlocks.timesTeleports(event.getVarpId()))
        {
            unlocksDirty = true;
        }
        if (event.getVarbitId() == VarbitID.GRAVESTONE_VISIBLE
            || event.getVarpId() == VarPlayerID.GRAVESTONE_CREATION_DEATH_COORD)
        {
            checkGrave();
        }
    }

    /** One's grave from the game: standing while GRAVESTONE_VISIBLE is not 0 (not a flag), on the tile one died on. */
    void checkGrave()
    {
        int coord = client.getVarpValue(VarPlayerID.GRAVESTONE_CREATION_DEATH_COORD);
        showGrave(client.getVarbitValue(VarbitID.GRAVESTONE_VISIBLE) != 0 && coord > 0 ? WorldPoint.fromCoord(coord)
            : null);
    }

    void showGrave(WorldPoint at)
    {
        SwingUtilities.invokeLater(() -> {
            for (GraveMarker grave : graves)
            {
                grave.set(at);
            }
        });
    }

    /** HD World Markers drops every plugin's tiles on a profile change, as on a logout: the route's are sent again. */
    @Subscribe
    public void onProfileChanged(ProfileChanged event)
    {
        route.tileMarkersCleared();
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        GameState game = event.getGameState();
        if (game == GameState.LOGGED_IN)
        {
            unlocksDirty = true;
            lastQuestTick = ticks - QUEST_TICKS;
        }
        if (game == GameState.LOGIN_SCREEN || game == GameState.HOPPING || game == GameState.CONNECTION_LOST)
        {
            route.tileMarkersCleared();
        }
        if (game == GameState.LOGIN_SCREEN)
        {
            lastPlayer = null;
            unlocks = null;
            showGrave(null);
            screens(s -> {
                s.view.setPlayer(null);
                s.setUnlocks(null);
            });
        }
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        // The house (per account) is written as it is seen: nothing for the map's tiles.
        if (!GROUP.equals(event.getGroup())
            || Set.of("fullMapBounds", RouteFeature.HOUSE_KEY, RouteFeature.PLAN_KEY, RouteFeature.BANK_KEY)
            .contains(event.getKey()))
        {
            return;
        }
        configureTiles();
        switch (event.getKey())
        {
            case "checkMapNow":
                // Every click: RuneLite's settings panel would not show the box unticking itself.
                runData(() -> resolveVersions(true, false));
                break;
            case "followPlayer":
                screens(s -> s.view.setFollowing(config.followPlayer()));
                break;
            case "linesOff":
                screens(s -> s.view.setLinesOff(config.linesOff()));
                return;
        }
        refreshLater();
    }

    void configureTiles()
    {
        tiles.configure(true, 256, config.diskCacheMb());
    }

    void tileLoaded()
    {
        if (repaintPending.compareAndSet(false, true))
        {
            SwingUtilities.invokeLater(() -> {
                repaintPending.set(false);
                refresh();
            });
        }
    }

    void refreshLater()
    {
        SwingUtilities.invokeLater(this::refresh);
    }

    void refresh()
    {
        if (screen != null)
        {
            screen.view.repaint();
            fullScreen.view.repaint();
        }
    }

    /**
     * The map server's current one (remembered); nothing is shown before a version's
     * files are had. {@code now}: ask the server even when the update check is not due; {@code world}: also take its
     * newer world facts, in the same request. Data thread.
     */
    void resolveVersions(boolean now, boolean world)
    {
        KnownVersion known = KnownVersion.read(home.joinSegment(KnownVersion.FILE));
        // Asked now: the remembered version may be the broken one.
        if (known != null && !now)
        {
            useVersion(known.version);
        }
        // Against a map server of one's own (developer mode, -Dhdmap.server): a data fix published there shows at the
        // next start, not a week later.
        boolean askMap = now || known == null || developerMode && System.getProperty("hdmap.server") != null
            || known.due(System.currentTimeMillis());
        if (!askMap && !world)
        {
            return;
        }
        int started = life.get();
        boolean mapAsked = askMap;
        server.versions(versions -> runData(() -> {
            if (world)
            {
                loadWorld(started, versions.data);
            }
            if (!mapAsked || started != life.get())
            {
                return;
            }
            if (versions.map == null)
            {
                if (wantedVersion == null)
                {
                    // The first start, and no answer: nothing to show until the server answers.
                    retryLater(started, () -> resolveVersions(false, world));
                }
                else if (currentVersion == null)
                {
                    screens(s -> s.view.setMapUnavailable(true));
                }
                return;
            }
            KnownVersion.write(home.joinSegment(KnownVersion.FILE), versions.map, System.currentTimeMillis());
            useVersion(versions.map);
        }));
    }

    /**
     * The world facts, for what shows them (the search, a card, a route): asks the server for a newer version when
     * {@link #WORLD_ASK_MS} have passed since it last did, so nothing is fetched while nothing uses them.
     */
    WorldData worldInUse()
    {
        long now = System.currentTimeMillis();
        if (now - worldAsked >= worldAskMs)
        {
            worldAsked = now;
            int started = life.get();
            server.versions(versions -> loadWorld(started, versions.data));
        }
        return world;
    }

    /**
     * The map server's search.json of {@code version} (what the search shows, worked out there), when newer than the
     * one shown; read on the data thread. Without it, the next look ({@link #worldInUse}) asks again a minute later;
     * one in another format (an older or newer map server) is not used. Either is said once in the log.
     */
    void loadWorld(int started, String version)
    {
        if (version == null || started != life.get() || version.equals(worldVersion))
        {
            return;
        }
        String file = WorldData.FILE + ".json";
        serverCache.bytes("data", version, file, fetch -> server.data(version, WorldData.FILE, fetch),
            bytes -> runData(() -> {
                WorldData read = bytes == null ? null : WorldData.parse(bytes);
                if (started != life.get())
                {
                    return;
                }
                if (read != null)
                {
                    world = read;
                    worldVersion = version;
                    return;
                }
                if (!version.equals(worldRefused))
                {
                    worldRefused = version;
                    log.warn(bytes == null ? "No {} of data version {}" : "{} of data version {} is not in format "
                        + WorldData.FORMAT, file, version);
                }
                if (bytes == null)
                {
                    worldAsked = System.currentTimeMillis() - worldAskMs + RETRY_MS;
                }
                else
                {
                    worldVersion = version;
                }
            }));
    }

    /**
     * Shows a map version once its three files are had (kept on disk once seen, else the server's): its map list with
     * its region table and its places, read here; its tiles switch with them. Until all three are usable the version
     * shown stays, and they are asked for again after {@link #RETRY_MS}. Data thread.
     */
    void useVersion(String version)
    {
        int started = life.get();
        wantedVersion = version;
        if (version.equals(currentVersion))
        {
            tiles.setVersion(version, true);
            refreshLater();
            return;
        }
        var files = new ArrayList<CompletableFuture<byte[]>>();
        for (String name : MapServer.MAP_FILES)
        {
            var file = new CompletableFuture<byte[]>();
            serverCache.bytes("maps", version, name, fetch -> server.mapFile(version, name, fetch), file::complete);
            files.add(file);
        }
        CompletableFuture.allOf(files.toArray(new CompletableFuture<?>[0])).thenRun(() -> runData(() -> {
            if (started != life.get() || !version.equals(wantedVersion) || version.equals(currentVersion))
            {
                return;
            }
            byte[] list = files.get(0).join();
            byte[] regions = files.get(1).join();
            byte[] places = files.get(2).join();
            byte[] routeData = files.get(3).join();
            BaseMaps maps = list == null ? null : BaseMaps.parse(gson, MapServer.text(list));
            MapData data = null;
            if (maps != null && maps.byId(BaseMap.SURFACE) != null && regions != null && places != null)
            {
                try
                {
                    maps.regions = new RegionTable().read(MapServer.text(regions));
                    maps.version = version;
                    data = MapData.read(gson, MapServer.text(places), maps, version);
                }
                catch (IOException | RuntimeException e)
                {
                    log.debug("Unusable files of map version {}", version, e);
                }
            }
            if (data == null)
            {
                log.debug("Map version {} incomplete; showing {}", version, currentVersion);
                if (list != null && regions != null && places != null)
                {
                    // Complete but unusable: deleted, or every retry would read the same copy from disk.
                    serverCache.forget("maps", version);
                }
                if (currentVersion == null)
                {
                    screens(s -> s.view.setMapUnavailable(true));
                }
                // The server is asked, as it may have replaced this version: at once, then 1, 2, 4... minutes apart.
                long gap = failedMaps == 0 ? 0 : Math.min(MAX_RETRY_MS, RETRY_MS << Math.min(failedMaps - 1, 5));
                failedMaps++;
                retryLater(started, gap, () -> {
                    if (version.equals(wantedVersion))
                    {
                        resolveVersions(true, false);
                    }
                });
                return;
            }
            if (routeData != null)
            {
                try
                {
                    // Before the switch: the version's gates and shortcuts are among the needs it watches.
                    RouteFiles.use(routeData);
                }
                catch (IOException | RuntimeException e)
                {
                    log.debug("Unusable route data of map version {}", version, e);
                }
            }
            switchVersion(version, maps, data, started);
            route.loadPlanner();
        }));
    }

    /** Runs {@code task} on the data thread after {@link #RETRY_MS} unless the plugin stopped meanwhile. */
    void retryLater(int started, Runnable task)
    {
        retryLater(started, RETRY_MS, task);
    }

    void retryLater(int started, long delayMs, Runnable task)
    {
        Timer timer = new Timer((int) delayMs, e -> runData(() -> {
            if (started == life.get())
            {
                task.run();
            }
        }));
        timer.setRepeats(false);
        Timer previous = retry;
        retry = timer;
        if (previous != null)
        {
            previous.stop();
        }
        timer.start();
    }

    void switchVersion(String version, BaseMaps maps, MapData data, int started)
    {
        String current = tiles.version;
        if (current != null && !current.equals(version))
        {
            // The tiles of the version in use carry over, but for those the server says changed.
            // On the data thread: the upgrade may remove many files, too long for OkHttp's shared callback threads.
            server.changes(version, changes -> runData(() -> {
                if (changes != null && changes.from.equals(current))
                {
                    tiles.upgrade(current, version, changes.changed, changes.removed);
                }
                if (started == life.get() && version.equals(wantedVersion))
                {
                    publish(version, maps, data, started);
                }
            }));
            return;
        }
        publish(version, maps, data, started);
    }

    /** A version's tiles, maps and places to the screens, the route and the requirements read. Data thread. */
    void publish(String version, BaseMaps maps, MapData data, int started)
    {
        tiles.setVersion(version, true);
        int generation = dataGeneration.incrementAndGet();
        currentVersion = version;
        failedMaps = 0;
        screens(s -> s.view.setMapUnavailable(false));
        // The places' needs and those of the bundled gates and shortcuts: a quest not watched counts as done.
        var reads = new Unlocks.Watched(Stream.concat(Poi.flatten(data.pois).stream().flatMap(poi -> Stream.concat(
            poi.members().stream().map(member -> member.needs), poi.links().stream().map(link -> link.needs))),
            RouteSource.fixedNeeds().stream()).collect(Collectors.toList()));
        // Quest start icons show their quest's state.
        data.icons.stream().filter(icon -> icon.poi.type == PoiType.QUEST_START)
            .forEach(icon -> reads.add(icon.poi.name));
        watched = reads;
        route.setIcons(data.icons);
        clientThread.invokeLater(() -> unlocksDirty = true);
        Set<Integer> elements = data.icons.stream().filter(icon -> icon.element >= 0).map(icon -> icon.element)
            .collect(Collectors.toCollection(HashSet::new));
        whenGameLoaded(started, () -> GameIconSprites.loadElements(client, elements), () -> { });
        SwingUtilities.invokeLater(() -> {
            if (screen != null && generation == dataGeneration.get())
            {
                // The game's icons with the data they belong to: a found shop is selected among them, a found passage
                // shown by the game's icon for that way in.
                for (MapIconLayer layer : gameIcons)
                {
                    layer.setIcons(data.icons, data.hidden);
                }
                screen.setData(maps, data);
                fullScreen.setData(maps, data);
            }
        });
        refreshLater();
    }

    void runData(Runnable task)
    {
        TileCache.execute(dataIo, task);
    }

    void togglePopOut()
    {
        if (window != null)
        {
            dock();
            return;
        }
        window = new MapWindow(screen, this::dock);
        window.setVisible(true);
    }

    void dock()
    {
        MapWindow closing = window;
        window = null;
        if (closing != null)
        {
            closing.dispose();
        }
    }

    void showOnMap(WorldPoint point)
    {
        if (fullMap == null)
        {
            return;
        }
        fullMap.open();
        fullScreen.view.focus(point);
    }
}
