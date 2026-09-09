package com.lanchess.bot;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Mencoba menebak lokasi binary Stockfish di sistem, supaya pemain tidak
 * harus tahu path lengkap secara manual saat instalasi Stockfish standar
 * lewat package manager (apt/brew/choco) sudah menaruhnya di PATH.
 *
 * Kalau auto-detect gagal, GUI tetap menyediakan field untuk pemain
 * memasukkan path manual (mis. hasil download langsung dari
 * stockfishchess.org tanpa lewat package manager).
 */
public final class StockfishLocator {

    private StockfishLocator() {
    }

    private static final List<String> COMMON_PATHS = List.of(
            "/usr/games/stockfish",           // Debian/Ubuntu (apt install stockfish)
            "/usr/local/bin/stockfish",        // Linux/macOS manual install
            "/opt/homebrew/bin/stockfish",     // macOS Apple Silicon (brew)
            "/usr/bin/stockfish",
            "C:\\Program Files\\Stockfish\\stockfish.exe",
            "C:\\stockfish\\stockfish.exe"
    );

    /**
     * @return path yang bisa langsung dipakai StockfishEngine.start(), atau
     *         empty kalau tidak ditemukan di lokasi umum manapun.
     */
    public static Optional<String> autoDetect() {
        if (isRunnable("stockfish")) return Optional.of("stockfish");
        if (isRunnable("stockfish.exe")) return Optional.of("stockfish.exe");

        for (String path : COMMON_PATHS) {
            if (new File(path).canExecute()) {
                return Optional.of(path);
            }
        }
        return Optional.empty();
    }

    /** Cek apakah sebuah command bisa langsung dijalankan (ada di PATH sistem) tanpa perlu path absolut. */
    public static boolean isRunnable(String command) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            try (OutputStream os = p.getOutputStream()) {
                os.write("quit\n".getBytes());
                os.flush();
            }
            boolean exited = p.waitFor(800, TimeUnit.MILLISECONDS);
            if (!exited) {
                p.destroyForcibly();
            }
            return true; // kalau ProcessBuilder.start() tidak melempar exception, command-nya valid
        } catch (IOException e) {
            return false; // command tidak ditemukan / tidak bisa dieksekusi
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
