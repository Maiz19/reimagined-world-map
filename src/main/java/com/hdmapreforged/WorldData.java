package com.hdmapreforged;

import static java.util.Collections.*;
import static com.hdmapreforged.ItemSources.*;
import static java.util.stream.Collectors.*;
import com.google.gson.*;
import java.util.*;
import java.util.function.*;
import lombok.*;
import net.runelite.api.coords.*;

/**
 * What the search finds, as our map server works it out once for every player at each wiki sync (its search.json, see
 * {@code MapServer}): each NPC name's places with how many stand there or the way in, the kind most seen and what
 * they drop; each item's shops, spots on the ground and droppers; the shops' wares; the names to suggest; and which
 * map draws each place where maps overlap, for the map version it names. Immutable.
 */
final class WorldData {
    /** The data version's file read: search.json's members as these classes hold them (format 1 is the released plugins'). */
    static final String FILE = "search2";
    /** Of search2.json: a file in another format (an older or newer map server) is not read. */
    static final int FORMAT = 2;
    static final WorldData NONE = new WorldData(emptyMap(), emptyMap(), emptyList(),
        new String[0], new String[0], null, emptyMap());
    /** A shop is where the server puts it: a shop icon or a card this near is that shop. */
    static final int SHOP_RADIUS = 12;
    /** A shop that never runs out. */
    static final int ENDLESS = -1;

    @RequiredArgsConstructor
    static final class Shop {
        final String name;
        final WorldPoint point;
        /** Its shopkeeper's NPC id, for the picture; -1 when not known. */
        final int npc;
        /** What it sells, with how many it keeps while no one trades ({@link #ENDLESS}). */
        final List<Ware> wares;
    }

    @RequiredArgsConstructor
    static final class Drop {
        final int item;
        /** The game's name of the item. */
        final String name;
        /** As the data words it: "Always", "3/128", "Common". */
        final String rarity;
        /** "5-10", "7"; empty for one. */
        final String quantity;
        /** The part of the drop table: "a" always, "r" the rare drop table, "t" tertiary, "" the main table. */
        final String part;
        /** The monster's version this row is for when not the table's own ("Catacombs of Kourend"); else "". */
        final String version;
    }

    /** One of a monster's drop tables: the monster's version ("Wilderness Slayer Cave"), a kind of loot. */
    @RequiredArgsConstructor
    static final class Table {
        /** Empty for a page's only table. */
        final String name;
        final List<Drop> drops;
    }

    final List<Shop> shops;
    /** By {@link #key}. */
    final Map<String, Spawns> npcs;
    final Map<String, ItemSources> items;
    /** The names the suggestions choose from, with their lower-case forms in the same order ({@link #suggestions}). */
    final String[][] npcChoices, itemChoices;
    /** Lower-case names the search finds: NPCs standing somewhere or with a way in ({@link #monsterName}). */
    final Set<String> monsterNames = new HashSet<>();
    /** The map version {@link #maps} is for; null for none. */
    final String mapVersion;
    /** Which map draws a place where maps overlap, by map id; null for none. */
    final Map<WorldPoint, Integer> maps;
    BaseMaps drawnFor;
    Map<WorldPoint, BaseMap> drawn = emptyMap();

    WorldData(Map<String, Spawns> npcs, Map<String, ItemSources> items, List<Shop> shops, String[] npcNames,
        String[] itemNames, String mapVersion, Map<WorldPoint, Integer> maps) {
        this.npcs = npcs;
        this.items = items;
        this.shops = shops;
        npcChoices = choices(npcNames);
        itemChoices = choices(itemNames);
        monsterNames.addAll(Arrays.asList(npcChoices[1]));
        this.mapVersion = mapVersion;
        this.maps = maps;
    }

