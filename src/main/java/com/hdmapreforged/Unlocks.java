package com.hdmapreforged;

import static java.util.Locale.ROOT;
import static net.runelite.api.gameval.VarPlayerID.*;

import java.awt.Color;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.regex.*;
import net.runelite.api.*;

/**
 * The player's levels, finished quests, varbits and varps, taken on the client thread, read from any. What it cannot
 * judge counts as met, so icons are only hidden when a requirement is certainly missing.
 */
final class Unlocks {
    /** A varbit condition ("4070=0"), or a varp one with a p ("p4130>11999"). */
    static final Pattern VAR = Pattern.compile("(p?)(\\d+)([=<>&])(\\d+)");

    final Map<String, Integer> levels = new HashMap<>();
    final Map<String, Boolean> quests = new HashMap<>();
    /** Quests begun or finished, for a requirement "Swan Song (started)" (a place open during the quest and after). */
    final Map<String, Boolean> started = new HashMap<>();
    static final String STARTED = " (started)";
    final Map<Integer, Integer> varbits = new HashMap<>(), varps = new HashMap<>();
    final Map<Needs, Boolean> usable = new ConcurrentHashMap<>();
    static final int CACHED = 20_000;
    /** When the last home and minigame teleports were cast, in minutes since 1970 (0: never), as core's timers read them. */
    int lastHome, lastMinigame;
    static final int HOME_WAIT = 30, MINIGAME_WAIT = 20;

    /** The quests, varbits and varps the loaded requirements name, worked out once per data load. */
    static final class Watched {
        final Set<Quest> quests = new LinkedHashSet<>();
        /** Sorted. */
        final int[] varbits, varps;

        Watched(Collection<Needs> needs) {
            var names = new HashSet<String>();
            var ids = new TreeSet<Integer>();
            var varpIds = new TreeSet<Integer>();
            for (Needs need : needs) {
                for (String quest : need.quests.split(";")) {
                    names.add(quest.trim().toLowerCase(ROOT).replace(STARTED, ""));
                }
                ids(need.varbits, ids, varpIds);
            }
            // Not named by any table: fairy rings without a staff (RouteSource.carried).
            ids.add(LUMBRIDGE_ELITE);
            names.forEach(this::add);
            varbits = ids.stream().mapToInt(Integer::intValue).toArray();
            varps = varpIds.stream().mapToInt(Integer::intValue).toArray();
        }

        void add(String name) {
            for (Quest quest : Quest.values()) {
                if (quest.getName().equalsIgnoreCase(name)) {
                    quests.add(quest);
                }
            }
        }

        /** Whether this varbit changing can change what is usable. */
        boolean watches(int varbit) {
            return Arrays.binarySearch(varbits, varbit) >= 0;
        }

        boolean watchesVarp(int varp) {
            return Arrays.binarySearch(varps, varp) >= 0;
        }
    }

    /** Client thread only. Quests run a client script each, so unless {@code checkQuests} they come from {@code previous}. */
    static Unlocks capture(Client client, Watched watched, Unlocks previous, boolean checkQuests) {
        var unlocks = new Unlocks();
        for (Skill skill : Skill.values()) {
            if (!"Overall".equals(skill.getName())) {
                unlocks.levels.put(skill.getName().toLowerCase(ROOT), client.getRealSkillLevel(skill));
            }
        }
        unlocks.levels.put("total", client.getTotalLevel());
        // "32 Quest" in a requirement: quest points.
        unlocks.levels.put("quest", client.getVarpValue(QP));
        Player player = client.getLocalPlayer();
        if (player != null) {
            unlocks.levels.put("combat", player.getCombatLevel());
        }
        if (checkQuests || previous == null) {
            for (Quest quest : watched.quests) {
                var state = quest.getState(client);
                String name = quest.getName().toLowerCase(ROOT);
                unlocks.quests.put(name, state == QuestState.FINISHED);
                unlocks.started.put(name, state != QuestState.NOT_STARTED);
            }
        } else {
            unlocks.quests.putAll(previous.quests);
            unlocks.started.putAll(previous.started);
        }
        unlocks.lastHome = client.getVarpValue(AIDE_TELE_TIMER);
        unlocks.lastMinigame = client.getVarpValue(SLUG2_REGIONUID);
        capture(watched.varbits, client::getVarbitValue, unlocks.varbits);
        capture(watched.varps, client::getVarpValue, unlocks.varps);
        return unlocks;
    }

