package com.hdmapreforged;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.regex.*;
import java.util.stream.*;
import lombok.*;

/** Turns requirement columns ({@link Needs}) into readable lines. */
final class Requirements {
    static final int MAX_ALTERNATIVES = 3;
    static final Pattern SPACES = Pattern.compile("\\s+");
    /** {@link #skillsAndQuests} per requirement: a route request asks for thousands, of a few hundred kinds. */
    static final Map<Needs, List<Line>> CHECKABLE = new ConcurrentHashMap<>();

    @RequiredArgsConstructor
    static final class Line {
        final String text, skill;
        final int level;
        final String quest;
    }

    /** Lines such as {@code "25 Magic"}, {@code "3 × Air rune"} and {@code "Quest: Sea Slug"}. */
    static List<Line> describe(Needs needs, IntFunction<String> itemNames) {
        var lines = new ArrayList<Line>();
        for (String skill : needs.skills.split(";")) {
            var parts = SPACES.split(skill.trim(), 2);
            if (parts.length == 2 && isNumber(parts[0])) {
                String name = parts[1].trim();
                String shown = name.equals("Quest") ? " quest points"
                    : name.contains("+") ? " " + name.replace("+", " and ") + " together" : " " + name;
                lines.add(new Line(parts[0] + shown, name, Integer.parseInt(parts[0]), null));
            } else if (!skill.trim().isEmpty()) {
                lines.add(new Line(skill.trim(), null, 0, null));
            }
        }
        items(needs.items, itemNames).forEach(item -> lines.add(new Line(item, null, 0, null)));
        for (String quest : needs.quests.split(";")) {
            if (!quest.trim().isEmpty()) {
                lines.add(new Line("Quest: " + quest.trim(), null, 0, quest.trim()));
            }
        }
        return lines;
    }

    /** The lines {@link Unlocks} can tick off: skill levels and quests. */
    static List<Line> skillsAndQuests(Needs needs) {
        var lines = CHECKABLE.get(needs);
        if (lines == null) {
            lines = Collections.unmodifiableList(describe(new Needs(needs.skills, "", needs.quests, ""), id -> null));
            if (CHECKABLE.size() < Unlocks.CACHED) {
                CHECKABLE.put(needs, lines);
            }
        }
        return lines;
    }

    /** {@code "556=3&&554=1"}: all needed; {@code "1706=1||1708=1"}: alternatives. {@code itemNames} may return null. */
    static List<String> items(String column, IntFunction<String> itemNames) {
        // A blank column has no alternatives: no lines.
        var lines = new ArrayList<String>();
        for (String all : ItemSnapshot.ALL.split(column)) {
            var alternatives = new LinkedHashSet<String>();
            int quantity = 1;
            for (String alternative : ItemSnapshot.ANY.split(all)) {
                var parts = alternative.trim().split("=");
                if (parts[0].isEmpty()) {
                    continue;
                }
                if (parts.length > 1) {
                    try {
                        quantity = Integer.parseInt(parts[1].trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
                alternatives.add(itemName(parts[0].trim(), itemNames));
            }
            if (alternatives.isEmpty()) {
                continue;
            }
            var names = new ArrayList<String>(alternatives);
            String text = String.join(" or ", names.subList(0, Math.min(MAX_ALTERNATIVES, names.size())));
            if (names.size() > MAX_ALTERNATIVES) {
                text += " or others";
            }
            lines.add(quantity > 1 ? quantity + " × " + text : text);
        }
        return lines;
    }

    static String itemName(String token, IntFunction<String> itemNames) {
        String name = isNumber(token) ? itemNames.apply(Integer.parseInt(token)) : null;
        // Charged variants such as "Amulet of glory(4)" collapse into one entry.
        return name == null ? "Item " + token : name.replaceFirst("\\s*\\(\\d+\\)$", "");
    }

    static List<Integer> itemIds(String column) {
        return Arrays.stream(column.split("&&|\\|\\|")).map(token -> token.split("=")[0].trim())
            .filter(Requirements::isNumber).map(Integer::parseInt).collect(Collectors.toList());
    }

    /** Each item a column names, with the most of it any part asks for ("563=3&&556" adds 563: 3, 556: 1), into {@code most}. */
    static void itemAmounts(String column, Map<Integer, Long> most) {
        for (String token : column.split("&&|\\|\\|")) {
            String[] parts = token.split("=");
            if (isNumber(parts[0].trim())) {
                long n = parts.length > 1 && parts[1].trim().matches("\\d{1,12}") ? Long.parseLong(parts[1].trim()) : 1;
                most.merge(Integer.parseInt(parts[0].trim()), n, Math::max);
            }
        }
    }

    static boolean isNumber(String text) {
        return !text.isEmpty() && text.length() <= 9 && text.chars().allMatch(Character::isDigit);
    }
}
