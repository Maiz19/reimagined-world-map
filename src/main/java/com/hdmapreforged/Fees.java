package com.hdmapreforged;

import java.util.*;
import lombok.*;

/** Entrance fees the plugin cannot check ({@code fees.tsv}), weighed as extra time so a free way wins. */
final class Fees {
    static final int NEAR = 2;

    @RequiredArgsConstructor
    static final class Fee {
        final int x, y, plane;
        /** Extra half ticks. */
        final int cost;
        final String text;
    }

    static final RouteFiles.Table<List<Fee>> FEES = new RouteFiles.Table<>(Fees::load);

    static Fee at(int x, int y, int plane) {
        return FEES.get().stream().filter(fee -> fee.plane == plane && Math.abs(fee.x - x) <= NEAR
            && Math.abs(fee.y - y) <= NEAR).findFirst().orElse(null);
    }

    static List<Fee> load() {
        return Pathfinder.table(RouteFiles.DIR + "fees.tsv", parts -> {
            var xyz = Pathfinder.ints(parts[0]);
            return new Fee(xyz[0], xyz[1], xyz[2], Integer.parseInt(parts[1].trim()), parts[2].trim());
        });
    }
}