    /** {@code read(id)} into {@code into}; gone after a game update, or otherwise unknown, counts as met. */
    static void capture(int[] ids, IntUnaryOperator read, Map<Integer, Integer> into) {
        for (int id : ids) {
            try {
                into.put(id, read.applyAsInt(id));
            } catch (RuntimeException e) {
                // Unknown: counts as met.
            }
        }
    }

    static void ids(String column, Set<Integer> varbits, Set<Integer> varps) {
        Matcher matcher = VAR.matcher(column);
        while (matcher.find()) {
            int id = number(matcher.group(2));
            if (id >= 0) {
                (matcher.group(1).isEmpty() ? varbits : varps).add(id);
            }
        }
    }

    /** -1 when too long to be one (a broken line). */
    static int number(String digits) {
        return digits.length() <= 9 ? Integer.parseInt(digits) : -1;
    }

    boolean sameAs(Unlocks other) {
        return other != null && levels.equals(other.levels) && quests.equals(other.quests) && started.equals(other.started)
            && varbits.equals(other.varbits)
            && varps.equals(other.varps)
            && lastHome == other.lastHome && lastMinigame == other.lastMinigame;
    }

    /** Whether a home or minigame teleport still waits out its cooldown at this minute (since 1970). */
    boolean waiting(String name, String group, long nowMinutes) {
        boolean home = name.toLowerCase(ROOT).contains("home teleport");
        boolean minigame = "Minigame teleports".equals(group);
        return home && lastHome > 0 && nowMinutes - lastHome < HOME_WAIT
            || minigame && lastMinigame > 0 && nowMinutes - lastMinigame < MINIGAME_WAIT;
    }

    /** True, false, or null when this line cannot be checked. */
    Boolean met(Requirements.Line line) {
        if (line.skill != null) {
            // "130 Attack+Strength": the levels added up (the Warriors' Guild).
            int sum = 0;
            for (String skill : line.skill.split("\\+")) {
                Integer level = levels.get(skill.trim().toLowerCase(ROOT));
                if (level == null) {
                    return null;
                }
                sum += level;
            }
            return sum >= line.level;
        }
        if (line.quest != null) {
            String quest = line.quest.toLowerCase(ROOT);
            if (quest.endsWith(STARTED)) {
                String name = quest.substring(0, quest.length() - STARTED.length());
                Boolean begun = started.get(name);
                // Finished is begun; not finished alone does not say whether it was begun.
                return begun != null ? begun : Boolean.TRUE.equals(quests.get(name)) ? Boolean.TRUE : null;
            }
            return quests.get(quest);
        }
        return null;
    }

    /** False only when a requirement is certainly not met. */
    /** A quest start icon's dot: green finished, yellow started, red not; null when not read. */
    Color questColor(String name) {
        String key = name.toLowerCase(ROOT);
        Boolean done = quests.get(key);
        return done == null ? null : done ? Color.GREEN : Boolean.TRUE.equals(started.get(key)) ? Color.YELLOW
            : Color.RED;
    }

    boolean usable(Needs needs) {
        if (needs.isEmpty()) {
            return true;
        }
        Boolean known = usable.get(needs);
        if (known != null) {
            return known;
        }
        boolean result = check(needs);
        if (usable.size() < CACHED) {
            usable.put(needs, result);
        }
        return result;
    }

    /** LUMBRIDGE_DIARY_ELITE_COMPLETE: the Lumbridge & Draynor Elite Diary is done. */
    static final int LUMBRIDGE_ELITE = 4498;

    /** Whether the Lumbridge & Draynor Elite Diary is known to be done. */
    boolean lumbridgeElite() {
        Integer value = varbits.get(LUMBRIDGE_ELITE);
        return value != null && value == 1;
    }

    boolean check(Needs needs) {
        return RouteSource.unmetLines(this, needs).isEmpty() && vars(needs.varbits, varbits, varps);
    }

    /** Conditions like {@code "4070=0;6069<3;4560&2;p4130>11999"} (a p: a varp). */
    static boolean vars(String column, Map<Integer, Integer> varbits, Map<Integer, Integer> varps) {
        Matcher matcher = VAR.matcher(column);
        while (matcher.find()) {
            int id = number(matcher.group(2)), wanted = number(matcher.group(4));
            Integer value = id < 0 ? null : (matcher.group(1).isEmpty() ? varbits : varps).get(id);
            if (value == null || wanted < 0) {
                continue;
            }
            String op = matcher.group(3);
            boolean ok = op.equals("=") ? value == wanted : op.equals(">") ? value > wanted
                : op.equals("<") ? value < wanted : (value & wanted) != 0;
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
