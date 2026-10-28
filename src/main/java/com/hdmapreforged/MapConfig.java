package com.hdmapreforged;

import java.awt.*;
import java.awt.event.*;
import lombok.*;
import net.runelite.client.config.*;

@ConfigGroup(MapConfig.GROUP)
public interface MapConfig extends Config {
    String GROUP = "hdmapreforged";
    int MIN_DROP_VALUE = 1000;

    @ConfigSection(name = "Map", description = "How the map looks and behaves; icons: its Icons button",
        position = 1)
    String map = "map";

    @ConfigSection(name = "Data and downloads", description = "Downloads and disk cache", position = 4,
        closedByDefault = true)
    String data = "data";

    @ConfigSection(name = "Route", description = "Right-click the map, Path to here. Only shows the way", position = 2)
    String route = "route";

    @ConfigSection(name = "Own route planner", description = "Only with Route planner: Reimagined World Map", position = 3, closedByDefault = true)
    String planner = "planner";

    @ConfigSection(name = "Party", description = "Party members on the map",
        position = 5, closedByDefault = true)
    String friends = "friends";

    /** The map's Icons panel: kinds of icons hidden ({@link Poi#toggle}, {@link MapData.Icon#toggle}), one a line. */
    @ConfigItem(keyName = "iconsOff", name = "", description = "", hidden = true)
    default String iconsOff() {
        return "";
    }

    @ConfigItem(keyName = "questStatus", name = "", description = "", hidden = true)
    default boolean questStatus() {
        return true;
    }

    @ConfigItem(keyName = "hideCompletedQuests", name = "", description = "", hidden = true)
    default boolean hideCompletedQuests() {
        return false;
    }

    @ConfigItem(keyName = "markLocked", name = "", description = "", hidden = true)
    default boolean markLocked() {
        return true;
    }

    @ConfigItem(keyName = "onlyUsable", name = "", description = "", hidden = true)
    default boolean onlyUsable() {
        return false;
    }

    @ConfigItem(keyName = "iconSize", name = "", description = "", hidden = true)
    default int iconSize() {
        return 18;
    }

    @ConfigItem(keyName = "orbOpensMap", name = "World map orb opens this map", position = -3,
        description = "Left-click opens this map; right-click World Map opens the game's")
    default boolean orbOpensMap() {
        return false;
    }

    @ConfigItem(keyName = "openMapKey", name = "Open map hotkey",
        description = "Opens and closes the map; Escape closes it too")
    default Keybind openMapKey() {
        return new Keybind(KeyEvent.VK_M, 0);
    }

    /** Icon kinds whose destination lines are hidden. */
    @ConfigItem(keyName = "linesOff", name = "", description = "", hidden = true)
    default String linesOff() {
        return "FAIRY_RING";
    }

    @ConfigItem(keyName = "fullMapBounds", name = "", description = "", hidden = true)
    default String fullMapBounds() {
        return "";
    }

    @ConfigItem(keyName = "followPlayer", name = "Follow player", position = 0, section = map,
        description = "Keep your character in view; dragging pauses it")
    default boolean followPlayer() {
        return true;
    }

    @ConfigItem(keyName = "showLabels", name = "Place names", position = 1, section = map,
        description = "Names of kingdoms, seas, towns and places")
    default boolean showLabels() {
        return true;
    }

    @ConfigItem(keyName = "showWilderness", name = "Wilderness levels", position = 2, section = map,
        description = "Lines every five levels, with the level 20 and 30 limits")
    default boolean showWilderness() {
        return true;
    }

    @ConfigItem(keyName = "sidebar", name = "Panel in the sidebar", position = 4, section = map,
        description = "Route steps, custom routes and party in the sidebar. Off: open the map by hotkey or orb")
    default boolean sidebar() {
        return true;
    }

    @ConfigItem(keyName = "pluginIcons", name = "Show other plugins' map icons", position = 5, section = map,
        description = "What other plugins put on the game's world map: slayer tasks, Quest Helper's step")
    default boolean pluginIcons() {
        return true;
    }

    @Range(min = 1, max = 9)
    @ConfigItem(keyName = "defaultZoom", name = "Default zoom", position = 3, section = map,
        description = "1 far out, 9 close up")
    default int defaultZoom() {
        return 6;
    }

    @Range(min = 100, max = 10000)
    @Units(" MB")
    @ConfigItem(keyName = "diskCacheMb", name = "Disk cache size", position = 1, section = data,
        description = "Tiles kept on disk; the oldest go first")
    default int diskCacheMb() {
        return 1000;
    }

