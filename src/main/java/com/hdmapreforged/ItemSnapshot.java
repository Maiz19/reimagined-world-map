package com.hdmapreforged;

import lombok.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

/**
 * Items carried and runes a worn staff gives without limit: what a route may use. The bank only through a
 * {@link BankDetour} (setting), as {@link #plus}. Immutable.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
final class ItemSnapshot {
    /** Compiled once: a split on a pattern compiles it on every call, and requests check hundreds of columns. */
    static final Pattern ALL = Pattern.compile("&&");
    static final Pattern ANY = Pattern.compile("\\|\\|");
    static final int AIR = 556, WATER = 555, EARTH = 557, FIRE = 554;
    /** Combination runes (steam, mist, dust, smoke, mud, lava) by id, with the two runes each counts as. */
    static final RouteFiles.Table<Map<Integer, int[]>> COMBINATIONS = new RouteFiles.Table<>(() -> Pathfinder.list(
        "combination rune ids", Pathfinder::ints).stream().filter(c -> c.length > 2)
        .collect(Collectors.toMap(c -> c[0], c -> new int[]{c[1], c[2]}, (a, b) -> a)));

    static final ItemSnapshot NONE = new ItemSnapshot(Map.of(), Set.of());
    static final ItemSnapshot EVERYTHING = new ItemSnapshot(Map.of(), Set.of());

    final Map<Integer, Long> carried;
    final Set<Integer> unlimited;

    /** Whether {@code other} carries the same (an inventory that changed back and forth is no change). */
    boolean sameAs(ItemSnapshot other) {
        return other != null && carried.equals(other.carried);
    }

    /**
     * Whether {@code other} carries the same of these items as far as anything asks for them ({@code most}: the most of
     * each any way needs; a rune cast with enough left is no change), and the same staff's runes.
     */
    boolean sameFor(ItemSnapshot other, Map<Integer, Long> most) {
        return other != null && unlimited.equals(other.unlimited) && most.entrySet().stream().allMatch(e ->
            Math.min(carried.getOrDefault(e.getKey(), 0L), e.getValue())
                == Math.min(other.carried.getOrDefault(e.getKey(), 0L), e.getValue()));
    }

    static ItemSnapshot of(Map<Integer, Long> carried, Set<Integer> unlimited) {
        return new ItemSnapshot(Map.of(), Collections.unmodifiableSet(new HashSet<>(unlimited))).plus(carried);
    }

    /** These items and {@code more} (a bank's), with the same staff's runes. */
    ItemSnapshot plus(Map<Integer, Long> more) {
        var all = new HashMap<Integer, Long>(carried);
        more.forEach((id, n) -> add(all, id, n));
        return new ItemSnapshot(Collections.unmodifiableMap(all), unlimited);
    }

    static void add(Map<Integer, Long> into, int id, long quantity) {
        into.merge(id, quantity, Long::sum);
        var parts = COMBINATIONS.get().get(id);
        if (parts != null) {
            for (int part : parts) {
                into.merge(part, quantity, Long::sum);
            }
        }
    }

    static Set<Integer> runesFromWeapon(String name) {
        var runes = new HashSet<Integer>();
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (!n.contains("staff") && !n.contains("tome of") && !n.contains("wand") || n.contains("(empty)")
            || n.contains("uncharged")) {
            return runes;
        }
        // The words that give each rune.
        var words = Pathfinder.list("staff runes");
        int[] ids = {AIR, WATER, EARTH, FIRE};
        for (int i = 0; i < Math.min(ids.length, words.size()); i++) {
            if (Arrays.stream(words.get(i).split(" ")).anyMatch(w -> !w.isEmpty() && n.contains(w))) {
                runes.add(ids[i]);
            }
        }
        return runes;
    }

    /**
     * Whether what is carried meets a column like {@code "556=3&&563=1"}. A token that is no item id counts as met:
     * only a certainly missing item rules out.
     */
    boolean has(String column) {
        if (this == EVERYTHING || column == null || column.trim().isEmpty()) {
            return true;
        }
        for (String all : ALL.split(column)) {
            boolean any = false, judged = false;
            for (String alternative : ANY.split(all)) {
                var parts = alternative.trim().split("=");
                String token = parts[0].trim();
                if (token.isEmpty()) {
                    continue;
                }
                long quantity = 1;
                if (parts.length > 1) {
                    try {
                        quantity = Long.parseLong(parts[1].trim());
                    } catch (NumberFormatException e) {
                        // One then.
                    }
                }
                Integer id = token.length() <= 9 && token.chars().allMatch(Character::isDigit) ? Integer.valueOf(token)
                    : null;
                if (id == null) {
                    any = true;
                    continue;
                }
                judged = true;
                any |= unlimited.contains(id) || carried.getOrDefault(id, 0L) >= quantity;
            }
            if (judged && !any) {
                return false;
            }
        }
        return true;
    }
}
