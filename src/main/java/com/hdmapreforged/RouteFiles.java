package com.hdmapreforged;

import java.io.*;
import java.util.*;
import java.util.function.*;
import java.util.zip.*;
import lombok.*;

/**
 * The route planner's tables (passages, gates, the collision map, ...): a map version's route.zip from the map server,
 * so they are fixed without a plugin update; before one is given (tests, development tools) the classpath.
 */
final class RouteFiles {
    /** Where the route tables lie on the classpath; a route.zip holds them by their file names alone. */
    static final String DIR = "/com/hdmapreforged/route/";
    static final int MAX_FILE_BYTES = 16 * 1024 * 1024, MAX_TOTAL_BYTES = 32 * 1024 * 1024, MAX_FILES = 100;
    static volatile Map<String, byte[]> files;
    /** Counts the route.zips used, so tables read from them are read again. */
    static volatile int generation;

    /** Uses a map version's route.zip: its files by name ("links.tsv", "collision.zip"). */
    static void use(byte[] zip) throws IOException {
        use(unzip(zip));
    }

    /** Uses these files; null: the classpath's (none in a player's plugin). Tables are read again. */
    static void use(Map<String, byte[]> given) {
        files = given;
        generation++;
    }

    /** None while the plugin is off; the next start uses its version's kept route.zip again. */
    static void clear() {
        use((Map<String, byte[]>) null);
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

    /**
     * What is read from a table, read again once another route.zip is used; without a lock once read (the planner asks
     * in its inner loop).
     */
    @RequiredArgsConstructor
    static final class Table<T> {
        final Supplier<T> read;
        volatile int readAt = -1;
        volatile T value;

        T get() {
            int now = generation;
            if (readAt != now) {
                synchronized (this) {
                    if (readAt != now) {
                        try {
                            value = read.get();
                        } finally {
                            // Once per route.zip, also when the read fails: then what was read before stays.
                            readAt = now;
                        }
                    }
                }
            }
            return value;
        }
    }
}