    @ConfigItem(keyName = "checkMapNow", name = "Check for a new map now", position = 2, section = data,
        description = "It checks at each login; click to check now")
    default boolean checkMapNow() {
        return false;
    }

    @ConfigItem(keyName = "downloadMapAgain", name = "Download the map again", position = 3, section = data,
        description = "Only if parts look wrong: downloads every tile again")
    default boolean downloadMapAgain() {
        return false;
    }

    @ConfigItem(keyName = "partyShowMembers", name = "Show party members", position = 0, section = friends,
        description = "Join a party in RuneLite's Party panel")
    default boolean partyShowMembers() {
        return true;
    }

    @ConfigItem(keyName = "partyShareGear", name = "Share inventory, equipment and skills", position = 2,
        section = friends, description = "What you carry and wear, skills and energy; only to your party")
    default boolean partyShareGear() {
        return true;
    }

    @ConfigItem(keyName = "partyShareDrops", name = "Share valuable drops", position = 3, section = friends,
        description = "Drops worth at least the value below")
    default boolean partyShareDrops() {
        return true;
    }

    @ConfigItem(keyName = "partyShareUntradeables", name = "Share untradeable drops", position = 5, section = friends,
        description = "Also clues, keys, shards and such, by name")
    default boolean partyShareUntradeables() {
        return true;
    }

    @Range(min = MIN_DROP_VALUE)
    @ConfigItem(keyName = "partyDropValue", name = "Valuable drop from", position = 4, section = friends,
        description = "Least value of one item stack to share")
    default int partyDropValue() {
        return 10_000;
    }

    @ConfigItem(keyName = "partyShareLocation", name = "Share my location", position = 1, section = friends,
        description = "Only to your party")
    default boolean partyShareLocation() {
        return true;
    }

    @ConfigItem(keyName = "partyFriendsTab", name = "Party button on the map", position = 6, section = friends,
        description = "Lists your party and their worlds")
    default boolean partyFriendsTab() {
        return true;
    }

    @RequiredArgsConstructor
    enum RoutePlanner {
        OWN("Reimagined World Map"),
        SHORTEST_PATH("Shortest Path plugin");

        final String name;

        public String toString() {
            return name;
        }
    }

    @ConfigItem(keyName = "routePlanner", name = "Route planner", position = 0, section = route,
        description = "This plugin, or Shortest Path if you have it")
    default RoutePlanner routePlanner() {
        return RoutePlanner.OWN;
    }

    @ConfigItem(keyName = "routeFollowPlugins", name = "Follow other plugins' directions", position = 17,
        section = planner, description = "Directions other plugins (Quest Helper) send to Shortest Path")
    default boolean routeFollowPlugins() {
        return true;
    }

    @RequiredArgsConstructor
    enum RouteStyle {
        TILES("Tiles"),
        LINES("Lines"),
        ARROWS("Lines with arrows");

        final String name;

        public String toString() {
            return name;
        }
    }

    @ConfigItem(keyName = "routeStyle", name = "Path style", position = 1, section = route,
        description = "Tiles, or a line with arrows")
    default RouteStyle routeStyle() {
        return RouteStyle.TILES;
    }

    @Range(min = 1, max = 16)
    @ConfigItem(keyName = "routeLineWidth", name = "Line width", position = 2, section = route,
        description = "Width of the line")
    default int routeLineWidth() {
        return 3;
    }

    @ConfigItem(keyName = "routeColor", name = "Route colour", position = 3, section = route,
        description = "Colour of the walks")
    default Color routeColor() {
        return RouteFeature.WALK;
    }

    @ConfigItem(keyName = "routeJumpColor", name = "Teleport colour", position = 4, section = route,
        description = "Colour of teleports, transports and passages")
    default Color routeJumpColor() {
        return RouteFeature.JUMP;
    }

    @Range(max = 255)
    @ConfigItem(keyName = "routeTileFill", name = "Tile fill opacity", position = 5, section = route,
        description = "0 border only, 255 solid")
    default int routeTileFill() {
        return 75;
    }

    @Range(max = 255)
    @ConfigItem(keyName = "routeTileBorder", name = "Tile border opacity", position = 6, section = route,
        description = "0 no border")
    default int routeTileBorder() {
        return 0;
    }

    @ConfigItem(keyName = "routeTileWidth", name = "Tile border width", position = 7, section = route,
        description = "Width of the tiles' border")
    default double routeTileWidth() {
        return 1.5;
    }

