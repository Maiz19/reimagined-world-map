package com.hdmapreforged;

import lombok.*;

/**
 * Where a search proved its target out of reach from: with the same ways, from any of these nodes the target stays out
 * of reach. Either all a search that tried everything reached (what it could get to from there it got to from its
 * start), or everything but the nodes the target can be reached from, when the search flooded back from the target
 * instead ({@code complement}). Immutable once made.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
final class Reached {
    final NodeSet nodes;
    final boolean complement;

    boolean contains(int node) {
        return node >= 0 && nodes.contains(node) != complement;
    }
}
