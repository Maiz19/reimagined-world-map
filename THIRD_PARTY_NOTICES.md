# Third-party notices

## RuneLite

- Source: https://github.com/runelite/runelite, tag `runelite-parent-1.12.39`:
  - `runelite-client/src/main/java/net/runelite/client/plugins/worldmap/`: `DungeonLocation`, `TransportationPointLocation`, `MooringLocation`, `SalvagingSpotLocation`, `RunecraftingAltarLocation`, `MinigameLocation`, `AgilityCourseLocation`, `FarmingPatchLocation`, `HunterAreaLocation`, `FishingSpotLocation`, `MiningSiteLocation`, `RareTreeLocation`, `TeleportLocationData`, `TeleportType` and `FairyRingLocation`;
  - `runelite-client/src/main/java/net/runelite/client/game/`: `FishingSpot` and `AgilityShortcut`;
  - `runelite-client/src/main/java/net/runelite/client/plugins/discord/DiscordGameEventType` (names of areas by map region);
  - `runelite-api/src/main/java/net/runelite/api/`: `Quest`, `ParamID` and the `gameval` lists `ItemID`, `ObjectID`, `ObjectID1` and `VarbitID` (names and ids).
  - `runelite-client/src/main/resources/net/runelite/client/plugins/worldmap/`: the World Map plugin's pixel-art icons of teleport items and runecrafting altars (`*_teleport_icon.png`, `*_cape_icon.png`, `*_altar_icon.png` and the like), copied into the JAR as `com/hdmapreforged/pictures/<item id>.png` (smaller ones centred on 15 by 15 pixels) and drawn on the map for those teleports and altars.
- Authors named in those files and in RuneLite's license: Morgan Lewis, Magic fTail, Torkel Velure, Bryce Altomare, Dava96, Kyle Sergio, Kyle Stead, Arman S, melky, dekvall, Spedwards, Seth, coopermor, Sam Szotkowski, SomeoneWithAnInternetConnection, MrGroggle, Tomas Slusny, PandahRS, Brooklyn, Abex, Adam, and the RuneLite contributors.
- License: BSD 2-Clause. Full text in `src/main/resources/META-INF/LICENSE-runelite`, included in the JAR.

The names, levels, coordinates and ids of those files are converted to tables by our development tools (not published): `build-runelite-lists.py` and `build-skill-spots.py` (`data/runelite_*.tsv`; fish and ore levels added from the game), `build-runelite-travel.py` (the travel tables `teleports.tsv`, `transport_networks.tsv`, `transport_routes.tsv` and `shortcuts.tsv`) and `build-runelite-areas.py` (the names of our dungeon maps). No RuneLite code is copied, only the icons named above. Only `shortcuts.tsv` reaches the plugin, in the route data it downloads with each map version (`route.zip`, read by the route planner); the others are development data (not published), and what the map shows from them reaches the plugin in our map server's files of each map version: `places.json`, which names them in its `credits`, and the dungeon maps' names in `maps.json`.

## Shortest Path

- Source: https://github.com/Skretzo/shortest-path (Skretzo and contributors), its transport tables `transports.tsv` and `teleportation_portals.tsv` (read 2026-10-04).
- What it gives: where a few passages land that neither the game's data nor the OSRS Wiki states, each named at its row in `route/links.tsv` and `route/gates.tsv`: the Wizards' Guild's three portals, the ladder down from the island east of Gu'Tanoth, the Ancient Pyramid's back door and its altar room's portal. No code is copied; the plugin talks to a running Shortest Path only through RuneLite's plugin messages.
- License: BSD 2-Clause. Full text in `src/main/resources/META-INF/LICENSE-shortest-path`, included in the JAR.

## Old School RuneScape game data

