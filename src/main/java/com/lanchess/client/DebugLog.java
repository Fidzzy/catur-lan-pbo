package com.lanchess.client;

import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.LocalTime;

/**
 * Log diagnosis sementara untuk melacak klik papan.
 * Menulis ke console DAN ke file click-debug.log di working directory.
 * HAPUS class ini beserta pemanggilnya setelah bug klik terpecahkan.
 */
public final class DebugLog {

    private static final String FILE = "click-debug.log";

    private DebugLog() {
    }

    public synchronized static void log(String tag, String message) {
        String line = "[%s][%s] %s".formatted(LocalTime.now().withNano(0), tag, message);
        System.out.println(line);
        try (PrintWriter out = new PrintWriter(new FileWriter(FILE, true))) {
            out.println(line);
        } catch (IOException ignored) {
        }
    }
}
