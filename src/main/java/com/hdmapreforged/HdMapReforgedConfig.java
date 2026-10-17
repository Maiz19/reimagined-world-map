package com.hdmapreforged;

import java.awt.*;
import java.awt.event.*;
import lombok.*;
import net.runelite.client.config.*;

@ConfigGroup(HdMapReforgedConfig.GROUP)
public interface HdMapReforgedConfig extends Config
{
    String GROUP = "hdmapreforged";
    int MIN_DROP_VALUE = 1000;

    @ConfigSection(name = "Icons", description = "Which icons the map shows", position = 0)
    String icons = "icons";

    @ConfigSection(name = "Map", description = "Map behaviour and appearance", position = 1)
    String map = "map";

    @ConfigSection(name = "Data and downloads", description = "What is downloaded, and how it is kept", position = 2)
    String data = "data";

    @ConfigSection(name = "Route", description = "Right-click the map, Path to here. It only shows the way, nothing "
        + "is walked or clicked for you", position = 3)
    String route = "route";

    @ConfigSection(name = "Own route planner", description = "Only for this plugin's own planner (Route planner: "
        + "Reimagined World Map)", position = 4)
    String planner = "planner";

    @ConfigSection(name = "Party", description = "Party members on the map, through RuneLite's party service",
        position = 5)
    String friends = "friends";

    @ConfigSection(name = "Miscellaneous", description = "Everything else", position = 6)
    String misc = "misc";

    @ConfigItem(keyName = "showTeleports", name = "Teleports", position = 0, section = icons,
        description = "Teleport spells, items and minigame teleports")
    default boolean showTeleports()
    {
        return true;
    }

    @ConfigItem(keyName = "showTransports", name = "Transport networks", position = 1, section = icons,
        description = "Fairy rings, spirit trees, gliders, balloons, boats, carts, carpets and more")
    default boolean showTransports()
    {
        return true;
    }

    @ConfigItem(keyName = "showDungeons", name = "Dungeon entrances", position = 2, section = icons,
        description = "Ladders, stairs and holes to another map. Click one to look inside")
    default boolean showDungeons()
    {
        return true;
    }

    @ConfigItem(keyName = "showSailing", name = "Sailing", position = 3, section = icons,
        description = "Moorings and salvage spots")
    default boolean showSailing()
    {
        return true;
    }

    @ConfigItem(keyName = "showSkilling", name = "Skilling", position = 4, section = icons,
        description = "Runecrafting altars, agility courses and shortcuts, farming patches")
    default boolean showSkilling()
    {
        return true;
    }

    @ConfigItem(keyName = "showActivities", name = "Minigames", position = 5, section = icons,
        description = "Minigame locations")
    default boolean showActivities()
    {
        return true;
    }

    @ConfigItem(keyName = "showServices", name = "Banks, altars and anvils", position = 6, section = icons,
        description = "Shown when zoomed in")
    default boolean showServices()
    {
        return true;
    }

    @ConfigItem(keyName = "showGameIcons", name = "Game map icons", position = 7, section = icons,
        description = "The game's own map icons: shops, quest starts, map links and more")
    default boolean showGameIcons()
    {
        return true;
    }

    @ConfigItem(keyName = "questStatus", name = "Quest status", position = 8, section = icons,
        description = "A dot on quest start icons: green finished, yellow started, red not started")
    default boolean questStatus()
    {
        return true;
    }

    @ConfigItem(keyName = "hideCompletedQuests", name = "Hide completed quests", position = 9, section = icons,
        description = "No quest start icons for finished quests")
    default boolean hideCompletedQuests()
    {
        return false;
    }

    @ConfigItem(keyName = "onlyUsable", name = "Only what I can use", position = 10, section = icons,
        description = "Hide icons and routes whose level, quest or unlock you lack (needs you logged in)")
    default boolean onlyUsable()
    {
        return false;
    }

