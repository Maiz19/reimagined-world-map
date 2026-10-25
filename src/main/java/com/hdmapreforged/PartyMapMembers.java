package com.hdmapreforged;

import java.awt.image.*;
import java.util.*;
import java.util.function.*;
import java.util.stream.*;
import lombok.*;
import net.runelite.api.coords.*;

/** Last known location of each party member: written from network threads, read on Swing, so all synchronized. */
final class PartyMapMembers {
    /** Heartbeats come every 30 s; after this without news the marker fades. */
    static final long FRESH_MILLIS = 75_000, FADE_MILLIS = 5 * 60_000;
    /** After this without news the member is left off the map (probably crashed). */
    static final long GONE_MILLIS = 30 * 60_000;
    static final float MIN_ALPHA = 0.4f;
    /** The core Party plugin's location messages are coarser; ours win while they keep coming. */
    static final long OWN_MESSAGE_PRIORITY_MILLIS = 60_000;

    @RequiredArgsConstructor
    static final class Marker {
        final long id;
        final String name;
        final WorldPoint point;
        final int world;
        final BufferedImage avatar;
        final long ageMillis;
        final float alpha;
        /** In a house, at its portal. */
        final boolean house;

        boolean stale() {
            return ageMillis > FRESH_MILLIS;
        }
    }

    static final class Member {
        String name;
        WorldPoint point;
        boolean house;
        int world;
        BufferedImage avatar;
        long updated, ownUpdated = Long.MIN_VALUE / 2;
    }

    final Map<Long, Member> members = new HashMap<>();
    final Map<Long, Gear> gear = new HashMap<>();

    static final class Gear {
        final int[] inventory, quantities, equipment, levels, boosted;
        /** Experience per skill (a gear made in a test may leave it null). */
        final int[] experience;
        /** 0 to 100. */
        final int run, special;
        final long at;

        /** A whole message's, or a message's changes onto {@code before}. */
        Gear(HdMapPartyState message, Gear before, long at) {
            int[][] parts = message.parts(message.whole() || before == null ? null : new int[][]{before.inventory,
                before.quantities, before.equipment, before.levels, before.boosted, before.experience});
            inventory = parts[0];
            quantities = parts[1];
            equipment = parts[2];
            levels = parts[3];
            boosted = parts[4];
            experience = parts[5];
            run = message.runEnergy();
            special = message.specialAttack();
            this.at = at;
        }
    }

    @RequiredArgsConstructor
    static final class Drop {
        final int skill, amount;
        final long at;
    }

    static final long DROP_MS = 2200;
    static final int MAX_DROPS = 16;
    final Map<Long, List<Drop>> drops = new HashMap<>();

    /** What a member got since first seen this session: experience by skill, the drops they shared. */
    static final class Session {
        final long[] xp = new long[HdMapPartyState.SKILLS];
        long value;
        /** The most valuable drops, most first; the latest, newest first. */
        final List<Loot> top = new ArrayList<>(), latest = new ArrayList<>();

        /** The skills most gained, most first, at most {@code n}. */
        int[] best(int n) {
            return IntStream.range(0, xp.length).filter(k -> xp[k] > 0).boxed()
                .sorted(Comparator.comparingLong(k -> -xp[k])).limit(n).mapToInt(Integer::intValue).toArray();
        }

        Session copy() {
            var copy = new Session();
            System.arraycopy(xp, 0, copy.xp, 0, xp.length);
            copy.value = value;
            copy.top.addAll(top);
            copy.latest.addAll(latest);
            return copy;
        }
    }

    /** Drops kept in a session's lists. */
    static final int SESSION_DROPS = 5;
    final Map<Long, Session> sessions = new HashMap<>();

    /** What a member got this session; null while nothing yet. */
    synchronized Session session(long id) {
        var session = sessions.get(id);
        return session == null || session.top.isEmpty() && Arrays.stream(session.xp).allMatch(x -> x == 0) ? null
            : session.copy();
    }

    /** Each member's experience last heard (each state tells it), for its drops. */
    final Map<Long, int[]> experience = new HashMap<>();

    /**
     * A member's state: whole, or changes onto the one had (none had: the next whole one comes within a minute); a
     * fresh one starts their experience anew (no burst of gains from another account), an unshared one drops it all.
     */
    synchronized void state(long id, HdMapPartyState message, long at) {
        Gear before = gear.get(id);
        if (message.fresh() || message.isUnshared()) {
            experience.remove(id);
        }
        if (message.isUnshared()) {
            gear.remove(id);
        } else if (message.whole() || before != null) {
            gear(id, new Gear(message, before, at));
        }
    }

    synchronized void gear(long id, Gear shared) {
        gear.put(id, shared);
        experience(id, shared.experience, shared.at);
    }

    /** What a member's experience became: the gain since last heard rises as drops and adds to the session. */
    synchronized void experience(long id, int[] now, long at) {
        int[] before = now == null ? null : experience.put(id, now);
        if (before != null) {
            var list = drops.computeIfAbsent(id, k -> new ArrayList<>());
            var session = sessions.computeIfAbsent(id, k -> new Session());
            for (int k = 0; k < Math.min(now.length, session.xp.length); k++) {
                int gained = now[k] - before[k];
                if (gained > 0 && gained < 5_000_000) {
                    list.add(new Drop(k, gained, at));
                    session.xp[k] += gained;
                }
            }
            // Kept short whoever reads it.
            list.removeIf(d -> at - d.at > DROP_MS);
            if (list.size() > MAX_DROPS) {
                list.subList(0, list.size() - MAX_DROPS).clear();
            }
        }
    }

