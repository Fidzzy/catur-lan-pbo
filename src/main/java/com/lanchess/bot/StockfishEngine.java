package com.lanchess.bot;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Mengelola satu proses engine UCI eksternal (Stockfish, atau engine UCI
 * lain yang kompatibel) sebagai child process, dan berkomunikasi lewat
 * stdin/stdout memakai protokol UCI (Universal Chess Interface) - protokol
 * teks baris-per-baris standar yang dipahami hampir semua engine catur.
 *
 * TIDAK ada library networking/engine eksternal yang dipakai di sini -
 * murni java.lang.ProcessBuilder + stream teks biasa, konsisten dengan
 * prinsip proyek ini (raw socket untuk networking, raw process pipe untuk
 * engine eksternal).
 *
 * PENTING - THREAD SAFETY: instance ini TIDAK aman dipanggil dari banyak
 * thread bersamaan (satu proses UCI hanya menangani satu request
 * "go"/"bestmove" pada satu waktu). Pemanggil (BotGameController) WAJIB
 * memastikan semua pemanggilan method di sini terjadi berurutan dari SATU
 * background thread saja, bukan dari JavaFX Application Thread (supaya UI
 * tidak freeze menunggu engine berpikir).
 */
public class StockfishEngine {

    private static final long STARTUP_TIMEOUT_MS = 5000;

    private Process process;
    private BufferedWriter writer;
    private BufferedReader reader;
    private volatile boolean running = false;

    /**
     * Jalankan proses engine di path yang diberikan dan lakukan handshake UCI
     * standar: uci -> tunggu "uciok", isready -> tunggu "readyok".
     *
     * @param enginePath path absolut ke binary engine, atau nama command
     *                   kalau sudah ada di PATH sistem (mis. "stockfish")
     * @throws IOException kalau proses gagal dijalankan ATAU engine tidak
     *                      merespons handshake dalam batas waktu
     */
    public void start(String enginePath) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(enginePath);
        builder.redirectErrorStream(true);
        process = builder.start();

        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

        sendCommand("uci");
        waitForResponse("uciok");

        sendCommand("isready");
        waitForResponse("readyok");

        running = true;
    }

    /** Set kekuatan bermain engine (dipanggil sekali setelah start(), sebelum newGame()). */
    public void setSkillLevel(int level) throws IOException {
        int clamped = Math.max(0, Math.min(20, level));
        sendCommand("setoption name Skill Level value " + clamped);
        sendCommand("isready");
        waitForResponse("readyok");
    }

    /**
     * Alternatif setSkillLevel(): batasi kekuatan engine berdasarkan rating
     * ELO asli lewat opsi UCI_LimitStrength + UCI_Elo (rentang valid Stockfish
     * 16: 1320-3190, di-clamp otomatis kalau di luar itu). Lebih akurat
     * merepresentasikan "level 1500 ELO" dibanding Skill Level 0-20 yang
     * abstrak. Dipakai BotSetupController sesuai desain "Select ELO".
     */
    public void setElo(int elo) throws IOException {
        int clamped = Math.max(1320, Math.min(3190, elo));
        sendCommand("setoption name UCI_LimitStrength value true");
        sendCommand("setoption name UCI_Elo value " + clamped);
        sendCommand("isready");
        waitForResponse("readyok");
    }

    /** Reset internal engine (hash table, riwayat) - panggil di awal setiap game baru. */
    public void newGame() throws IOException {
        sendCommand("ucinewgame");
        sendCommand("isready");
        waitForResponse("readyok");
    }

    /**
     * Minta engine menghitung langkah terbaik untuk posisi FEN yang
     * diberikan. BLOCKING selama kurang lebih moveTimeMs (plus sedikit
     * overhead I/O) - WAJIB dipanggil dari background thread, bukan dari
     * JavaFX Application Thread.
     *
     * @return notasi UCI langkah terbaik, mis. "e2e4" atau "e7e8q" (promosi)
     */
    public String getBestMove(String fen, int moveTimeMs) throws IOException {
        sendCommand("position fen " + fen);
        sendCommand("go movetime " + moveTimeMs);

        String line;
        while ((line = readLine()) != null) {
            if (line.startsWith("bestmove")) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 2) {
                    throw new IOException("Respons bestmove tidak lengkap dari engine: " + line);
                }
                return parts[1]; // parts[0]="bestmove", parts[1]=notasi UCI, parts[2..]="ponder ..."
            }
            // baris lain (info depth ... score cp ... pv ...) diabaikan - itu
            // cuma log analisis internal engine, bukan hasil akhir
        }
        throw new IOException("Koneksi ke engine terputus sebelum menerima bestmove.");
    }

    /** Matikan proses engine dengan rapi (quit UCI dulu, baru force-kill kalau perlu). */
    public void quit() {
        running = false;
        try {
            if (writer != null) {
                sendCommand("quit");
            }
        } catch (IOException ignored) {
        }
        if (process != null) {
            try {
                boolean exited = process.waitFor(1, TimeUnit.SECONDS);
                if (!exited) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }

    public boolean isRunning() {
        return running && process != null && process.isAlive();
    }

    // =========================================================================
    // Helper komunikasi UCI mentah
    // =========================================================================

    private void sendCommand(String command) throws IOException {
        writer.write(command);
        writer.newLine();
        writer.flush();
    }

    private String readLine() throws IOException {
        return reader.readLine();
    }

    /** Baca baris demi baris sampai menemukan salah satu yang PERSIS/mengandung token yang ditunggu. */
    private void waitForResponse(String expectedToken) throws IOException {
        long deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS;
        String line;
        while ((line = readLine()) != null) {
            if (line.trim().equals(expectedToken) || line.trim().startsWith(expectedToken)) {
                return;
            }
            if (System.currentTimeMillis() > deadline) {
                throw new IOException("Timeout menunggu respons '" + expectedToken + "' dari engine.");
            }
        }
        throw new IOException("Koneksi ke engine terputus saat menunggu '" + expectedToken + "'.");
    }
}
