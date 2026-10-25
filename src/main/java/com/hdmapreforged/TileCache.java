package com.hdmapreforged;

import java.awt.*;
import java.awt.image.*;
import java.io.*;
import java.lang.ref.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;
import java.util.stream.*;
import javax.imageio.*;
import javax.imageio.stream.*;
import lombok.*;
import lombok.extern.slf4j.*;
import net.runelite.client.util.*;
import okhttp3.*;

/**
 * Map tiles from our map server, in memory and on disk, downloaded a few at a time. Each frame queues tiles farthest first
 * and loads at frame end, so the middle loads first; tiles scrolled away before their turn are dropped.
 */
@Slf4j
@RequiredArgsConstructor
final class TileCache {
    static final int TILE_SIZE = 256, MIN_ZOOM = -3, MAX_ZOOM = 3;
    /** A tile is well under 200 KB. */
    static final int MAX_TILE_BYTES = 2 * 1024 * 1024;
    static final long MB = 1024 * 1024;
    /**
     * 128 MB of tiles in view: past it the views draw a coarser level, a quarter of the tiles (a 4K window's upper floor
     * would need 2,000 tiles, 500 MB, just above a whole zoom).
     */
    static final int MAX_TILES = 512;
    /** Kept while no map is open, for the next opening. */
    static final int CLOSED_TILES = 64;

    @RequiredArgsConstructor
    @EqualsAndHashCode
    static final class Key {
        final int map, zoom, plane, x, y;

        String path() {
            return map + "/" + zoom + "/" + plane + "_" + x + "_" + y + ".png";
        }

        /** A line of missing.txt; null for a broken one. */
        static Key parse(String line) {
            var p = line.trim().split(" ");
            try {
                return p.length == 5 ? new Key(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]),
                    Integer.parseInt(p[3]), Integer.parseInt(p[4])) : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    final OkHttpClient http;
    final ExecutorService io;
    final Filepath cacheRoot;
    final Runnable onLoaded;

    /** The tiles the open views need (in view and some to spare), or a few with no map open. */
    final LinkedHashMap<Key, BufferedImage> memory = new LinkedHashMap<>(256, 0.75f, true);
    /**
     * Older tiles past those, up to the memory setting, held softly: a large heap keeps them, and the JVM drops them
     * before it runs out of memory, so they never crash the client (plugins may not read the heap's size).
     */
    final LinkedHashMap<Key, SoftReference<BufferedImage>> spare = new LinkedHashMap<>();
    final Set<Key> missing = new HashSet<>();
    final Map<Key, Long> failed = new HashMap<>();
    /** Previous-version tiles shown while this version's are fetched. */
    final Set<Key> stale = new HashSet<>();
    final Deque<Key> queue = new ArrayDeque<>();
    final Set<Key> queued = new HashSet<>(), loading = new HashSet<>();
    /** Downloads under way: cancelled when the cache is cleared (the plugin stops). */
    final Set<Call> calls = ConcurrentHashMap.newKeySet();
    /** Requests still running, of any generation: {@link #clear} empties {@code loading} but not the network. */
    int inFlight;
    /** Bytes on disk as last counted, plus writes since; a trim runs once it passes the limit. */
    final AtomicLong diskBytes = new AtomicLong();
    final AtomicBoolean trimQueued = new AtomicBoolean();
    volatile String version;
    /** Tiles held in memory past those in view; the tests raise or lower it. */
    volatile int memoryLimit = 256, diskLimitMb = 1000;
    /** What each open view needs; the memory cache never shrinks below their sum, or tiles in view would reload. */
    final Map<Object, Integer> needs = new HashMap<>();
    int frameNeed, generation;
    /** Old versions are removed once a tile of the new version downloads, so one the server lacks never wipes the cache. */
    boolean cleanupPending;
    /** Previous version on disk, shown while the new one downloads; each old tile is removed once replaced. */
    volatile String fallbackVersion;

    static int tileIndex(double world, int zoom) {
        return (int) Math.floor(world * Math.pow(2, zoom) / TILE_SIZE);
    }

    static double worldPerTile(int zoom) {
        return TILE_SIZE / Math.pow(2, zoom);
    }

