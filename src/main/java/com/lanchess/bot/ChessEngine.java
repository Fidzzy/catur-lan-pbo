package com.lanchess.bot;

import java.io.IOException;

/**
 * Kontrak engine catur: menghitung langkah terbaik dan mengevaluasi posisi
 * dari notasi FEN. Implementasi saat ini adalah {@link StockfishEngine}
 * (proses UCI eksternal), tapi controller hanya bergantung pada interface
 * ini sehingga engine bisa diganti tanpa mengubah gameplay (polimorfisme).
 */
public interface ChessEngine {

    /** Jalankan engine dan lakukan handshake awal. */
    void start(String enginePath) throws IOException;

    /** Set kekuatan abstrak 0-20 (opsi UCI Skill Level). */
    void setSkillLevel(int level) throws IOException;

    /** Batasi kekuatan berdasarkan rating ELO (opsi UCI_LimitStrength + UCI_Elo). */
    void setElo(int elo) throws IOException;

    /** Reset state internal engine untuk game baru. */
    void newGame() throws IOException;

    /**
     * Hitung langkah terbaik untuk posisi FEN. BLOCKING - wajib dipanggil
     * dari background thread, bukan JavaFX Application Thread.
     *
     * @return notasi UCI, mis. "e2e4" atau "e7e8q" (promosi)
     */
    String getBestMove(String fen, int moveTimeMs) throws IOException;

    /**
     * Evaluasi numerik posisi (centipawn, dari sudut pandang sisi yang
     * sedang jalan di FEN) tanpa mengeksekusi langkahnya.
     */
    int evaluateCentipawns(String fen, int moveTimeMs) throws IOException;

    /** Matikan engine dengan rapi. */
    void quit();

    /** True kalau proses engine hidup dan siap menerima perintah. */
    boolean isRunning();
}
