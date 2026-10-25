package com.hdmapreforged;

import java.util.*;
import java.util.function.IntUnaryOperator;
import lombok.*;
import net.runelite.api.coords.*;

/**
 * Where an item comes from ({@link WorldData#item}): the shops that sell it, where it lies on the ground and the
 * monsters that drop it; also a shop's wares, each with the stock it keeps.
 */
@RequiredArgsConstructor
final class ItemSources {
    @RequiredArgsConstructor
    static final class Store {
        final String shop;
        final WorldPoint point;
        final int stock;
    }

    @RequiredArgsConstructor
    static final class Ware {
        final String item;
        /** The game's item id, for its picture. */
        final int itemId, stock;
    }

    /** A spot where the item lies on the ground, with how many lie there. */
    @RequiredArgsConstructor
    static final class Ground {
        final WorldPoint point;
        final int count;
    }

    /** A monster that drops the item, and how rarely ("Always", "3/128"). */
    @RequiredArgsConstructor
    static final class Dropper {
        final int npc;
        final String name, rarity;
        /** The monster's version that drops it when not the standard one ("Wilderness Slayer Cave"); else "". */
        final String version;
        /** "5-10", "7"; empty for one. */
        final String quantity;
        /** In how many places monsters of the name stand; those standing nowhere known are left out. */
        final int places;
        /** The first of its page's drop tables that drops the item ("" unnamed): the monster's Drops tab shows it. */
        final String table;
    }

    /** The item's name. */
    final String page;
    /** The game's id of the item, for its picture and price. */
    final int item;
    final List<Store> stores;
    final List<Ground> grounds;
    final List<Dropper> droppers;

    /** "Stock 10", "Stock ∞": how many a shop keeps while no one trades. */
    static String stockText(int stock) {
        return "Stock " + (stock == WorldData.ENDLESS ? "∞" : Integer.toString(stock));
    }

    /** Fuller stocks first, an endless one before all. */
    static int byStock(int a, int b) {
        return Integer.compare(b == WorldData.ENDLESS ? Integer.MAX_VALUE : b, a == WorldData.ENDLESS ? Integer.MAX_VALUE : a);
    }

    /**
     * A shop's wares in the order chosen: "shop" as the shop lists them, "stock" fullest first, "name", "price" dearest
     * first by the Grand Exchange ({@code price} gives an item's, -1 when not known: those last).
     */
    static Comparator<Ware> wareOrder(String sort, IntUnaryOperator price) {
        switch (sort) {
            case "stock":
                return (a, b) -> byStock(a.stock, b.stock) != 0 ? byStock(a.stock, b.stock) : a.item.compareToIgnoreCase(b.item);
            case "name":
                return (a, b) -> a.item.compareToIgnoreCase(b.item);
            case "price":
                return Comparator.comparingLong((Ware w) -> price.applyAsInt(w.itemId) < 0 ? Long.MAX_VALUE
                    : -(long) price.applyAsInt(w.itemId)).thenComparing(w -> w.item, String.CASE_INSENSITIVE_ORDER);
            default:
                return (a, b) -> 0;
        }
    }
}
