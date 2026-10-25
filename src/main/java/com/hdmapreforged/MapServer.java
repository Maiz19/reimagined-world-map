package com.hdmapreforged;

import com.google.gson.*;
import java.awt.image.*;
import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.function.*;
import java.util.regex.*;
import lombok.*;
import lombok.extern.slf4j.*;
import okhttp3.*;

/**
 * Our own map server: the map's tiles, its list of maps, region table and places and its version, all drawn from the
 * game's own data, its NPC pictures, and its lists of where NPCs stand and what shops keep ({@link WorldData}). A
 * download sends only the address of what is wanted; nothing is ever sent about the player. The host is fixed; only in
 * RuneLite's developer mode does the system property {@code hdmap.server} point elsewhere.
 */
@Slf4j
@RequiredArgsConstructor
final class MapServer {
    /** The live map server; its website shows the same map. */
    static final String HOST = "https://osrsmapreforged.online";
    static final int MAX_BODY_BYTES = 8 * 1024 * 1024;
    static final Pattern SAFE_VERSION = Pattern.compile("[A-Za-z0-9]([A-Za-z0-9_.-]{0,38}[A-Za-z0-9_-])?");
    static volatile HttpUrl base = HttpUrl.get(HOST);

    final OkHttpClient http;
    final Gson gson;

    /** In developer mode {@code -Dhdmap.server=<url>} when given, else the fixed host. */
    static void use(boolean developerMode) {
        var other = developerMode ? HttpUrl.parse(System.getProperty("hdmap.server", "")) : null;
        base = other != null ? other : HttpUrl.get(HOST);
    }

    static boolean isSafeVersion(String version) {
        return version != null && SAFE_VERSION.matcher(version).matches() && !version.contains("..");
    }

    static HttpUrl url(String... segments) {
        var url = base.newBuilder().addPathSegment("v1");
        for (String segment : segments) {
            url.addPathSegment(segment);
        }
        return url.build();
    }

    static HttpUrl tile(String version, TileCache.Key key) {
        return url("tiles", version, Integer.toString(key.map), Integer.toString(key.zoom),
            key.plane + "_" + key.x + "_" + key.y + ".png");
    }

    /** The server's current versions; each null when it cannot be had. */
    @RequiredArgsConstructor
    static final class Versions {
        static final Versions NONE = new Versions(null, null);
        final String map;
        /** Of the lists: where NPCs stand, the shops. */
        final String data;
    }

    void versions(Consumer<Versions> callback) {
        get(url("version.json"), body -> {
            JsonObject o = gson.fromJson(text(body), JsonObject.class);
            return new Versions(safeVersion(o, "map"), safeVersion(o, "data"));
        }, callback, Versions.NONE, MAX_BODY_BYTES);
    }

    /** What a map version's tiles changed since an earlier version: that version, and the tiles' paths. */
    @RequiredArgsConstructor
    static final class Changes {
        final String from;
        final Set<String> changed, removed;
    }

    static final Pattern TILE_PATH = Pattern.compile("-?\\d{1,6}/-?\\d/[0-3]_\\d{1,6}_\\d{1,6}\\.png");

    /**
     * The changes of {@code version} since {@code have} when the server lists them ("since"), else since the version
     * before; null when the server has none (its first version, or not reachable).
     */
    void changes(String version, String have, Consumer<Changes> callback) {
        get(isSafeVersion(version) ? url("tiles", version, "changed.json") : null, body -> {
            JsonObject o = gson.fromJson(text(body), JsonObject.class);
            var since = o != null && o.get("since") instanceof JsonObject ? o.getAsJsonObject("since") : null;
            if (since != null && have != null && since.get(have) instanceof JsonObject) {
                return new Changes(have, paths(since.getAsJsonObject(have), "changed"),
                    paths(since.getAsJsonObject(have), "removed"));
            }
            String from = o != null && o.has("from") ? o.get("from").getAsString() : null;
            return isSafeVersion(from) ? new Changes(from, paths(o, "changed"), paths(o, "removed")) : null;
        }, callback, null, MAX_BODY_BYTES);
    }