    @Range(min = 10, max = 40)
    @Units(Units.PIXELS)
    @ConfigItem(keyName = "iconSize", name = "Icon size", position = 11, section = icons,
        description = "Size of map icons. The game's own icons grow in whole steps to stay sharp")
    default int iconSize()
    {
        return 18;
    }

    @ConfigItem(keyName = "orbOpensMap", name = "World map orb opens this map", position = -3,
        description = "Left-click the world map orb to open this map. The game's own stays on right-click World Map")
    default boolean orbOpensMap()
    {
        return false;
    }

    @ConfigItem(keyName = "openMapKey", name = "Open map hotkey",
        description = "Opens and closes the map. Escape also closes it")
    default Keybind openMapKey()
    {
        return new Keybind(KeyEvent.VK_M, 0);
    }

    /** Icon kinds whose destination lines are hidden. */
    @ConfigItem(keyName = "linesOff", name = "", description = "", hidden = true)
    default String linesOff()
    {
        return "FAIRY_RING";
    }

    @ConfigItem(keyName = "fullMapBounds", name = "", description = "", hidden = true)
    default String fullMapBounds()
    {
        return "";
    }

    @ConfigItem(keyName = "followPlayer", name = "Follow player", position = 0, section = map,
        description = "Keep your character in view. Dragging the map pauses this")
    default boolean followPlayer()
    {
        return true;
    }

    @ConfigItem(keyName = "showLabels", name = "Place names", position = 1, section = map,
        description = "Names of kingdoms, seas, towns and smaller places")
    default boolean showLabels()
    {
        return true;
    }

    @ConfigItem(keyName = "showWilderness", name = "Wilderness levels", position = 2, section = map,
        description = "Lines every five Wilderness levels, with the level 20 and 30 teleport limits")
    default boolean showWilderness()
    {
        return true;
    }

    @Range(min = 1, max = 9)
    @ConfigItem(keyName = "defaultZoom", name = "Default zoom", position = 3, section = map,
        description = "How close the map opens on you: 1 far out, 9 close up")
    default int defaultZoom()
    {
        return 6;
    }

    @Range(min = 100, max = 10000)
    @Units(" MB")
    @ConfigItem(keyName = "diskCacheMb", name = "Disk cache size", position = 1, section = data,
        description = "Downloaded tiles kept on disk, the oldest go first above this size")
    default int diskCacheMb()
    {
        return 1000;
    }

    @ConfigItem(keyName = "checkMapNow", name = "Check for a new map now", position = 2, section = data,
        description = "The map checks weekly by itself. Click to check right away")
    default boolean checkMapNow()
    {
        return false;
    }

    @ConfigItem(keyName = "partyShowMembers", name = "Show party members", position = 0, section = friends,
        description = "Where your party's members are (join one in RuneLite's Party panel)")
    default boolean partyShowMembers()
    {
        return true;
    }

    @ConfigItem(keyName = "partyShareGear", name = "Share inventory, equipment and skills", position = 2,
        section = friends, description = "Your party sees what you carry and wear, your skills and your energy. "
            + "Nothing is sent outside a party")
    default boolean partyShareGear()
    {
        return true;
    }

    @ConfigItem(keyName = "partyShareDrops", name = "Share valuable drops", position = 3, section = friends,
        description = "Drops worth at least the value below show above you on your party's maps")
    default boolean partyShareDrops()
    {
        return true;
    }

    @ConfigItem(keyName = "partyShareUntradeables", name = "Share untradeable drops", position = 5, section = friends,
        description = "With Share valuable drops on, also untradeable drops (clues, keys, shards), by name")
    default boolean partyShareUntradeables()
    {
        return true;
    }

    @Range(min = MIN_DROP_VALUE)
    @ConfigItem(keyName = "partyDropValue", name = "Valuable drop from", position = 4, section = friends,
        description = "The least value in coins of one item stack to share it")
    default int partyDropValue()
    {
        return 100_000;
    }

    @ConfigItem(keyName = "partyShareLocation", name = "Share my location", position = 1, section = friends,
        description = "Your party's maps show where you are. Nothing is sent outside a party")
    default boolean partyShareLocation()
    {
        return true;
    }