    /** The disk cache's size (setting); lowered, it trims at once. */
    void configure(int diskLimitMb) {
        int oldLimit = this.diskLimitMb;
        this.diskLimitMb = diskLimitMb;
        if (diskLimitMb < oldLimit) {
            requestTrim();
        }
    }

    /**
     * The tiles of {@code from} become {@code to}'s, but for those the server says changed or went, so only they are
     * fetched again: the folder is renamed, the changed tiles deleted, the tiles known missing kept but for the changed
     * ones. Before {@link #setVersion}. False when {@code from} is not on disk or {@code to} already is.
     */
    boolean upgrade(String from, String to, Set<String> changed, Set<String> removed) {
        var old = versionFolder(from);
        var fresh = versionFolder(to);
        if (!old.isDirectory() || fresh.exists()) {
            return false;
        }
        var gone = new HashSet<>(changed);
        gone.addAll(removed);
        synchronized (missingFile) {
            try {
                for (String path : gone) {
                    var p = path.split("/");
                    var tile = p.length == 3 ? old.join(p[0], p[1], p[2]) : null;
                    if (tile != null && tile.isFile()) {
                        diskBytes.addAndGet(-tile.size());
                        tile.delete();
                    }
                }
                var missingList = old.joinSegment(MISSING);
                if (missingList.isFile()) {
                    StringBuilder kept = new StringBuilder();
                    try (BufferedReader in = missingList.openBufferedReader()) {
                        String line;
                        while ((line = in.readLine()) != null) {
                            var key = Key.parse(line);
                            if (key != null && !changed.contains(key.path())) {
                                kept.append(line.trim()).append('\n');
                            }
                        }
                    }
                    missingList.write(kept.toString());
                }
                old.moveTo(fresh);
                return true;
            } catch (IOException | RuntimeException e) {
                log.debug("Could not carry the tiles of {} over to {}", from, to, e);
                return false;
            }
        }
    }

    /** The map server's version to show; the previous one on disk shows meanwhile, worn down as new tiles arrive. */
    void setVersion(String version) {
        if (version.equals(this.version)) {
            return;
        }
        // Listed outside the lock: the map keeps painting meanwhile.
        String fallback = previousOnDisk(version);
        synchronized (this) {
            if (version.equals(this.version)) {
                return;
            }
            this.version = version;
            clear();
            cleanupPending = true;
            fallbackVersion = fallback;
        }
        if (fallback != null) {
            // Older versions merge into the fallback, so their tiles still show while this version's arrive.
            execute(() -> mergeOlder(version, fallback));
        }
        execute(() -> loadMissing(version));
        requestTrim();
    }

    /** Per version, the tiles the map server has not got (open sea, the world's edge), so they aren't asked again. */
    static final String MISSING = "missing.txt";
    final Object missingFile = new Object();

