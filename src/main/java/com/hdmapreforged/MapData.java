package com.hdmapreforged;

import static com.hdmapreforged.SearchIndex.*;
import com.google.gson.*;
import lombok.*;
import java.io.*;
import java.util.*;
import java.util.stream.*;
import net.runelite.api.coords.*;

/**
 * Everything the map shows and the planner uses for a map version, read from the map server's places.json, which the
 * test sources' MapDataBuilder makes: the game's world map icons and map links are the one source for places and ways
 * in; ours only where the game has none. Our entrances and passages are the planner's only.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class MapData {
    /**
     * Of places.json. An incompatible change raises it, and a new map version built with the new builder carries it:
     * a published version's files never change, and the plugin refuses another format.
     */
    static final int FORMAT = 2;

    @RequiredArgsConstructor
    static final class Place {
        final WorldPoint point;
        final String name;
        /**
         * How the game's map labels it: "region", "ocean", "settlement", "sea", "floor" or "place" (see
         * the map server's MapLabels); null for places only used for naming.
         */
        final String kind;
    }

    /** One of the game's own map icons (drawn by {@link MapIconLayer} from the game's sprites) with what it stands for. */
    static final class Icon {
        final WorldPoint location;
        /** Differs from {@link #location} where the map draws a place elsewhere (the Keldagrim tunnel). */
        final WorldPoint drawn;
        final BaseMap map;
        /** Our own place at the same spot ({@link #own}), else its own. */
        final Poi poi;
        final boolean own;
        /** The game's map element, or -1. */
        final int element;
        /** On a floor above the ground, which the map draws flat on the ground floor as the game's map does (Keldagrim). */
        final boolean flat;
        /** The name of its key on the game's world map ("Shipwright"), or null. */
        final String kind;
        /** What the Icons panel shows and hides it by: its type's name, else its key's ("Fairy ring (AIQ)": "Fairy ring"). */
        final String toggle;
        final Layer group;

        Icon(WorldPoint location, BaseMap map, Poi poi, boolean own, int element, String kind) {
            this.location = location;
            drawn = MapView.shownOn(map, location);
            this.map = map;
            this.poi = poi;
            this.own = own;
            this.element = element;
            flat = map != null && drawn != null && drawn.getPlane() > 0
                && drawn.getPlane() < WorldMapMoves.floorsDrawnFlat(map.id, drawn.getX(), drawn.getY());
            this.kind = kind;
            var type = poi.type;
            String name = kind == null || type.layer != Layer.DUNGEONS && type != PoiType.SHOP && type != PoiType.GAME_ICON
                ? type.displayName : kind.replaceFirst(" \\(.*\\)$", "");
            var row = PoiType.key(name);
            toggle = row == null || row[1].isEmpty() ? name : row[1];
            var listed = row == null ? null : value(Layer.class, row[2]);
            group = listed == null ? type.layer : listed;
        }
    }

    /** In order of layers, with the planner's passages among them. */
    final List<Poi> pois;
    /** Of {@link #pois}, those not drawn. */
    final Set<Poi> hidden;
    final List<Place> labels;
    final List<Icon> icons;
    /** The game's icons with no icon of ours on them, which the search finds too. */
    final List<Poi> gameIcons;
    /** Every place of a kind, for the search and for custom routes' "nearest of a kind". */
    final KindIndex kinds;
    final SearchIndex search;

    /**
     * Of {@code places} of {@code kind} (null: any), the one nearest {@code point} within {@code radius}, another floor
     * counting {@code floorCost} tiles more (negative: none on another floor); the first of equals.
     */
    static Place nearest(Collection<Place> places, WorldPoint point, String kind, int radius, int floorCost) {
        Place best = null;
        long bestDistance = radius + 1L;
        for (Place place : places) {
            int floor = place.point.getPlane() == point.getPlane() ? 0 : floorCost;
            int d = place.point.distanceTo2D(point) + floor;
            // The kind last: comparing words is slower, and only needed for a nearer place.
            if (floor >= 0 && d < bestDistance && (kind == null || kind.equals(place.kind))) {
                best = place;
                bestDistance = d;
            }
        }
        return best;
    }

    static final int TOWN_RADIUS = 120;
    /** In a town this near the town's name is the place's... */
    static final int IN_TOWN = 50;
    /** ...unless over this far, with a landmark of its own this near (the Grand Exchange's bank, the Cooks' Guild's). */
    static final int TOWN_EDGE = 45, OWN_PLACE = 15;
    /** A guild's grounds are wider: the Warriors' Guild's bank is 22 tiles from its label. */
    static final int OWN_GUILD = 22;
    /** Out of town a landmark of the map this near names a place (Castle Wars' bank, not Gu'Tanoth's). */
    static final int LANDMARK_RADIUS = 40;
    /** A one-word label this near a town's is a part of it (its market, its park, its palace), not a name to go by. */
    static final int DISTRICT = 50;
    /** Out of reach of towns and landmarks, any label this near names a place (Lunar Isle, Mos Le'Harmless). */
    static final int NEAR_LABEL = 60;

    /**
     * The name a place goes by: its town's when in one, unless at a landmark of its own on the town's edge; else the
     * nearest of a town (counting as half as far) and a landmark of the map's own labels (Castle Wars, the Farming
     * Guild); else any label near (another floor counting 4 tiles more). A town's parts are no landmarks. Its own loop,
     * not {@link #nearest}: the search index names thousands of places with it, and a loop shared with other kinds of
     * lists ran about twice as slow (the JIT no longer inlines the iteration).
     */
    static String settlement(List<Place> places, WorldPoint at) {
        var named = named(places, at);
        return named == null ? null : named.name;
    }

    /** The label {@link #settlement} names a place by, or null (no town within reach, no label near). */
    static Place named(List<Place> places, WorldPoint at) {
        Place town = null, best = null, own = null;
        int townDistance = TOWN_RADIUS + 1, ownDistance = OWN_GUILD + 1;
        double bestScore = TOWN_RADIUS / 2.0 + 1;
        for (Place place : places) {
            int d = place.point.distanceTo2D(at);
            boolean isTown = "settlement".equals(place.kind);
            if (isTown && d < townDistance) {
                town = place;
                townDistance = d;
            }
            // A town's part (a one-word label near a town's) is no landmark.
            if (!isTown && (!"place".equals(place.kind) || d > LANDMARK_RADIUS || place.name.indexOf(' ') < 0
                && nearest(places, place.point, "settlement", DISTRICT, 0) != null)) {
                continue;
            }
            if (!isTown && d <= (place.name.endsWith(" Guild") ? OWN_GUILD : OWN_PLACE) && d < ownDistance) {
                own = place;
                ownDistance = d;
            }
            double score = isTown ? d / 2.0 : d;
            if (score < bestScore) {
                best = place;
                bestScore = score;
            }
        }
        if (townDistance <= IN_TOWN) {
            return townDistance > TOWN_EDGE && own != null ? own : town;
        }
        return best != null ? best : nearest(places, at, null, NEAR_LABEL, 4);
    }

    /**
     * A map version's places.json, with the same version's map list and region table: what the plugin shows and plans
     * with. Unknown keys are ignored; a place of an unknown type or malformed is left out, and an icon or a kind's
     * entry standing for it. Data thread.
     *
     * @throws IOException not readable, or not this version's places in this format
     */
    static MapData read(Gson gson, Reader json, BaseMaps maps, String version) throws IOException {
        PlacesFile file;
        try {
            file = gson.fromJson(json, PlacesFile.class);
        } catch (RuntimeException e) {
            throw new IOException("Unreadable places of map version " + version, e);
        }
        if (file == null || file.format != FORMAT || !version.equals(file.version)) {
            throw new IOException("Not the places of map version " + version + " in format " + FORMAT);
        }
        Reading reading = new Reading(maps);
        // As listed, null where left out: an icon of ours gives its place's index in it.
        var listed = new ArrayList<Poi>();
        var pois = new ArrayList<Poi>();
        Set<Poi> hidden = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var place : list(file.pois)) {
            var poi = reading.poi(place, 0);
            listed.add(poi);
            if (poi != null) {
                pois.add(poi);
                if (Boolean.TRUE.equals(place.hidden)) {
                    hidden.add(poi);
                }
            }
        }
        Map<Integer, String> keys = file.keys == null ? Collections.emptyMap() : file.keys;
        var icons = new ArrayList<Icon>();
        for (var icon : list(file.icons)) {
            var at = point(icon.at);
            var poi = icon.own == null ? reading.poi(icon.poi, 0) : item(listed, icon.own);
            if (at != null && poi != null) {
                icons.add(new Icon(at, reading.map(icon.map), poi, icon.own != null, icon.element,
                    keys.get(icon.element)));
            }
        }
        // Labels and kinds as listed too, null where left out: a search hit gives its target's index.
        var listedLabels = new ArrayList<Place>();
        for (var label : list(file.labels)) {
            var at = point(label.at);
            listedLabels.add(at == null || label.name == null ? null : new Place(at, label.name, label.kind));
        }
        // The kinds' places as numbered: the places read, each with what it stands for, then the spots.
        var places = new ArrayList<KindIndex.Entry>();
        for (var poi : reading.numbered) {
            places.add(poi == null ? null : new KindIndex.Entry(poi.name, poi.location, poi.note, poi));
        }
        for (var spot : list(file.spots)) {
            var at = point(spot.at);
            places.add(at == null || spot.name == null ? null : new KindIndex.Entry(spot.name, at, spot.detail, null));
        }
        var listedKinds = new ArrayList<KindIndex.Kind>();
        for (var written : list(file.kinds)) {
            var kind = new KindIndex.Kind(written.label, value(PoiType.class, written.type), written.page);
            for (int i : written.places == null ? new int[0] : written.places) {
                var place = item(places, i);
                if (place != null) {
                    kind.entries.add(place);
                }
            }
            listedKinds.add(written.label == null || kind.entries.isEmpty() ? null : kind);
        }
        var hits = new ArrayList<Hit>();
        for (var hit : list(file.hits)) {
            var type = value(Type.class, hit.type);
            Object target = type == null ? null : type == Type.MAP ? maps.byId(hit.target)
                : item(type == Type.PLACE ? listedLabels : type == Type.KIND
                || type == Type.KIND_PLACE ? listedKinds : reading.numbered, hit.target);
            if (target != null && hit.label != null) {
                var made = new Hit(type, hit.label, hit.words == null ? hit.label : hit.words,
                    hit.place, target, point(hit.at), value(PoiType.class, hit.icon));
                made.later = hit.later == null ? 0 : hit.later;
                hits.add(made);
            }
        }
        var gameIcons = icons.stream().filter(icon -> !icon.own).map(icon -> icon.poi).distinct()
            .collect(Collectors.toList());
        return new MapData(pois, hidden, nonNull(listedLabels), icons, gameIcons, new KindIndex(nonNull(listedKinds)),
            new SearchIndex(hits));
    }

    /** Reading one file: its version's maps, and one Needs for equal requirements (a few hundred in thousands). */
    @RequiredArgsConstructor
    static final class Reading {
        final BaseMaps maps;
        final Map<Needs, Needs> needs = new HashMap<>();
        /** Listed places and those nearby, then the game's icons' own, in the file's order: null where left out. */
        final List<Poi> numbered = new ArrayList<>();

        /** A map id the list lacks reads as none. */
        BaseMap map(Integer id) {
            return id == null ? null : maps.byId(id);
        }

        /** Null when malformed. */
        Needs needs(String[] raw) {
            if (raw == null) {
                return Needs.NONE;
            }
            if (raw.length != 4) {
                return null;
            }
            Needs read = new Needs(text(raw[0]), text(raw[1]), text(raw[2]), text(raw[3]));
            return read.isEmpty() ? Needs.NONE : needs.computeIfAbsent(read, n -> n);
        }

        /** {@code depth}: 0 for a listed place (which may stand for places nearby), 1 nearby, 2 a stacked member. */
        Poi poi(PoiJson place, int depth) {
            var type = place == null ? null : value(PoiType.class, place.type);
            var at = type == null ? null : point(place.at);
            Needs needs = at == null ? null : needs(place.needs);
            // Older map versions name the Seers option of Camelot Teleport "Bank": read as a bank step.
            String name = "Camelot Teleport: Bank".equals(place.name) ? "Camelot Teleport: Seers" : place.name;
            var poi = needs == null || name == null ? null : new Poi(type, name, at, map(place.map),
                place.group, needs, place.wiki, map(place.target), place.note);
            if (depth < 2) {
                numbered.add(poi);
            }
            var drawn = poi == null || place.drawn == null ? null : point(place.drawn);
            if (drawn != null) {
                poi.drawnAt = drawn;
            }
            if (poi != null && place.picture != null) {
                poi.picture = place.picture;
            }
            if (poi == null) {
                // The places nearby are left out with it, keeping their numbers.
                numbered.addAll(Collections.nCopies(depth == 0 && place != null ? list(place.nearby).size() : 0, null));
                return null;
            }
            for (var link : list(place.links)) {
                var to = point(link.at);
                Needs linkNeeds = needs(link.needs);
                if (to != null && linkNeeds != null && link.name != null) {
                    poi.links.add(new Poi.Link(link.name, to, map(link.map), linkNeeds));
                }
            }
            for (var member : depth < 2 ? list(place.members) : Collections.<PoiJson>emptyList()) {
                var read = poi(member, 2);
                if (read != null) {
                    poi.members.add(read);
                }
            }
            for (var near : depth == 0 ? list(place.nearby) : Collections.<PoiJson>emptyList()) {
                var read = poi(near, 1);
                if (read != null) {
                    poi.nearby.add(read);
                }
            }
            return poi;
        }
    }

    static <E extends Enum<E>> E value(Class<E> type, String name) {
        try {
            return name == null ? null : Enum.valueOf(type, name);
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }

    static <T> T item(List<? extends T> list, int i) {
        return i >= 0 && i < list.size() ? list.get(i) : null;
    }

    static <T> List<T> nonNull(List<T> list) {
        return list.stream().filter(Objects::nonNull).collect(Collectors.toUnmodifiableList());
    }

    static WorldPoint point(int[] at) {
        return at != null && at.length == 3 ? new WorldPoint(at[0], at[1], at[2]) : null;
    }

    static String text(String raw) {
        return raw == null ? "" : raw;
    }

    static <T> List<T> list(List<T> list) {
        return list == null ? Collections.emptyList() : list;
    }

    /**
     * A map version's places.json (format 2) as {@link #read} binds it and the builder (test sources) writes it, one
     * class for both. Points are [x, y, plane]; map ids those of the version's maps.json. Absent: null, false or none.
     */
    static final class PlacesFile {
        int format;
        String version;
        List<String> credits;
        /** In drawing order, the planner's passages among them. */
        List<PoiJson> pois;
        List<IconJson> icons;
        List<LabelJson> labels;
        /** Map element id to the name of its key on the game's world map. */
        Map<Integer, String> keys;
        /** RuneLite's skilling spots that are places of a kind. */
        List<SpotJson> spots;
        List<KindJson> kinds;
        List<HitJson> hits;
    }

    static final class PoiJson {
        String type, name;
        int[] at;
        /** Where the map draws it, when not at {@code at} (an upper floor's teleport drawn on the ground floor). */
        int[] drawn;
        Integer map;
        String group;
        /** Skills, items, quests, varbits, raw as the travel tables write them. */
        String[] needs;
        String wiki;
        Integer target;
        String note;
        /** The game's picture for it ({@link Poi#picture}). */
        Integer picture;
        /** Not drawn (top-level places only). */
        Boolean hidden;
        List<LinkJson> links;
        /** Stacked teleports. */
        List<PoiJson> members;
        /** Only on a top-level place. */
        List<PoiJson> nearby;
    }

    static final class LinkJson {
        String name;
        int[] at;
        Integer map;
        String[] needs;
    }

    static final class IconJson {
        int[] at;
        Integer map;
        int element = -1;
        /** The index in pois of the place the icon stands for; else {@link #poi}, its own place. */
        Integer own;
        PoiJson poi;
    }

    static final class LabelJson {
        int[] at;
        String name, kind;
    }

    static final class SpotJson {
        int[] at;
        String name, detail;
    }

    /** Every place of one kind ({@link KindIndex}). */
    static final class KindJson {
        String label, type, page;
        /**
         * Its places in order, numbered as read: each of {@link PlacesFile#pois} followed by its places nearby, then
         * the places of the icons that are not ours, then the spots.
         */
        int[] places;
    }

    /** One of the place search's hits ({@link SearchIndex.Hit}). */
    static final class HitJson {
        String type, label;
        /** The words it is found by, when not its label's. */
        String words;
        /** The words of where it is, if any. */
        String place;
        /**
         * The map id of a MAP, else an index: in {@link PlacesFile#labels} for a PLACE, {@link PlacesFile#kinds} for a
         * KIND or KIND_PLACE, the places as {@link KindJson#places} numbers them for an ICON or GAME_ICON.
         */
        int target;
        int[] at;
        String icon;
        Double later;
    }
}