    @ConfigItem(keyName = "partyFriendsTab", name = "Party button on the map", position = 6, section = friends,
        description = "A button at the map's bottom left lists your party and their worlds")
    default boolean partyFriendsTab()
    {
        return true;
    }

    @RequiredArgsConstructor
    enum RoutePlanner
    {
        OWN("Reimagined World Map"),
        SHORTEST_PATH("Shortest Path plugin");

        final String name;

        public String toString()
        {
            return name;
        }
    }

    @ConfigItem(keyName = "routePlanner", name = "Route planner", position = 0, section = route,
        description = "Who plans Path to here: this plugin, or the Shortest Path plugin if you have it")
    default RoutePlanner routePlanner()
    {
        return RoutePlanner.OWN;
    }

    @ConfigItem(keyName = "routeFollowPlugins", name = "Follow other plugins' directions", position = 17,
        section = planner, description = "Also route to directions other plugins (such as Quest Helper) send to "
            + "Shortest Path")
    default boolean routeFollowPlugins()
    {
        return true;
    }

    @RequiredArgsConstructor
    enum RouteStyle
    {
        TILES("Tiles"),
        LINES("Lines"),
        ARROWS("Lines with arrows");

        final String name;

        public String toString()
        {
            return name;
        }
    }

    @ConfigItem(keyName = "routeStyle", name = "Path style", position = 1, section = route,
        description = "The route on the ground as tiles, or as a line through them with arrows if you like")
    default RouteStyle routeStyle()
    {
        return RouteStyle.TILES;
    }

    @Range(min = 1, max = 16)
    @ConfigItem(keyName = "routeLineWidth", name = "Line width", position = 2, section = route,
        description = "Width of the route's line on the ground")
    default int routeLineWidth()
    {
        return 3;
    }

    @ConfigItem(keyName = "routeColor", name = "Route colour", position = 3, section = route,
        description = "Colour of the route's walks")
    default Color routeColor()
    {
        return RouteFeature.WALK;
    }

    @ConfigItem(keyName = "routeJumpColor", name = "Teleport colour", position = 4, section = route,
        description = "Colour of the route's teleports, transports and passages")
    default Color routeJumpColor()
    {
        return RouteFeature.JUMP;
    }

    @Range(max = 255)
    @ConfigItem(keyName = "routeTileFill", name = "Tile fill opacity", position = 5, section = route,
        description = "0 is only the border, 255 solid")
    default int routeTileFill()
    {
        return 75;
    }

    @Range(max = 255)
    @ConfigItem(keyName = "routeTileBorder", name = "Tile border opacity", position = 6, section = route,
        description = "0 is no border")
    default int routeTileBorder()
    {
        return 0;
    }

    @ConfigItem(keyName = "routeTileWidth", name = "Tile border width", position = 7, section = route,
        description = "Border width of the route's ground tiles")
    default double routeTileWidth()
    {
        return 1.5;
    }

    @ConfigItem(keyName = "routeMinimap", name = "Route on the minimap", position = 8, section = route,
        description = "Dots on the minimap for the route's tiles")
    default boolean routeMinimap()
    {
        return true;
    }

    @ConfigItem(keyName = "routeTeleports", name = "Use teleports", position = 0, section = planner,
        description = "Teleports whose runes or item you carry")
    default boolean routeTeleports()
    {
        return true;
    }

    @ConfigItem(keyName = "routeSailing", name = "Sail my boat", position = 1, section = planner,
        description = "Within your Sailing level (needs Pandemonium done)")
    default boolean routeSailing()
    {
        return true;
    }

    @ConfigItem(keyName = "routeSailToLand", name = "Sail on land routes", position = 2, section = planner,
        description = "Off: sail only to or from the sea, or where nothing else gets there")
    default boolean routeSailToLand()
    {
        return false;
    }

