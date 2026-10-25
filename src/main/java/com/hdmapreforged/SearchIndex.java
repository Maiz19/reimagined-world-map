package com.hdmapreforged;

import lombok.*;
import java.util.*;
import java.util.function.*;
import java.util.regex.*;
import net.runelite.api.coords.*;

/**
 * The place search: every typed word must fit the name or its place, in any order, a small typo forgiven. Its hits come
 * in a map version's places.json (made by the test sources' SearchIndexBuilder).
 */
@RequiredArgsConstructor
final class SearchIndex {
    @RequiredArgsConstructor
    enum Type {
        PLACE(0),
        MAP(1),
        /** "Shark fishing spots". */
        KIND(1),
        ICON(2),
        /** "Shark fishing spots – Catherby". */
        KIND_PLACE(2),
        GAME_ICON(3);

        final int rank;
    }

    static final class Hit {
        final Type type;
        final String label;
        final String[] words;
        final String joined;
        final String[] placeWords;
        /** A {@link MapData.Place}, {@link BaseMap}, {@link Poi} or {@link KindIndex.Kind}. */
        final Object target;
        final WorldPoint point;
        final PoiType icon;
        /** Kingdoms, seas and small places come after towns of the same name (the builder's {@code later}). */
        double later;

        Hit(Type type, String label, String words, String place, Object target, WorldPoint point, PoiType icon) {
            this.type = type;
            this.label = label;
            this.words = words(words);
            this.joined = String.join(" ", this.words);
            this.placeWords = place == null ? new String[0] : words(place);
            this.target = target;
            this.point = point;
            this.icon = icon;
        }
    }

    final List<Hit> hits;

    /**
     * A game label or a map named after monsters ("Abyssal demons", the map "Nex"): found as the monsters, whose places
     * say where to go, not as a place or map of their own.
     */
    static boolean namesMonsters(Hit hit, WorldData world) {
        switch (hit.type) {
            case PLACE:
                var place = (MapData.Place) hit.target;
                return "place".equals(place.kind) && world.monsterName(place.name);
            case MAP:
                return world.monsterName(((BaseMap) hit.target).name);
            default:
                return false;
        }
    }

    List<Hit> find(String query, int limit, Predicate<Hit> allowed) {
        var typed = words(query);
        if (typed.length == 0) {
            return Collections.emptyList();
        }
        String whole = String.join(" ", typed);
        var scores = new HashMap<Hit, Double>();
        var found = new ArrayList<Hit>();
        for (Hit hit : hits) {
            double score = score(hit, typed, whole);
            if (!Double.isNaN(score) && allowed.test(hit)) {
                scores.put(hit, score);
                found.add(hit);
            }
        }
        // Stable: equally good hits keep the index's order.
        found.sort(Comparator.comparingDouble(scores::get));
        var best = new ArrayList<Hit>();
        var labels = new HashSet<String>();
        for (Hit hit : found) {
            if (labels.add(hit.type == Type.MAP ? hit.label : hit.label.toLowerCase(Locale.ROOT)) && best.size() < limit) {
                best.add(hit);
            }
        }
        return best;
    }

    /**
     * Lower is better, NaN when a word fits nowhere. A kind at a place needs words fitting both ("shark catherby"), so a
     * town's name alone does not list everything in it.
     */
    static double score(Hit hit, String[] typed, String whole) {
        double score = 0;
        boolean ownWord = false, placeWord = false;
        for (String word : typed) {
            int own = best(word, hit.words), place = best(word, hit.placeWords);
            if (own < 0 && place < 0) {
                return Double.NaN;
            }
            if (own >= 0 && (place < 0 || own <= place)) {
                ownWord = true;
                score += own;
            } else {
                placeWord = true;
                score += place + 0.5;
            }
        }
        if (!ownWord || hit.type == Type.KIND_PLACE && !placeWord) {
            return Double.NaN;
        }
        String name = hit.joined;
        // A kind named as typed ("bank": Banks, nearest first) before a place of that name.
        if (hit.type == Type.KIND && (name.equals(whole) || name.equals(whole + "s"))) {
            score -= 5;
        } else if (name.equals(whole)) {
            score -= 3;
        } else if (name.startsWith(whole)) {
            score -= 2;
        }
        // Fewer words of its own and of its place first: "Catherby" before "Catherby Bay" for "catherby".
        return score + hit.type.rank * 0.6 + (hit.words.length + hit.placeWords.length) * 0.05 + hit.later;
    }

    /** 0 same word, 1 start of one, 2 inside one (3+ letters), 3 one letter off (5+ letters), -1 none. */
    static int best(String typed, String[] words) {
        int best = -1;
        for (String word : words) {
            int fit = word.equals(typed) ? 0 : word.startsWith(typed) ? 1
                : typed.length() >= 3 && word.contains(typed) ? 2
                : typed.length() >= 5 && nearlyStarts(word, typed) ? 3 : -1;
            if (fit >= 0 && (best < 0 || fit < best)) {
                best = fit;
            }
        }
        return best;
    }

    static boolean nearlyStarts(String word, String typed) {
        for (int length = typed.length() - 1; length <= typed.length() + 1; length++) {
            if (length <= word.length() && oneEdit(word, length, typed)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the first {@code length} letters of {@code word} are at most one letter changed, added or left out from
     * {@code typed}: checked letter by letter, with nothing made, as it runs for every word at every keystroke.
     */
    static boolean oneEdit(String word, int length, String typed) {
        int t = typed.length(), i = 0;
        while (i < length && i < t && word.charAt(i) == typed.charAt(i)) {
            i++;
        }
        if (i == length || i == t) {
            return Math.abs(length - t) <= 1;
        }
        return length == t ? word.regionMatches(i + 1, typed, i + 1, t - i - 1)
            : length == t + 1 ? word.regionMatches(i + 1, typed, i, t - i)
            : length == t - 1 && word.regionMatches(i, typed, i + 1, length - i);
    }

    static final Pattern NOT_WORD = Pattern.compile("[^a-z0-9]+");

    /** Lower-case words without punctuation: "Phosani's Nightmare" is "phosanis nightmare". */
    static String[] words(String text) {
        String clean = NOT_WORD.matcher(text.toLowerCase(Locale.ROOT).replace("'", "")).replaceAll(" ").trim();
        return clean.isEmpty() ? new String[0] : clean.split(" ");
    }
}