    /**
     * search2.json as the map server writes it, the rows checked there as the plugin once checked them; points as
     * {@code [x, y, plane]}. Null when it is not search2.json in {@link #FORMAT}.
     */
    static WorldData parse(Gson gson, byte[] file) {
        try {
            var read = gson.newBuilder().registerTypeAdapter(WorldPoint.class, (JsonDeserializer<WorldPoint>) (e, t, c) -> {
                var at = e.getAsJsonArray();
                return new WorldPoint(at.get(0).getAsInt(), at.get(1).getAsInt(), at.get(2).getAsInt());
            }).create().fromJson(MapServer.text(file), Read.class);
            return read.format != FORMAT ? null : new WorldData(read.npcs.stream().collect(toMap(n -> key(n.page), n -> n,
                (a, b) -> b)), read.items.stream().collect(toMap(i -> key(i.page), i -> i, (a, b) -> b)),
                unmodifiableList(read.shops), read.npcNames, read.itemNames, read.map, read.maps == null ? emptyMap() : read.maps);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** search2.json's members. */
    static final class Read {
        int format;
        String map;
        List<Spawns> npcs;
        List<ItemSources> items;
        List<Shop> shops;
        String[] npcNames, itemNames;
        /** Which map draws a place, by map id; null for none. */
        Map<WorldPoint, Integer> maps;
    }

    /**
     * Where NPCs of this name stand (whatever its case), close spots as one place with how many stand there, and what
     * they drop; null when none stands anywhere known, as the search finds only those.
     */
    Spawns spawns(String name) {
        return npcs.get(key(name));
    }

    /** Where this item comes from: the shops that sell it, where it lies, what drops it; null when nothing is known. */
    ItemSources item(String name) {
        return items.get(key(name));
    }

    /** The shop near {@code at}; null when none is known there. */
    Shop shop(WorldPoint at) {
        Shop best = null;
        for (Shop shop : shops) {
            if (shop.point.distanceTo(at) <= SHOP_RADIUS
                && (best == null || shop.point.distanceTo(at) < best.point.distanceTo(at))) {
                best = shop;
            }
        }
        return best;
    }

    /** What the shop near {@code at} sells, with how many it keeps; null when no shop is known there. */
    List<Ware> wares(WorldPoint at) {
        Shop shop = shop(at);
        return shop == null ? null : shop.wares;
    }

    /**
     * Which map draws each place where maps overlap, for these maps: none unless they are of the map version the
     * server worked it out for, and then the region table decides alone; a place mapped to null is drawn by none.
     */
    synchronized Map<WorldPoint, BaseMap> drawnBy(BaseMaps baseMaps) {
        if (baseMaps != drawnFor) {
            drawnFor = baseMaps;
            drawn = new HashMap<>();
            if (baseMaps != null && mapVersion != null && mapVersion.equals(baseMaps.version)) {
                maps.forEach((point, id) -> {
                    var map = id == null ? null : baseMaps.byId(id);
                    if (id == null || map != null) {
                        drawn.put(point, map);
                    }
                });
            }
        }
        return drawn;
    }

    /**
     * A map label that names a monster or NPC the search finds ("Abyssal demons", "The Nightmare"): the monster search
     * shows where they are, so the place search need not list the label as a place too.
     */
    boolean monsterName(String label) {
        String name = label.trim().toLowerCase(Locale.ROOT);
        return monsterNames.contains(name) || name.endsWith("s") && monsterNames.contains(name.substring(0,
            name.length() - 1));
    }

    /** NPC names starting with or containing {@code typed}, starts first. */
    List<String> npcSuggestions(String typed, int limit) {
        return suggestions(npcChoices, typed, limit);
    }

    /** Names of items some shop sells, that lie somewhere or that something drops, likewise. */
    List<String> itemSuggestions(String typed, int limit) {
        return suggestions(itemChoices, typed, limit);
    }

    /** The names as the server orders them (shortest first, then alphabetically), with their lower-case forms. */
    static String[][] choices(String[] names) {
        return new String[][]{names, Arrays.stream(names).map(n -> n.toLowerCase(Locale.ROOT)).toArray(String[]::new)};
    }

    /** Of {@link #choices}, those starting with {@code typed}, then those holding it: one pass, nothing sorted. */
    static List<String> suggestions(String[][] choices, String typed, int limit) {
        String t = typed.trim().toLowerCase(Locale.ROOT);
        var starts = new ArrayList<String>();
        var holds = new ArrayList<String>();
        for (int i = 0; i < choices[0].length && starts.size() < limit; i++) {
            if (choices[1][i].startsWith(t)) {
                starts.add(choices[0][i]);
            } else if (holds.size() < limit && choices[1][i].contains(t)) {
                holds.add(choices[0][i]);
            }
        }
        starts.addAll(holds);
        return starts.subList(0, Math.min(limit, starts.size()));
    }

    /** A name as the search compares names: letter by letter without case, as {@code equalsIgnoreCase} does. */
    static String key(String name) {
        var letters = name.trim().toCharArray();
        for (int i = 0; i < letters.length; i++) {
            letters[i] = Character.toLowerCase(Character.toUpperCase(letters[i]));
        }
        return new String(letters);
    }
}