    @ConfigItem(keyName = "routeMinimap", name = "Route on the minimap", position = 8, section = route,
        description = "Dots on the minimap")
    default boolean routeMinimap() {
        return true;
    }

    @ConfigItem(keyName = "routeItems", name = "Highlight route items", position = 9, section = route,
        description = "Outline the next teleport's item or spell, and in the bank what to take")
    default boolean routeItems() {
        return true;
    }

    @ConfigItem(keyName = "routeTeleports", name = "Use teleports", position = 0, section = planner,
        description = "Those whose runes or item you carry")
    default boolean routeTeleports() {
        return true;
    }

    @ConfigItem(keyName = "routeSailing", name = "Sail my boat", position = 1, section = planner,
        description = "Within your Sailing level (Pandemonium done)")
    default boolean routeSailing() {
        return true;
    }

    @ConfigItem(keyName = "routeSailToLand", name = "Sail on land routes", position = 2, section = planner,
        description = "Off: only to or from the sea, or where nothing else goes")
    default boolean routeSailToLand() {
        return false;
    }

    @ConfigItem(keyName = "routeClearOnArrival", name = "Done when arrived", position = 3, section = planner,
        description = "Remove the route a few tiles from its end")
    default boolean routeClearOnArrival() {
        return true;
    }

    @ConfigItem(keyName = "routeAvoidWilderness", name = "Avoid the Wilderness", position = 4, section = planner,
        description = "Unless it starts or ends there or must; Ferox Enclave is fine")
    default boolean routeAvoidWilderness() {
        return true;
    }

    @ConfigItem(keyName = "routeTips", name = "Tips to bring items", position = 5, section = planner,
        description = "A line when bringing coins or runes would be notably faster")
    default boolean routeTips() {
        return true;
    }

    @ConfigItem(keyName = "routeBankTeleports", name = "Teleports from the bank", position = 6, section = planner,
        description = "Remember your bank and go past it when notably faster")
    default boolean routeBankTeleports() {
        return true;
    }

    @ConfigItem(keyName = "routeIgnoreLevels", name = "Ignore levels", position = 7, section = planner,
        description = "As if every level, quest and unlock were met")
    default boolean routeIgnoreLevels() {
        return false;
    }

    @ConfigItem(keyName = "routeIgnoreItems", name = "Ignore items", position = 8, section = planner,
        description = "As if you had every teleport item, rune and fare")
    default boolean routeIgnoreItems() {
        return false;
    }

    @ConfigItem(keyName = "routeNeverUse", name = "Never use", position = 9, section = planner,
        description = "One per line, a kind as type:Hot air balloon. Add with a step's ⋯")
    default String routeNeverUse() {
        return "";
    }

    @Range(max = 500)
    @Units(" tiles")
    @ConfigItem(keyName = "routeSaveTeleport", name = "Teleport if it saves", position = 10, section = planner,
        description = "Tiles of running it must save")
    default int routeSaveTeleport() {
        return 10;
    }

    @Range(max = 500)
    @Units(" tiles")
    @ConfigItem(keyName = "routeSaveShortcut", name = "Shortcut if it saves", position = 11, section = planner,
        description = "Tiles it must save")
    default int routeSaveShortcut() {
        return 0;
    }

    @Range(max = 500)
    @Units(" tiles")
    @ConfigItem(keyName = "routeSaveTransport", name = "Transport if it saves", position = 12, section = planner,
        description = "Fairy rings, spirit trees, gliders: tiles they must save")
    default int routeSaveTransport() {
        return 5;
    }

    @Range(max = 500)
    @Units(" tiles")
    @ConfigItem(keyName = "routeSaveCanoe", name = "Canoe if it saves", position = 13, section = planner,
        description = "Tiles it must save")
    default int routeSaveCanoe() {
        return 10;
    }

    @Range(max = 500)
    @Units(" tiles")
    @ConfigItem(keyName = "routeSaveCarpet", name = "Magic carpet if it saves", position = 14, section = planner,
        description = "Tiles it must save")
    default int routeSaveCarpet() {
        return 10;
    }

    @Range(max = 500)
    @Units(" tiles")
    @ConfigItem(keyName = "routeSaveShip", name = "Boat if it saves", position = 15, section = planner,
        description = "Boats and charters: tiles they must save")
    default int routeSaveShip() {
        return 10;
    }

    @Range(max = 500)
    @Units(" tiles")
    @ConfigItem(keyName = "routeSaveBank", name = "Bank if it saves", position = 16, section = planner,
        description = "Tiles the bank must save")
    default int routeSaveBank() {
        return 10;
    }}
