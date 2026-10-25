package com.hdmapreforged;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.regex.*;
import lombok.*;
import lombok.extern.slf4j.*;
import net.runelite.client.util.*;

/**
 * The map server's files kept on disk once seen, under the plugin's folder: the lists of a data version, and a map
 * version's map list, region table, places and route tables, and NPC pictures. A player asks the server for each file
 * once; once another version of a kind is in use ({@link #prune}), the old version's folder goes.
 */
@Slf4j
@RequiredArgsConstructor
final class ServerCache {
    static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    static final Pattern KIND = Pattern.compile("[a-z]{1,16}");
    static final int MAX_BYTES = 16 * 1024 * 1024;

    final Filepath root;
    final ExecutorService io;
    /** Each kind's version in use, whose folder alone is kept. */
    final Map<String, String> inUse = new ConcurrentHashMap<>();
    /** Files (kind/version/name) whose kept copy is passed over until the server sends its own. */
    final Set<String> stale = ConcurrentHashMap.newKeySet();

    /**
     * A file's bytes: from disk when seen before, else from {@code fetch}, which asks the server and hands the bytes
     * (null when there are none) to the consumer it is given; they are kept for next time. Any thread; {@code done}
     * is called once, on any thread, with null when there are no bytes.
     */
    void bytes(String kind, String version, String name, Consumer<Consumer<byte[]>> fetch, Consumer<byte[]> done) {
        var file = file(kind, version, name);
        if (file == null || !TileCache.execute(io, () -> {
            String key = kind + "/" + version + "/" + name;
            var kept = stale.contains(key) ? null : read(file);
            if (kept != null) {
                done.accept(kept);
                return;
            }
            fetch.accept(bytes -> {
                if (bytes != null) {
                    // Passed over until the server's copy comes, so a version is never half old copies, half new.
                    stale.remove(key);
                    TileCache.execute(io, () -> keep(file, bytes));
                }
                done.accept(bytes);
            });
        })) {
            fetch.accept(done);
        }
    }

    /** Null for a name that could leave the folder. */
    Filepath file(String kind, String version, String name) {
        return KIND.matcher(kind).matches() && MapServer.isSafeVersion(version) && NAME.matcher(name).matches()
            ? root.joinSegment(kind).joinSegment(version).joinSegment(name) : null;
    }

    /** IO thread. */
    static byte[] read(Filepath file) {
        if (!file.isFile()) {
            return null;
        }
        try (InputStream in = file.openInputStream()) {
            var bytes = in.readNBytes(MAX_BYTES + 1);
            return bytes.length > MAX_BYTES ? null : bytes;
        } catch (IOException e) {
            log.debug("Could not read {}", file, e);
            return null;
        }
    }

    /** IO thread. */
    void keep(Filepath file, byte[] bytes) {
        try {
            TileCache.writeAtomically(file, bytes);
        } catch (IOException e) {
            log.debug("Could not keep {}", file, e);
        }
    }

    /**
     * {@code version} of {@code kind} is in use (shown, so usable): the other versions' folders go. Not before, or a
     * broken new version would take the working one's files. Background thread.
     */
    void prune(String kind, String version) {
        if (version.equals(inUse.put(kind, version))) {
            return;
        }
        var folder = root.joinSegment(kind);
        if (!folder.isDirectory()) {
            return;
        }
        try (var others = folder.walk(1)) {
            others.filter(other -> !other.equals(folder) && !other.getFileName().equals(version))
                .forEach(TileCache::deleteTree);
        } catch (IOException | UncheckedIOException e) {
            // A folder another prune removed meanwhile: what is left goes next time.
            log.debug("Could not look through {}", folder, e);
        }
    }
}
