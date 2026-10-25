package com.hdmapreforged;

import static com.hdmapreforged.Tiles.*;
import static lombok.AccessLevel.*;

import java.util.*;
import java.util.stream.*;
import lombok.*;

/** The result of a search: the steps, and how the target was reached (or why not). */
@AllArgsConstructor(access = PRIVATE)
final class Route {
    enum Outcome {
        FOUND,
        NEAREST,
        NONE,
        CANCELLED
    }

    @AllArgsConstructor
    static final class Obstacle {
        final int at;
        final String text;
    }

    @AllArgsConstructor
    static final class Step {
        enum Kind {
            WALK, SAIL, STAIRS, ENTRANCE, TELEPORT, TRANSPORT, SHIP, BOARD, DISEMBARK, HOUSE,
            /** Take from the bank what the steps after it need ({@link BankDetour}): both points the bank's tile. */
            BANK
        }

        final Kind kind;
        /** Packed: all tiles walked or sea blocks sailed, or the two ends of a jump. */
        final int[] points;
        @With(PACKAGE)
        final String name;
        @With(PACKAGE)
        final String detail;
        final int cost, doors;
        final List<Obstacle> obstacles;
        /** Teleport or transport kind ("Hot air balloon"), to leave out all of that kind; or null. */
        final String category;

        int first() {
            return points[0];
        }

        int last() {
            return points[points.length - 1];
        }

        boolean isJump() {
            return kind != Kind.WALK && kind != Kind.SAIL;
        }
    }

    final Outcome outcome;
    @With(PACKAGE)
    final List<Step> steps;
    /** Half ticks. */
    final int cost, target, end;
    @With(PACKAGE)
    final boolean exhausted;
    final boolean limited;
    final int nodes;
    /** Where a search that proved the target out of reach proved it out of reach from; else null. */
    @With(PACKAGE)
    final Reached reached;
    /** Through the Wilderness though the player keeps out of it: no way around it gets there. */
    @With(PACKAGE)
    final boolean throughWilderness;
    /** The search left out a step or jump into the Wilderness (kept out of it): through it, it may get further. */
    @With(PACKAGE)
    final boolean keptOut;

    /** Without the extra weight of teleports kept for longer trips. */
    int time() {
        return steps.stream().mapToInt(step -> step.cost).sum();
    }

    Route(Outcome outcome, List<Step> steps, int cost, int target, int end, boolean exhausted, boolean limited,
        int nodes) {
        this(outcome, steps, cost, target, end, exhausted, limited, nodes, null);
    }

    Route(Outcome outcome, List<Step> steps, int cost, int target, int end, boolean exhausted, boolean limited, int nodes,
        Reached reached) {
        this(outcome, Collections.unmodifiableList(steps), cost, target, end, exhausted, limited, nodes, reached, false,
            false);
    }

    /** This route, ending near the target of {@code shut}, which proved it out of reach. */
    Route near(Route shut) {
        return new Route(Outcome.NEAREST, steps, cost, shut.target, end, shut.exhausted, limited, nodes, shut.reached,
            throughWilderness, keptOut);
    }

    static final int NEAR = 12;

    /**
     * Up to its first teleport (or way of one's house): what comes after is drawn in the game once it is taken, so a
     * route out of the house and back in (to its fairy ring) does not draw both visits at once.
     */
    Route untilTeleport() {
        for (int s = 0; s < steps.size() - 1; s++) {
            var kind = steps.get(s).kind;
            if (kind == Step.Kind.TELEPORT || kind == Step.Kind.HOUSE) {
                return withSteps(steps.subList(0, s + 1));
            }
        }
        return this;
    }

    /** What is still ahead of the player, like a navigation app; the route itself when the player is not near it. */
    Route ahead(int player) {
        if (player < 0 || steps.isEmpty()) {
            return this;
        }
        int bestStep = -1, bestIndex = 0;
        long bestDistance = (long) (NEAR + 1) * (NEAR + 1);
        // Past a bank step nothing in one's house counts while one is in it: the route may go from the house to the bank
        // and back (its fairy ring), and in the house before it the bank is still to do.
        boolean banked = false, home = HousePlan.contains(player);
        for (int s = 0; s < steps.size(); s++) {
            Step step = steps.get(s);
            if (step.kind == Step.Kind.BANK) {
                // Done only once what it takes out is used: standing at the bank it is still to do.
                banked = true;
                continue;
            }
            if (step.isJump()) {
                int landing = step.last(), from = step.first();
                // Nearer where it starts (a gate, a door): not crossed yet.
                boolean before = from >= 0 && !isSea(from) && sameLevel(player, from)
                    && distance(player, from) < distance(player, landing);
                if (banked && home && HousePlan.contains(landing)) {
                    continue;
                }
                // Into one's house (a teleport, its portal): anywhere in it, it was taken, wherever the game put one.
                boolean entered = from >= 0 && !HousePlan.contains(from) && HousePlan.contains(landing) && home;
                if (sameLevel(player, landing) && (distance(player, landing) <= 2 || entered) && !before) {
                    bestStep = s + 1;
                    bestIndex = 0;
                    bestDistance = 0;
                }
                continue;
            }
            for (int i = 0; i < step.points.length; i++) {
                int point = step.points[i];
                if (!sameLevel(player, point) || banked && home && HousePlan.contains(point)) {
                    continue;
                }
                long dx = x(player) - x(point), dy = y(player) - y(point), d = dx * dx + dy * dy;
                if (isSea(point)) {
                    d /= (long) CELL * CELL;
                }
                // Ties go to the later point, so a route passing a spot twice follows the player forward.
                if (d <= bestDistance) {
                    bestStep = s;
                    bestIndex = i;
                    bestDistance = d;
                }
            }
        }
        if (bestStep < 0 || bestStep == 0 && bestIndex == 0) {
            return this;
        }
        var left = new ArrayList<Step>();
        for (int s = bestStep; s < steps.size(); s++) {
            Step step = steps.get(s);
            if (s == bestStep && bestIndex > 0) {
                var rest = Arrays.copyOfRange(step.points, bestIndex, step.points.length);
                if (rest.length < 2) {
                    continue;
                }
                var obstaclesLeft = step.obstacles.stream()
                    .filter(o -> Arrays.stream(rest).anyMatch(p -> p == o.at)).collect(Collectors.toList());
                step = new Step(step.kind, rest, step.name, step.detail, step.cost * rest.length / step.points.length,
                    step.doors, obstaclesLeft, step.category);
            }
            left.add(step);
        }
        return withSteps(Collections.unmodifiableList(left)).withReached(null);
    }

    static boolean sameLevel(int a, int b) {
        return isSea(a) || isSea(b) || z(a) == z(b);
    }

    /**
     * Whether this search proved the target out of reach from the node too (it reached the node and tried everything,
     * or flooded back from the target and never came to the node), as long as the same ways may be used.
     */
    boolean reached(int node) {
        return reached != null && reached.contains(node);
    }

    boolean snapped() {
        return end >= 0 && end != target;
    }

    boolean has(Step.Kind kind) {
        return first(kind) != null;
    }

    Step first(Step.Kind kind) {
        return steps.stream().filter(step -> step.kind == kind).findFirst().orElse(null);
    }

    public String toString() {
        StringBuilder sb = new StringBuilder(outcome + " " + (cost + 1) / 2 + " ticks, " + nodes + " nodes");
        for (Step step : steps) {
            sb.append("\n  ").append(step.kind).append(' ').append(step.name == null ? "" : step.name).append(' ')
                .append(format(step.first())).append(" -> ").append(format(step.last()));
        }
        return sb.toString();
    }
}
