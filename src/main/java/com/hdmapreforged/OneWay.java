package com.hdmapreforged;

import java.util.*;
import lombok.*;

/** One-way passages ({@code one_way.tsv}, e.g. a slide): a passage of any source going back between the areas is left out. */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
final class OneWay {
    @RequiredArgsConstructor
    static final class Rule {
        final int from, to, radius;
    }

    final List<Rule> rules;

    static OneWay load() {
        return new OneWay(Pathfinder.table(RouteFiles.DIR + "one_way.tsv", OneWay::parse));
    }

    /** "x y plane <tab> x y plane <tab> radius <tab> name", split at tabs; null when not a rule. */
    static Rule parse(String[] parts) {
        if (parts.length < 3) {
            return null;
        }
        int from = Tiles.parse(parts[0]), to = Tiles.parse(parts[1]);
        try {
            return from < 0 || to < 0 ? null : new Rule(from, to, Integer.parseInt(parts[2].trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    boolean against(int from, int to) {
        return rules.stream().anyMatch(rule -> near(from, rule.to, rule.radius) && near(to, rule.from, rule.radius));
    }

    static boolean near(int a, int b, int radius) {
        return !Tiles.isSea(a) && !Tiles.isSea(b) && Tiles.near(a, b, radius);
    }
}