    static Set<String> paths(JsonObject o, String key) {
        var paths = new HashSet<String>();
        var list = o.get(key);
        for (var e : list != null && list.isJsonArray() ? list.getAsJsonArray() : new JsonArray()) {
            String path = e.isJsonPrimitive() ? e.getAsString() : "";
            // At most 200,000 paths.
            if (TILE_PATH.matcher(path).matches() && paths.size() < 200_000) {
                paths.add(path);
            }
        }
        return paths;
    }

    /** A picture's bytes (PNG) as the server sent them; null when none. */
    static BufferedImage decode(byte[] bytes) {
        try {
            return bytes == null ? null
                : TileCache.readImage(bytes, (width, height) -> width <= MAX_PICTURE_SIDE && height <= MAX_PICTURE_SIDE);
        } catch (IOException | RuntimeException e) {
            log.debug("Unusable picture", e);
            return null;
        }
    }

    /** An NPC's picture as the server's PNG bytes, for keeping; null when there is none. Calls back on any thread. */
    void npcPictureBytes(String version, int id, Consumer<byte[]> callback) {
        // At most 256 KB.
        get(isSafeVersion(version) && id >= 0 ? url("npcs", version, id + ".png") : null, body -> body, callback, null, 256 * 1024);
    }

    static final int MAX_PICTURE_SIDE = 1024;

    static String safeVersion(JsonObject versions, String which) {
        String version = versions != null && versions.has(which) ? versions.get(which).getAsString() : null;
        return isSafeVersion(version) ? version : null;
    }

    /** A data version's file ({@link WorldData#FILE}); null when it cannot be had. */
    void data(String version, String name, Consumer<byte[]> callback) {
        get(url("data", version, name + ".json"), body -> body, callback, null, MAX_BODY_BYTES);
    }

    /** UTF-8 text, read as it is used. */
    static Reader text(byte[] bytes) {
        return new InputStreamReader(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8);
    }

    /**
     * A map version's files, made once per version from the game's data: its map list, which map draws each region
     * several maps claim, its places (what the map shows and the planner uses, {@link MapData#read}) and the planner's
     * tables ({@link com.hdmapreforged.RouteFiles}).
     */
    static final List<String> MAP_FILES = List.of("maps.json", "regions.tsv", "places.json", "route.zip");

    /** One of {@link #MAP_FILES} of a map version, as sent; null when it cannot be had. Calls back on any thread. */
    void mapFile(String version, String name, Consumer<byte[]> callback) {
        get(isSafeVersion(version) && MAP_FILES.contains(name) ? url("maps", version, name) : null, body -> body, callback, null, MAX_BODY_BYTES);
    }

    /**
     * Calls back once: with the parsed answer, or with {@code fallback} when there is none; an answer over {@code max}
     * bytes is none. A null {@code url} (no version or file to ask for) calls back with {@code fallback} at once.
     */
    <T> void get(HttpUrl url, Function<byte[], T> parse, Consumer<T> callback, T fallback, int max) {
        if (url == null) {
            callback.accept(fallback);
            return;
        }
        http.newCall(new Request.Builder().url(url).build()).enqueue(new Callback() {
            public void onFailure(Call call, IOException e) {
                log.debug("Map server request failed: {}", url, e);
                callback.accept(fallback);
            }

            public void onResponse(Call call, Response response) {
                T value = fallback;
                try (var body = response.body()) {
                    if (response.isSuccessful() && body != null) {
                        value = parse.apply(TileCache.readBody(body, max));
                    }
                } catch (IOException | RuntimeException e) {
                    log.debug("Unusable answer from {}", url, e);
                }
                callback.accept(value == null ? fallback : value);
            }
        });
    }
}
