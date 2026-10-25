package com.hdmapreforged;

import java.io.*;
import java.nio.charset.*;
import lombok.extern.slf4j.*;
import net.runelite.client.util.*;

/** The map server's map version as last seen, kept on disk so the next start shows it before the server answers. */
@Slf4j
final class KnownVersion {
    static final String FILE = "server-map-version.txt";

    /** Null when missing or unusable. Its first line; older plugins wrote when they asked on the second. */
    static String read(Filepath file) {
        if (!file.isFile()) {
            return null;
        }
        try {
            String version = new String(TileCache.read(file), StandardCharsets.UTF_8).split("\n")[0].trim();
            return MapServer.isSafeVersion(version) ? version : null;
        } catch (IOException e) {
            log.debug("Ignoring unreadable {}", file, e);
            return null;
        }
    }

    static void write(Filepath file, String version) {
        try {
            TileCache.writeAtomically(file, (version + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.debug("Could not save the map version", e);
        }
    }
}