    @RequiredArgsConstructor
    static final class Loot {
        final int item, quantity;
        final long value, at;
        /** An untradeable drop's name, else null. */
        final String name;
    }

    static final long LOOT_MS = 3500;
    /** Untradeables first (a pet, a clue: no price), then the most valuable; as the sender picks its few too. */
    static final Comparator<Loot> BEST = Comparator.comparing((Loot l) -> l.name == null).thenComparingLong(l -> -l.value);
    final Map<Long, List<Loot>> loot = new HashMap<>();

    synchronized void loot(long id, Loot drop) {
        var session = sessions.computeIfAbsent(id, k -> new Session());
        session.value += drop.value;
        session.latest.add(0, drop);
        // An untradeable seen before adds to it (Tokkul kill after kill is one entry); at most two of them stand first
        // (a pet, a clue: worth nothing in coins, they would drop off the list), then the most valuable.
        var same = drop.name == null ? null
            : session.top.stream().filter(l -> l.item == drop.item).findFirst().orElse(null);
        session.top.remove(same);
        session.top.add(same == null ? drop : new Loot(drop.item, same.quantity + drop.quantity, 0, drop.at, drop.name));
        session.top.sort(BEST);
        var ordered = new ArrayList<Loot>();
        session.top.stream().filter(l -> l.name != null).limit(2).forEach(ordered::add);
        session.top.stream().filter(l -> l.name == null).forEach(ordered::add);
        session.top.clear();
        session.top.addAll(ordered);
        for (var kept : List.of(session.latest, session.top)) {
            if (kept.size() > SESSION_DROPS) {
                kept.subList(SESSION_DROPS, kept.size()).clear();
            }
        }
        var list = loot.computeIfAbsent(id, k -> new ArrayList<>());
        list.removeIf(l -> drop.at - l.at > LOOT_MS);
        list.add(drop);
        // A big pile shows its best few.
        if (list.size() > 4) {
            list.sort(BEST);
            list.subList(4, list.size()).clear();
        }
    }

    synchronized List<Loot> loot(long id, long now) {
        return recent(loot.get(id), l -> now - l.at > LOOT_MS);
    }

    /** A copy without the old ones, which go. */
    static <T> List<T> recent(List<T> list, Predicate<T> old) {
        if (list == null) {
            return Collections.emptyList();
        }
        list.removeIf(old);
        return new ArrayList<>(list);
    }

    synchronized List<Drop> drops(long id, long now) {
        return recent(drops.get(id), d -> now - d.at > DROP_MS);
    }

    synchronized Gear gear(long id) {
        return gear.get(id);
    }

    /** A location from our own message; a null point means they left the game. */
    synchronized void update(long id, WorldPoint point, int world, String name, boolean house, long now) {
        Member member = members.computeIfAbsent(id, k -> new Member());
        // From a version that sent the house's room template (far off any map): its last spot outside, in a house.
        boolean template = point != null && HouseTracker.isTemplate(point.getX(), point.getY());
        member.point = template ? member.point : point;
        member.house = house || template;
        member.world = world;
        if (name != null) {
            member.name = name;
        }
        member.updated = now;
        member.ownUpdated = now;
    }

    /** A location from RuneLite's Party plugin, used only while no recent message of ours came. */
    synchronized boolean updateFromCore(long id, WorldPoint point, long now) {
        Member member = members.computeIfAbsent(id, k -> new Member());
        if (now - member.ownUpdated < OWN_MESSAGE_PRIORITY_MILLIS) {
            return false;
        }
        member.point = point;
        member.house = false;
        member.updated = now;
        return true;
    }

    /** From RuneLite's party data, when the member's own messages carry none. */
    synchronized void name(long id, String name) {
        Member member = members.get(id);
        name = clean(name);
        if (member != null && member.name == null && name != null) {
            member.name = name;
        }
    }

    /** Never HTML in a Swing label; null when nothing is left. */
    static String clean(String name) {
        return HdMapPartyLocation.cleaned(name, true);
    }

    synchronized void avatar(long id, BufferedImage avatar) {
        Member member = members.get(id);
        if (member != null) {
            member.avatar = avatar;
        }
    }

    synchronized void remove(long id) {
        List.of(members, gear, drops, loot, sessions, experience).forEach(m -> m.remove(id));
    }

    synchronized void clear() {
        List.of(members, gear, drops, loot, sessions, experience).forEach(Map::clear);
    }

    synchronized boolean isEmpty() {
        return members.isEmpty();
    }

    /** Oldest news first so the freshest end up on top. */
    synchronized List<Marker> markers(long now) {
        var markers = new ArrayList<Marker>();
        for (var entry : members.entrySet()) {
            Member member = entry.getValue();
            long age = Math.max(0, now - member.updated);
            if (member.point == null || age > GONE_MILLIS) {
                continue;
            }
            markers.add(new Marker(entry.getKey(), member.name, member.point, member.world, member.avatar, age, alpha(age),
                member.house));
        }
        markers.sort((a, b) -> Long.compare(b.ageMillis, a.ageMillis));
        return Collections.unmodifiableList(markers);
    }

    /** Full until FRESH_MILLIS, then fading linearly to MIN_ALPHA over FADE_MILLIS. */
    static float alpha(long ageMillis) {
        return ageMillis <= FRESH_MILLIS ? 1f
            : (float) (1 - Math.min(1, (ageMillis - FRESH_MILLIS) / (double) FADE_MILLIS) * (1 - MIN_ALPHA));
    }
}