    void rememberMissing(String tileVersion, Key key) {
        synchronized (missingFile) {
            try {
                var folder = versionFolder(tileVersion);
                folder.createDirectories();
                folder.joinSegment(MISSING).write(key.map + " " + key.zoom + " " + key.plane + " " + key.x + " " + key.y
                    + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException | RuntimeException e) {
                log.debug("Could not remember missing tile {}", key.path(), e);
            }
        }
    }

    /** Background thread. */
    void loadMissing(String tileVersion) {
        var file = versionFolder(tileVersion).joinSegment(MISSING);
        if (!file.isFile()) {
            return;
        }
        var keys = new HashSet<Key>();
        try (BufferedReader in = file.openBufferedReader()) {
            in.lines().map(Key::parse).filter(Objects::nonNull).forEach(keys::add);
        } catch (IOException | RuntimeException e) {
            log.debug("Could not read {}", file, e);
            return;
        }
        synchronized (this) {
            if (tileVersion.equals(version)) {
                missing.addAll(keys);
            }
        }
    }

    String previousOnDisk(String current) {
        Filepath best = null;
        for (var dir : versionDirs()) {
            if (!dir.getFileName().equals(current) && MapServer.isSafeVersion(dir.getFileName())
                && (best == null || modified(dir) > modified(best))) {
                best = dir;
            }
        }
        return best == null ? null : best.getFileName();
    }

    /** The tiles in view and half as many to spare, so panning back or zooming out reloads little. */
    static int need(int visibleTiles) {
        return visibleTiles + visibleTiles / 2 + 16;
    }

    /** Whether {@code view} may show {@code visibleTiles} with the cache under its cap, the other views' tiles counted. */
    synchronized boolean fits(Object view, int visibleTiles) {
        return need(visibleTiles) + frameNeed - needs.getOrDefault(view, 0) <= Math.max(memoryLimit, MAX_TILES);
    }

    synchronized void beginFrame(Object view, int visibleTiles) {
        queue.clear();
        queued.clear();
        setNeed(view, need(visibleTiles));
    }

    /**
     * {@code view} closed: with no map open only a few tiles stay, for the next opening. Another still open trims at its
     * next tile, once its own are the latest used.
     */
    synchronized void closed(Object view) {
        setNeed(view, 0);
        if (needs.isEmpty()) {
            queue.clear();
            queued.clear();
            trim();
        }
    }

    void setNeed(Object view, int need) {
        Integer old = need > 0 ? needs.put(view, need) : needs.remove(view);
        frameNeed += need - (old == null ? 0 : old);
    }

    synchronized void endFrame() {
        pump();
    }

    synchronized BufferedImage peek(Key key) {
        var ref = spare.remove(key);
        var image = ref == null ? null : ref.get();
        if (image != null && memory.putIfAbsent(key, image) == null) {
            // Back from the soft spares: strongly held only up to what the views need, download or not.
            trim();
            return image;
        }
        return memory.get(key);
    }

    /** Queue tiles farthest from the middle first. */
    synchronized BufferedImage get(Key key) {
        BufferedImage image = peek(key);
        Long failedAt = failed.get(key);
        if (!(image != null && !stale.contains(key) || version == null || missing.contains(key) || loading.contains(key)
            // A failed tile is tried again after 30 seconds.
            || queued.contains(key) || failedAt != null && System.currentTimeMillis() - failedAt < 30_000)) {
            queue.addFirst(key);
            queued.add(key);
        }
        return image;
    }

    /** Null on 404. */
    static byte[] bytes(Response response, ResponseBody body) throws IOException {
        if (response.code() == 404) {
            return null;
        }
        if (!response.isSuccessful() || body == null) {
            throw new IOException("HTTP " + response.code());
        }
        return readBody(body, MAX_TILE_BYTES);
    }

    /** Every tile downloaded again (the map looks wrong): those on disk go first, then those in memory. */
    void downloadAgain() {
        execute(() -> {
            versionDirs().forEach(TileCache::deleteTree);
            diskBytes.set(0);
            synchronized (this) {
                fallbackVersion = null;
                clear();
            }
        });
    }

    synchronized void clear() {
        generation++;
        memory.clear();
        spare.clear();
        stale.clear();
        queue.clear();
        queued.clear();
        loading.clear();
        missing.clear();
        failed.clear();
        calls.forEach(Call::cancel);
        calls.clear();
    }

    void pump() {
        // At most 10 at once; OkHttp itself still caps requests at 5 per host, shared with the rest of RuneLite.
        while (inFlight < 10 && !queue.isEmpty()) {
            Key key = queue.pollFirst();
            queued.remove(key);
            loading.add(key);
            inFlight++;
            int started = generation;
            String tileVersion = version;
            if (!execute(() -> load(key, tileVersion, started))) {
                loading.remove(key);
                inFlight--;
                return;
            }
        }
    }

    boolean execute(Runnable task) {
        return execute(io, task);
    }

    /** Runs {@code task} on {@code io}; false once it refuses more work (the plugin stopping), for every such hand-off. */
    static boolean execute(Executor io, Runnable task) {
        try {
            io.execute(task);
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    Filepath file(String tileVersion, Key key) {
        return versionFolder(tileVersion).join(key.path());
    }

    static final String PART_SUFFIX = ".part";

    /** Refused over {@code max} bytes whether or not the server declared the length. */
    static byte[] readBody(ResponseBody body, int max) throws IOException {
        long declared = body.contentLength();
        if (declared > max) {
            throw new IOException("Too large: " + declared + " bytes");
        }
        try (InputStream in = body.byteStream()) {
            var bytes = in.readNBytes(max + 1);
            if (bytes.length > max) {
                throw new IOException("Too large: over " + max + " bytes");
            }
            return bytes;
        }
    }

    Filepath versionFolder(String tileVersion) {
        return cacheRoot.joinSegment(tileVersion);
    }

    /** For a map's first frame, which would otherwise show icons on black before tiles pop in; not tiles known missing. */
    BufferedImage fromDiskNow(Key key) {
        String current;
        synchronized (this) {
            current = missing.contains(key) || failed.containsKey(key) ? null : version;
        }
        BufferedImage image = current == null ? null : readCached(file(current, key), under(key));
        if (image != null) {
            synchronized (this) {
                if (current.equals(version)) {
                    memory.put(key, image);
                    trim();
                }
            }
        }
        return image;
    }

    /** Files that are no tile are removed so they download again; a failed read leaves the file. */
    BufferedImage readCached(Filepath file, Color under) {
        if (!file.isFile()) {
            return null;
        }
        byte[] bytes;
        try {
            bytes = file.size() > MAX_TILE_BYTES ? null : read(file);
        } catch (IOException | RuntimeException e) {
            log.debug("Could not read cached tile {}", file, e);
            return null;
        }
        try {
            BufferedImage image = bytes == null ? null : decode(bytes, under);
            if (image != null) {
                return image;
            }
        } catch (IOException | RuntimeException e) {
            log.debug("Unreadable cached tile {}", file, e);
        }
        delete(file);
        return null;
    }

    void load(Key key, String tileVersion, int started) {
        try {
            BufferedImage cached = readCached(file(tileVersion, key), under(key));
            if (cached != null) {
                finish(key, started, cached, false, false);
                return;
            }
            String previous = fallbackVersion;
            var old = previous == null ? null : file(previous, key);
            BufferedImage stale = old == null ? null : readCached(old, under(key));
            if (stale != null) {
                showStale(key, started, stale);
            }
            Call download = http.newCall(new Request.Builder().url(MapServer.tile(tileVersion, key)).build());
            calls.add(download);
            download.enqueue(new Callback() {
                public void onFailure(Call call, IOException e) {
                    calls.remove(call);
                    log.debug("Tile download failed: {}", key.path(), e);
                    finish(key, started, null, false, false);
                }

                public void onResponse(Call call, Response response) {
                    calls.remove(call);
                    byte[] bytes;
                    try (var body = response.body()) {
                        bytes = bytes(response, body);
                    } catch (IOException | RuntimeException e) {
                        log.debug("Unusable tile {}", key.path(), e);
                        finish(key, started, null, false, false);
                        return;
                    }
                    // The disk work on our own thread, not OkHttp's shared callback threads.
                    if (!execute(() -> store(key, tileVersion, started, old, bytes))) {
                        finish(key, started, null, false, false);
                    }
                }
            });
        } catch (RuntimeException e) {
            log.debug("Could not load tile {}", key.path(), e);
            finish(key, started, null, false, false);
        }
    }

    /** A downloaded tile kept on disk; {@code bytes} null: the map server has none in this version. */
    void store(Key key, String tileVersion, int started, Filepath old, byte[] bytes) {
        BufferedImage image = null;
        try {
            if (bytes == null) {
                // Gone in this version: the stale copy must not linger.
                forget(key, started);
                rememberMissing(tileVersion, key);
            } else {
                // Not an image although found: a passing fault, asked again later. Kept on disk once decoded.
                image = decode(bytes, under(key));
                if (image != null) {
                    write(file(tileVersion, key), bytes);
                }
                if (image != null && old != null && old.isFile()) {
                    diskBytes.addAndGet(-old.size());
                    delete(old);
                }
            }
        } catch (IOException | RuntimeException e) {
            log.debug("Unusable tile {}", key.path(), e);
        }
        finish(key, started, image, bytes == null, image != null);
    }

    /** The key stays loading. */
    void showStale(Key key, int started, BufferedImage image) {
        synchronized (this) {
            if (started != generation || memory.containsKey(key)) {
                return;
            }
            memory.put(key, image);
            stale.add(key);
            trim();
        }
        onLoaded.run();
    }

    synchronized void forget(Key key, int started) {
        if (started == generation) {
            memory.remove(key);
            spare.remove(key);
            stale.remove(key);
        }
    }

    void finish(Key key, int started, BufferedImage image, boolean notFound, boolean downloaded) {
        boolean cleanup = false;
        synchronized (this) {
            inFlight--;
            if (started != generation) {
                pump();
                return;
            }
            loading.remove(key);
            if (image != null) {
                memory.put(key, image);
                stale.remove(key);
                failed.remove(key);
                trim();
                if (downloaded && cleanupPending) {
                    cleanupPending = false;
                    cleanup = true;
                }
            } else if (notFound) {
                missing.add(key);
            } else {
                failed.put(key, System.currentTimeMillis());
            }
            pump();
        }
        if (cleanup) {
            String keep = version, fallback = fallbackVersion;
            execute(() -> removeOtherVersions(keep, fallback));
        }
        if (image != null) {
            onLoaded.run();
        }
    }

    void trim() {
        boolean open = !needs.isEmpty();
        // Never fewer than a closed map keeps, so closing keeps the latest.
        int limit = Math.max(open ? frameNeed : 0, Math.min(memoryLimit, CLOSED_TILES));
        while (memory.size() > limit) {
            Key oldest = memory.keySet().iterator().next();
            var image = memory.remove(oldest);
            // An older version's tile shown meanwhile is not kept.
            if (!stale.remove(oldest) && open) {
                spare.put(oldest, new SoftReference<>(image));
            }
        }
        for (var it = spare.keySet().iterator(); it.hasNext() && spare.size() > (open ? memoryLimit : 0); ) {
            it.next();
            it.remove();
        }
    }

    /**
     * What a tile shown in a view is drawn on: the ground floor is drawn on the map's background, so filled with it the
     * tile stays opaque, which Java2D scales several times faster (zoomed out most tiles have see-through parts). Upper
     * floors stay see-through: the ground floor shows under them ({@link MapView}).
     */
    static Color under(Key key) {
        return key.plane == 0 ? MapView.BACKGROUND : null;
    }

    /** A picture, its size checked before its pixels are read; null when it is no picture. */
    static BufferedImage readImage(byte[] bytes, BiPredicate<Integer, Integer> sizeOk) throws IOException {
        // In memory: ImageIO's default stream would cache to a temporary file.
        try (var in = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                return null;
            }
            var reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (!sizeOk.test(width, height)) {
                    throw new IOException("Unexpected picture size: " + width + "x" + height);
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }

    /**
     * Into a type Java2D draws quickly; anything but 256x256 is refused before it is decoded. See-through parts are
     * filled with {@code under} when given ({@link #under}).
     */
    static BufferedImage decode(byte[] bytes, Color under) throws IOException {
        BufferedImage source = readImage(bytes, (width, height) -> width == TILE_SIZE && height == TILE_SIZE);
        if (source == null) {
            return null;
        }
        boolean alpha = source.getColorModel().hasAlpha();
        BufferedImage image = new BufferedImage(source.getWidth(), source.getHeight(), alpha && under == null
            ? BufferedImage.TYPE_INT_ARGB_PRE : BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        if (alpha && under != null) {
            g.setColor(under);
            g.fillRect(0, 0, image.getWidth(), image.getHeight());
        }
        g.drawImage(source, 0, 0, null);
        g.dispose();
        return image;
    }

    void write(Filepath file, byte[] bytes) {
        try {
            writeAtomically(file, bytes);
        } catch (IOException | RuntimeException e) {
            log.debug("Could not cache tile {}", file, e);
            return;
        }
        if (diskBytes.addAndGet(bytes.length) > diskLimitMb * MB) {
            requestTrim();
        }
    }

    /**
     * Through an own temp file (two threads may write the same file at once), then moved over the target; makes the
     * folder, which another thread may make at the same moment.
     */
    static void writeAtomically(Filepath target, byte[] bytes) throws IOException {
        var folder = target.getParent();
        folder.createDirectories();
        var temp = folder.createTempFile(target.getFileName() + ".", PART_SUFFIX);
        try {
            temp.write(bytes);
            try {
                temp.moveTo(target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                temp.moveTo(target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            temp.deleteIfExists();
        }
    }

    static byte[] read(Filepath file) throws IOException {
        try (InputStream in = file.openInputStream()) {
            return in.readAllBytes();
        }
    }

    /** One trim at a time: the flag stays set until it ends, so writes meanwhile queue no second one. */
    void requestTrim() {
        if (trimQueued.compareAndSet(false, true) && !execute(this::limitDiskUse)) {
            trimQueued.set(false);
        }
    }

    /** Moves tiles of other versions that {@code fallback} lacks into it, then removes those versions. */
    void mergeOlder(String current, String fallback) {
        var into = versionFolder(fallback);
        for (var dir : versionDirs()) {
            String name = dir.getFileName();
            if (name.equals(current) || name.equals(fallback) || !MapServer.isSafeVersion(name)) {
                continue;
            }
            for (var file : files(dir)) {
                try {
                    // Tiles lie in version/map/zoom.
                    var zoom = file.getParent();
                    var map = zoom.getParent();
                    var target = into.join(map.getFileName(), zoom.getFileName(), file.getFileName());
                    if (dir.equals(map.getParent()) && !target.exists()) {
                        target.getParent().createDirectories();
                        file.moveTo(target);
                    }
                } catch (IOException | RuntimeException e) {
                    log.debug("Could not keep {}", file, e);
                }
            }
            deleteTree(dir);
        }
    }

    void removeOtherVersions(String keep, String fallback) {
        for (var dir : versionDirs()) {
            if (!dir.getFileName().equals(keep) && !dir.getFileName().equals(fallback)) {
                deleteTree(dir);
            }
        }
    }

    /** Snapshot: the disk may change while trimming. */
    static final class Entry {
        final Filepath file;
        final long modified, size;

        Entry(Filepath file) throws IOException {
            this.file = file;
            modified = file.getLastModifiedTime().toMillis();
            size = file.size();
        }
    }

    /** Background thread: removes oldest tiles past the limit and stale half-written files. */
    void limitDiskUse() {
        try {
            long counted = diskBytes.get(), now = System.currentTimeMillis();
            var entries = new ArrayList<Entry>();
            long total = 0;
            for (var file : files(cacheRoot)) {
                String name = file.getFileName();
                try {
                    Entry entry = new Entry(file);
                    // Half-written files older than 10 minutes were left by a crash; newer ones may still be being
                    // written.
                    if (name.endsWith(PART_SUFFIX) && now - entry.modified > 10 * 60 * 1000L) {
                        delete(file);
                        continue;
                    }
                    entries.add(entry);
                    total += entry.size;
                } catch (IOException e) {
                    // Removed meanwhile.
                }
            }
            total -= trimOldest(entries, total, diskLimitMb * MB);
            // Tiles written during the walk still count.
            diskBytes.addAndGet(total - counted);
        } finally {
            trimQueued.set(false);
        }
    }

    /** Removes the oldest files until 80% of {@code limit} is left, if over it; returns the bytes removed. */
    static long trimOldest(List<Entry> entries, long total, long limit) {
        long removed = 0;
        if (total > limit) {
            entries.sort(Comparator.comparingLong((Entry e) -> e.modified));
            for (Entry entry : entries) {
                if (total - removed <= limit * 8 / 10) {
                    break;
                }
                if (delete(entry.file)) {
                    removed += entry.size;
                }
            }
        }
        return removed;
    }

    List<Filepath> versionDirs() {
        return walk(cacheRoot, 1, dir -> !dir.equals(cacheRoot) && dir.isDirectory());
    }

    /** Files below {@code dir}; symbolic links are not followed. */
    static List<Filepath> files(Filepath dir) {
        return walk(dir, Integer.MAX_VALUE, Filepath::isFile);
    }

    static List<Filepath> walk(Filepath dir, int depth, Predicate<Filepath> filter) {
        try (var found = dir.walk(depth)) {
            return found.filter(filter).collect(Collectors.toList());
        } catch (IOException | UncheckedIOException e) {
            return Collections.emptyList();
        }
    }

    static long modified(Filepath file) {
        try {
            return file.getLastModifiedTime().toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    /** Never follows symbolic links. */
    static void deleteTree(Filepath file) {
        try {
            file.deleteRecursively();
        } catch (IOException e) {
            log.debug("Could not remove {}", file, e);
        }
    }

    static boolean delete(Filepath file) {
        try {
            file.deleteIfExists();
            return true;
        } catch (IOException e) {
            log.debug("Could not remove {}", file, e);
            return false;
        }
    }
}
