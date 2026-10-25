package com.hdmapreforged;

import static java.lang.Math.*;

import java.util.*;
import java.util.function.*;
import net.runelite.client.party.messages.*;

/**
 * What a party member carries, wears and can do: whole on joining and at most every {@link PartyMapRules#WHOLE_TICKS},
 * else only what changed since, so a tick's change (a hit, a rune cast, experience) is a few numbers and goes every
 * tick. Short field names keep it small; values from other clients are checked before use.
 */
public class HdMapPartyState extends PartyMemberMessage {
    static final int INVENTORY = 28, EQUIPMENT = 14, SKILLS = 24;
    /** Item ids above this are not believed. */
    static final int MAX_ITEM = 100_000;
    static final int[] SIZES = {INVENTORY, INVENTORY, EQUIPMENT, SKILLS, SKILLS, SKILLS};

    /** Whether the parts are whole; else each holds what changed as index, value pairs (null: nothing did). */
    private final boolean w;
    /** A new start (the plugin, a login, maybe another account): no gain since the state had before. */
    private final boolean n;
    /** Inventory ids (-1 empty) and quantities, worn ids by slot, real and current levels, experience per skill. */
    private final int[] i, q, e, s, b, x;
    /** Run and special attack energy, 0 to 100. */
    private final int r, p;

    HdMapPartyState(boolean whole, boolean fresh, int[][] parts, int run, int special) {
        w = whole;
        n = fresh;
        i = parts[0];
        q = parts[1];
        e = parts[2];
        s = parts[3];
        b = parts[4];
        x = parts[5];
        r = run;
        p = special;
    }

    boolean whole() {
        return w;
    }

    boolean fresh() {
        return n;
    }

    /** No longer shared (the setting turned off, the plugin stopped): a whole one without parts. */
    static HdMapPartyState unshared() {
        return new HdMapPartyState(true, true, new int[SIZES.length][], 0, 0);
    }

    boolean isUnshared() {
        return w && i == null && s == null;
    }

    /** The parts in {@link #SIZES}' order: this one's whole, or its changes onto {@code before} (null when whole). */
    int[][] parts(int[][] before) {
        IntUnaryOperator item = id -> id >= 0 && id < MAX_ITEM ? id : -1;
        IntUnaryOperator level = v -> max(0, min(255, v));
        IntUnaryOperator[] checks = {item, v -> max(0, v), item, level, level, v -> max(0, min(200_000_000, v))};
        int[][] got = {i, q, e, s, b, x};
        int[][] out = new int[SIZES.length][];
        for (int k = 0; k < out.length; k++) {
            boolean whole = w || before == null;
            out[k] = whole ? new int[SIZES[k]] : before[k].clone();
            if (whole) {
                Arrays.fill(out[k], k == 0 || k == 2 ? -1 : 0);
            }
            int[] values = got[k] == null ? new int[0] : got[k];
            for (int n = 0; n < values.length - (whole ? 0 : 1); n += whole ? 1 : 2) {
                int at = whole ? n : values[n];
                if (at >= 0 && at < out[k].length) {
                    out[k][at] = checks[k].applyAsInt(values[whole ? n : n + 1]);
                }
            }
        }
        return out;
    }

    int runEnergy() {
        return max(0, min(100, r));
    }

    int specialAttack() {
        return max(0, min(100, p));
    }

    /** What changed from {@code before} to {@code now} as index, value pairs; null when nothing did. */
    static int[] changes(int[] before, int[] now) {
        int n = 0;
        for (int k = 0; k < now.length; k++) {
            n += before[k] != now[k] ? 1 : 0;
        }
        if (n == 0) {
            return null;
        }
        int[] pairs = new int[2 * n];
        for (int k = 0, at = 0; k < now.length; k++) {
            if (before[k] != now[k]) {
                pairs[at++] = k;
                pairs[at++] = now[k];
            }
        }
        return pairs;
    }
}
