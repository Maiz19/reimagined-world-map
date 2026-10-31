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
class MapData {
    /**
     * Of route.zip's places.json (2: the released plugins' places.json beside it). An incompatible change raises it, and
     * a new map version built with the new builder carries it: a published version's files never change, and the plugin
     * refuses another format.
     */
    static final int FORMAT = 3;

    @RequiredArgsConstructor
    static class Place {
        final WorldPoint point;
        final String name;
        /**
         * How the game's map labels it: "region", "ocean", "settlement", "sea", "floor" or "place" (see
         * the map server's MapLabels); null for places only used for naming.
         */
        final String kind;
    }

    /**
     * One of the game's own map icons (drawn by {@link IconLayer} from the game's sprites) with what it stands for, as
     * places.json holds it: the builder works out where it is drawn, its toggle and group.
     */
    static class Icon {
        WorldPoint location;
        /** Differs from {@link #location} where the map draws a place elsewhere (the Keldagrim tunnel). */
        WorldPoint drawn;
        BaseMap map;
        /** Our own place at the same spot ({@link #own}, places.json's pois[{@link #of}]), else its own. */
        Poi poi;
        boolean own;
        int of = -1;
        /** The game's map element, or -1. */
        int element;
        /** On a floor above the ground, which the map draws flat on the ground floor as the game's map does (Keldagrim). */
        boolean flat;
        /** The name of its key on the game's world map ("Shipwright"), or null. */
        String kind;
        /** What the Icons panel shows and hides it by: its type's name, else its key's ("Fairy ring (AIQ)": "Fairy ring"). */
        String toggle;
        Layer group;
        /**
         * Our places it stands for besides its own (pois[{@link #covered}], worked out by the builder): passages the map
         * does not draw whose way in it is, ours beside it named by its key ("Shipwright Sornik" by "Shipwright"); not drawn.
         */
        final transient List<Poi> covers = new ArrayList<>();
        int[] covered = {};
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
     * A map version's places.json (in its route.zip), with the same version's map list and region table: what the
     * plugin shows and plans with, bound as it is. Unknown keys are ignored; a place of an unknown type is left out, and
     * an icon or a kind's entry standing for it. Data thread.
     *
     * @throws IOException not readable, or not this version's places in this format
     */
    static MapData read(Gson gson, Reader json, BaseMaps maps, String version) throws IOException {
        var needs = new HashMap<Needs, Needs>();
        PlacesFile file;
        try {
            file = gson.newBuilder().registerTypeAdapter(WorldPoint.class, WorldData.POINT)
                .registerTypeAdapter(BaseMap.class, (JsonDeserializer<BaseMap>) (e, t, c) -> maps.byId(e.getAsInt()))
                .registerTypeAdapter(Needs.class, (JsonDeserializer<Needs>) (e, t, c) -> {
                    String[] raw = c.deserialize(e, String[].class);
                    return needs.computeIfAbsent(new Needs(raw[0], raw[1], raw[2], raw[3]), n -> n);
                }).create().fromJson(json, PlacesFile.class);
        } catch (RuntimeException e) {
            throw new IOException("Unreadable places of map version " + version, e);
        }
        if (file == null || file.format != FORMAT || !version.equals(file.version)) {
            throw new IOException("Not the places of map version " + version + " in format " + FORMAT);
        }
        // As listed, null where left out; then numbered as kinds and hits number them: each with its places nearby,
        // then the game's icons' own.
        var listed = new ArrayList<Poi>();
        var numbered = new ArrayList<Poi>();
        for (var poi : file.pois) {
            listed.add(known(poi));
            numbered.add(known(poi));
            poi.nearby.forEach(near -> numbered.add(known(poi) == null ? null : known(near)));
            poi.nearby.removeIf(near -> known(near) == null);
            poi.members.removeIf(member -> known(member) == null);
        }
        var icons = new ArrayList<Icon>();
        for (var icon : file.icons) {
            if (icon.own) {
                icon.poi = item(listed, icon.of);
            } else {
                numbered.add(known(icon.poi));
            }
            for (int i : icon.covered) {
                icon.covers.add(item(listed, i));
            }
            icon.covers.removeIf(Objects::isNull);
            if (known(icon.poi) != null) {
                icons.add(icon);
            }
        }
        var places = new ArrayList<KindIndex.Entry>();
        var kinds = new ArrayList<KindIndex.Kind>();
        for (var poi : numbered) {
            places.add(poi == null ? null : new KindIndex.Entry(poi.name, poi.location, poi.note, poi));
        }
        places.addAll(file.spots);
        for (var kind : file.kinds) {
            for (int i : kind.places) {
                kind.entries.add(item(places, i));
            }
            kind.entries.removeIf(Objects::isNull);
            for (int i = 0; i < kind.groups.size(); i++) {
                var first = item(places, kind.firsts[i]);
                kind.groups.get(i).poi = first == null ? null : first.poi;
            }
            kinds.add(kind.entries.isEmpty() ? null : kind);
        }
        var hits = new ArrayList<Hit>();
        for (var hit : file.hits) {
            var type = value(Type.class, hit.type);
            Object target = type == null ? null : type == Type.MAP ? maps.byId(hit.target)
                : item(type == Type.PLACE ? file.labels : type == Type.KIND
                || type == Type.KIND_PLACE ? kinds : numbered, hit.target);
            if (target != null && hit.label != null) {
                var made = new Hit(type, hit.label, hit.words == null ? hit.label : hit.words,
                    hit.place, target, hit.at, value(PoiType.class, hit.icon));
                made.later = hit.later == null ? 0 : hit.later;
                hits.add(made);
            }
        }
        var pois = nonNull(listed);
        Set<Poi> hidden = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int i : file.hidden) {
            hidden.add(item(listed, i));
        }
        hidden.remove(null);
        var gameIcons = icons.stream().filter(icon -> !icon.own).map(icon -> icon.poi).distinct().collect(Collectors.toList());
        return new MapData(pois, hidden, file.labels, icons, gameIcons, new KindIndex(nonNull(kinds)), new SearchIndex(hits));
    }