The game and its data are © Jagex Ltd. Our development tools read the OSRS game cache, as archived by [OpenRS2](https://archive.openrs2.org), with RuneLite's `net.runelite:cache` library (BSD 2-Clause). No game code, models or graphics are in the JAR; the game's map icons are drawn with the sprites of the running game client.

- `data/map_icons.tsv` (development data, not published): where the game's world map icons are, the quest names and fairy ring codes they belong to, the names of their kinds (the world map's key) and where map links lead (cache 2727 of 2026-09-30, `local-development/tools/map-icons`). What the map shows from it reaches the plugin in `places.json`; the route data (`route.zip`) has `route/map_link_starts.tsv`, where the map links start (`MapLinkStarts`, a development tool in the development tools (not published)).
- In the route data (`route.zip`): `world_map_moves.tsv`, the parts of the game the world map draws elsewhere, from its composite maps (`tools/map-icons`, `WorldMapMoves`); `sea.bin`, where boats sail, made by the map server from the collision map's water and the named seas below (`SeaMaps`).
- `route/collision.zip` (which tiles can be walked on, walls and doors, water; ladders, stairs and entrances with where they lead) and `route/ports.tsv` (the Sailing ports), from the map files, object definitions and database of cache 2720 of 2026-09-23 (`tools/collision`). The data is our own summary of the game's map.
- `route/map_link_passages.tsv`: the game's map links paired with the cache's placed objects (`MapLinkPassages`, a development tool, not published).
- The travel tables take spell levels and runes, item ids and placed objects (house portals, the floors and far sides of shortcut obstacles) from cache 2727 (`tools/travel-cache`).
- From our map server, made from the game cache and not in the JAR: the map tiles, the list of maps, the labels, the pictures of NPCs and, in `search.json`, the game's names of NPCs and items. The tests' `place_names.tsv` (the game's labels, written by the map server's `mapLabels`), `basemaps.json` and `region_maps.tsv` (one map version's `maps.json` and `regions.tsv`) come from the same cache.

## Old School RuneScape Wiki

© the OSRS Wiki / Weird Gloop, [CC BY-NC-SA 3.0](https://weirdgloop.org/licensing/). The plugin never contacts the wiki; its *Wiki* buttons open a wiki page in the browser.

- Downloaded from our map server in its `search.json`, worked out from the wiki's data tables, which the server reads once a week: where NPCs and monsters stand, shops with their wares and stock, monsters' drop tables and the items lying on the ground.
- In the route data the plugin downloads with a map version (`route.zip`), each naming its wiki pages: `route/sea_areas.tsv` (named seas and their Sailing hazards, from *Category:Seas* and *Sailing hazards*, built by `local-development/tools/build-sea-areas.py`); `route/gates.tsv`, `route/fees.tsv`, `route/one_way.tsv` and `route/links.tsv`, made by hand from wiki pages and the game cache (`data/shortcuts.tsv` leaves out the directions `one_way.tsv` rules out); the Corporeal Beast's lair as the destination of its cave's map link, where the wiki's map draws it (a correction in `tools/map-icons`, so also in `route/map_link_passages.tsv`); and the order of the jewellery box's destinations in route steps (*Ornate jewellery box*, in `route/lists.tsv`).
- Development data (not published) whose content reaches the plugin in `places.json`: `data/icon_names.tsv` (the Barracuda Trials' icon names) and, in `teleports.tsv`, the quests teleport spells need and the teleport tablets (the wiki's spell and tablet pages). Since 2026-10-03 also the travel the game cache and RuneLite's lists do not give, merged into `teleports.tsv`, `transport_routes.tsv` and `transport_networks.tsv` by `local-development/tools/merge-wiki-travel.py` from the plugin's wiki-era tables (`local-development/tools/wiki-travel`): charter ships, canoes, minecarts, the way back of magic carpets and levers, home and minigame teleports and other items' destinations, fares and the quests and levels of boats and network stops, and a few landing spots and requirements by hand (the wiki's *Minigame Teleport*, *Energy Barrier* and *Primio* pages). The tests' `boss_targets.tsv` lists bosses of the wiki's *Category:Bosses*.