    @ConfigItem(keyName = "routeClearOnArrival", name = "Done when arrived", position = 3, section = planner,
        description = "Remove the route once you are a few tiles from its end")
    default boolean routeClearOnArrival()
    {
        return true;
    }

    @ConfigItem(keyName = "routeAvoidWilderness", name = "Avoid the Wilderness", position = 4, section = planner,
        description = "Unless the route starts or ends there or no other way gets there. The Ferox Enclave is fine")
    default boolean routeAvoidWilderness()
    {
        return true;
    }

    @ConfigItem(keyName = "routeTips", name = "Tips to bring items", position = 5, section = planner,
        description = "A line under the route when bringing coins or runes would be notably faster")
    default boolean routeTips()
    {
        return true;
    }

    @ConfigItem(keyName = "routeBankTeleports", name = "Teleports from the bank", position = 6, section = planner,
        description = "Remember your bank (seen when opened) and go past a bank first when that is notably faster")
    default boolean routeBankTeleports()
    {
        return true;
    }

    @ConfigItem(keyName = "routeIgnoreLevels", name = "Ignore levels", position = 7, section = planner,
        description = "Plan as if every level, quest and unlock were met")
    default boolean routeIgnoreLevels()
    {
        return false;
    }

    @ConfigItem(keyName = "routeIgnoreItems", name = "Ignore items", position = 8, section = planner,
        description = "Plan as if you had every teleport item, rune and fare")
    default boolean routeIgnoreItems()
    {
        return false;
    }

    @ConfigItem(keyName = "routeNeverUse", name = "Never use", position = 9, section = planner,
        description = "Teleports and transports never taken, one per line, a whole kind as type:Hot air balloon. "
            + "Add them with a route step's ⋯ button")
    default String routeNeverUse()
    {
        return "";
    }

    @Range(max = 500)
    @Units(" tiles")
    @ConfigItem(keyName = "routeSaveTeleport", name = "Teleport if it saves", position = 10, section = planner,
        description = "Only when it saves at least this many tiles of running")
    default int routeSaveTeleport()
    {
        return 10;
    }

    @Range(max = 500)
    @Units(" tiles")
    @ConfigItem(keyName = "routeSaveShortcut", name = "Shortcut if it saves", position = 11, section = planner,
        description = "Agility shortcuts only when they save at least this many tiles")
    default int routeSaveShortcut()
    {
        return 0;
    }

    @Range(max = 500)
    @Units(" tiles")
    @ConfigItem(keyName = "routeSaveTransport", name = "Transport if it saves", position = 12, section = planner,
        description = "Fairy rings, spirit trees, gliders and such only when they save at least this many tiles")
    default int routeSaveTransport()
    {
        return 5;
    }

    @Range(max = 500)
    @Units(" tiles")
    @ConfigItem(keyName = "routeSaveCanoe", name = "Canoe if it saves", position = 13, section = planner,
        description = "Only when it saves at least this many tiles")
    default int routeSaveCanoe()
    {
        return 10;
    }

    @Range(max = 500)
    @Units(" tiles")
    @ConfigItem(keyName = "routeSaveCarpet", name = "Magic carpet if it saves", position = 14, section = planner,
        description = "Only when it saves at least this many tiles")
    default int routeSaveCarpet()
    {
        return 10;
    }

    @Range(max = 500)
    @Units(" tiles")
    @ConfigItem(keyName = "routeSaveShip", name = "Boat if it saves", position = 15, section = planner,
        description = "Boats and charter ships only when they save at least this many tiles")
    default int routeSaveShip()
    {
        return 10;
    }

    @Range(max = 500)
    @Units(" tiles")
    @ConfigItem(keyName = "routeSaveBank", name = "Bank if it saves", position = 16, section = planner,
        description = "With Teleports from the bank: only when the bank saves at least this many tiles")
    default int routeSaveBank()
    {
        return 10;
    }

    @ConfigItem(keyName = "showVersion", name = "Show plugin version", position = 0, section = misc,
        description = "The plugin's version in the bottom right corner of the map")
    default boolean showVersion()
    {
        return true;
    }
}
