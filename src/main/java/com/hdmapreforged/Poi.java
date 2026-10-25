package com.hdmapreforged;

import lombok.*;
import java.util.*;
import net.runelite.api.coords.*;

/** An icon on the map. */
@RequiredArgsConstructor
final class Poi {
    @RequiredArgsConstructor
    static final class Link {
        final String label;
        final WorldPoint point;
        /** Null when no map draws it. */
        final BaseMap map;
        final Needs needs;
    }

    final PoiType type;
    final String name;
    final WorldPoint location;
    final BaseMap map;
    /** Highlighted together (such as all Ardougne cloak teleports); may be null. */
    final String group;
    final Needs needs;
    final String wikiQuery;
    /** Where an entrance or passage leads, or null. */
    final BaseMap target;
    final String note;
    final List<Link> links = new ArrayList<>();
    /**
     * Where the map draws it when not at {@link #location}: a teleport or transport on an upper floor of a building on
     * the surface is drawn on the ground floor (where its map icon stands), so it is seen on the map; routes go to its
     * location. Null: drawn at its location.
     */
    WorldPoint drawnAt;

    /** The game's picture the map draws it with: an item's id, a sprite's as {@code ~id}; 0 none (its type's icon). */
    int picture;

    /** What the Icons panel shows and hides it by: a teleport's group ("Amulet of glory"), else its type's name. */
    String toggle() {
        return type == PoiType.TELEPORT && group != null ? group : type.displayName;
    }

    /** Where the map draws it. */
    WorldPoint drawn() {
        return drawnAt != null ? drawnAt : location;
    }

    /** Teleports stacked on this icon; empty for a single one. */
    final List<Poi> members = new ArrayList<>();
    /** Other things at the same place this icon stands for. */
    final List<Poi> nearby = new ArrayList<>();

    List<Link> links() {
        return Collections.unmodifiableList(links);
    }

    List<Poi> members() {
        return members.isEmpty() ? Collections.singletonList(this) : Collections.unmodifiableList(members);
    }

    List<Poi> nearby() {
        return Collections.unmodifiableList(nearby);
    }

    /** Icons with the things they stand for. */
    static List<Poi> flatten(List<Poi> pois) {
        var all = new ArrayList<Poi>(pois.size() + 16);
        for (var poi : pois) {
            all.add(poi);
            all.addAll(poi.nearby);
        }
        return all;
    }

    /** A spot of the game on the water (a Barracuda Trial's start, salvage): sailed to, not walked to its shore. */
    boolean atSea() {
        return type == PoiType.SALVAGE || name.startsWith("Barracuda Trial");
    }

    boolean hasGroup(String group) {
        return memberOf(group) != null;
    }

    Poi memberOf(String group) {
        if (group == null) {
            return null;
        }
        for (var member : members()) {
            if (group.equals(member.group)) {
                return member;
            }
        }
        for (var other : nearby) {
            var member = other.memberOf(group);
            if (member != null) {
                return member;
            }
        }
        return null;
    }

    boolean isOn(BaseMap view) {
        return view != null && map != null && (view.id == BaseMap.FULL || map == view);
    }

    public String toString() {
        return name;
    }
}