    /** The place, or null for one of a type this plugin does not know. */
    static Poi known(Poi poi) {
        return poi == null || poi.type == null ? null : poi;
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

    /**
     * A map version's places.json (format 3, in its route.zip) as {@link #read} binds it and the builder (test sources)
     * writes it: the plugin's own classes, points as [x, y, plane], maps as their ids in the version's maps.json, needs as
     * [skills, items, quests, varbits].
     */
    static class PlacesFile {
        int format;
        String version;
        List<String> credits;
        /** In drawing order, the planner's passages among them. */
        List<Poi> pois = List.of();
        /** Of {@link #pois}, those not drawn. */
        int[] hidden = {};
        List<Icon> icons = List.of();
        List<Place> labels = List.of();
        /** RuneLite's skilling spots that are places of a kind. */
        List<KindIndex.Entry> spots = List.of();
        /**
         * Every place of one kind: its places in order ({@link KindIndex.Kind#places}), numbered as read: each of
         * {@link #pois} followed by its places nearby, then the places of the icons that are not ours, then the spots.
         */
        List<KindIndex.Kind> kinds = List.of();
        List<HitJson> hits = List.of();
    }

    /** One of the place search's hits ({@link SearchIndex.Hit}). */
    static class HitJson {
        String type, label;
        /** The words it is found by, when not its label's. */
        String words;
        /** The words of where it is, if any. */
        String place;
        /**
         * The map id of a MAP, else an index: in {@link PlacesFile#labels} for a PLACE, {@link PlacesFile#kinds} for a
         * KIND or KIND_PLACE, the places as {@link KindIndex.Kind#places} numbers them for an ICON or GAME_ICON.
         */
        int target;
        WorldPoint at;
        String icon;
        Double later;
    }
}
