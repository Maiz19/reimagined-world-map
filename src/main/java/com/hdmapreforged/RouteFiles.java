package com.hdmapreforged;

import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.function.*;
import com.google.gson.*;
import java.util.zip.*;

/**
 * The route planner's files (passages, the collision map, the tables) of a map version's route.zip from the map server,
 * so they are fixed without a plugin update; before one is given (tests, development tools) the classpath.
 */
final class RouteFiles {
    /** Where the route tables lie on the classpath; a route.zip holds them by their file names alone. */
    static final String DIR = "/com/hdmapreforged/route/";
    static final int MAX_FILE_BYTES = 16 * 1024 * 1024, MAX_TOTAL_BYTES = 32 * 1024 * 1024, MAX_FILES = 100;
    static volatile Map<String, byte[]> files;
    static volatile RouteTables tables;
    /** Without a route.zip: the classpath's tables, set by tests and development tools (none in a player's plugin). */
    static volatile RouteTables classpath = new RouteTables();
    /** A version's tables for this thread alone, while its places are read before it is shown. */
    static final ThreadLocal<RouteTables> READING = new ThreadLocal<>();

    /** Uses a map version's route.zip: its files by name ("collision.zip"), its tables bound by {@code gson}. */
    static void use(byte[] zip, Gson gson) throws IOException {
        var read = unzip(zip);
        use(read, tables(gson, read));
    }

    /** Uses these files and tables; null: the classpath's (none in a player's plugin). */
    static synchronized void use(Map<String, byte[]> given, RouteTables read) {
        files = given;
        tables = read;
    }

    /**
     * Uses these files and tables if {@code current} still holds, checked under the lock of {@link #clear}: a stopped
     * run's never replace those of the run after it. False when not used.
     */
    static synchronized boolean use(Map<String, byte[]> given, RouteTables read, BooleanSupplier current) {
        if (!current.getAsBoolean()) {
            return false;
        }
        use(given, read);
        return true;
    }

    /** None while the plugin is off; the next start uses its version's kept route.zip again. */
    static synchronized void clear() {
        files = null;
        tables = null;
    }

    /** The tables in use: this thread's version being read, else the version's, else the classpath's. */
    static RouteTables tables() {
        var reading = READING.get();
        return reading != null ? reading : tables != null ? tables : classpath;
    }

    /** A route.zip's tables.json as Gson binds it. */
    static RouteTables tables(Gson gson, Map<String, byte[]> files) throws IOException {
        var json = files.get(RouteTables.FILE);
        if (json == null) {
            throw new IOException("Missing " + RouteTables.FILE);
        }
        try {
            return gson.fromJson(new String(json, StandardCharsets.UTF_8), RouteTables.class).ready();
        } catch (RuntimeException e) {
            throw new IOException("Unreadable " + RouteTables.FILE, e);
        }
    }

    /** A route.zip's files by name, not used yet. */
    static Map<String, byte[]> unzip(byte[] zip) throws IOException {
        var read = new HashMap<String, byte[]>();
        long total = 0;
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                if (!entry.isDirectory()) {
                    var bytes = read(in, MAX_FILE_BYTES, entry.getName());
                    total += bytes.length;
                    if (total > MAX_TOTAL_BYTES || read.size() >= MAX_FILES) {
                        throw new IOException("Route data too large");
                    }
                    read.put(entry.getName().substring(entry.getName().lastIndexOf('/') + 1), bytes);
                }
            }
        }
        return read;
    }

    /** The rest of a zip entry; more than {@code max} bytes is an error, so a small zip never fills the memory. */
    static byte[] read(InputStream in, int max, String name) throws IOException {
        var bytes = in.readNBytes(max + 1);
        if (bytes.length > max) {
            throw new IOException("Route file too large: " + name);
        }
        return bytes;
    }

    /** Each line but {@code #} comments, split as {@code line.split("\t", limit)} does; closes {@code in}. */
    static void rows(InputStream in, int limit, Consumer<String[]> each) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (!line.startsWith("#")) {
                    each.accept(line.split("\t", limit));
                }
            }
        }
    }

    /** Whether the tables can be read: a route.zip is in use, or the classpath has them (tests, development tools). */
    static boolean available() {
        if (files != null) {
            return true;
        }
        try (InputStream in = RouteFiles.class.getResourceAsStream(CollisionMap.RESOURCE)) {
            return in != null;
        } catch (IOException e) {
            return false;
        }
    }

    /** A table by its resource path ("/com/hdmapreforged/route/links.tsv"); null when there is none. */
    static InputStream open(String resource) {
        var given = files;
        if (given == null) {
            return RouteFiles.class.getResourceAsStream(resource);
        }
        var bytes = given.get(resource.substring(resource.lastIndexOf('/') + 1));
        return bytes == null ? null : new ByteArrayInputStream(bytes);
    }

    /** The same, where a missing table is an error. */
    static InputStream require(String resource) throws IOException {
        InputStream in = open(resource);
        if (in == null) {
            throw new IOException("Missing " + resource);
        }
        return in;
    }
}
