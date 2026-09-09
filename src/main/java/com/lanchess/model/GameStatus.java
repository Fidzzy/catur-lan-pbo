package com.lanchess.model;

import java.io.Serializable;

/**
 * State machine status permainan.
 *
 * Alur transisi normal:
 *   WAITING_FOR_PLAYER -> PLAYING -> CHECK -> PLAYING -> ... -> CHECKMATE / STALEMATE / TIMEOUT / RESIGNATION / DRAW
 *
 * WAITING_FOR_PLAYER : server baru punya 1 client, menunggu client kedua connect
 * PLAYING            : kedua client sudah connect, permainan berjalan normal, tidak ada raja yang diskak
 * CHECK              : giliran pemain saat ini rajanya sedang diskak (harus keluar dari skak)
 * CHECKMATE          : pemain yang sedang giliran diskak dan tidak ada langkah legal -> game over, lawan menang
 * STALEMATE          : pemain yang sedang giliran TIDAK diskak tapi tidak ada langkah legal -> game over, seri
 * DRAW               : seri karena threefold repetition / 50-move rule / kesepakatan (lihat GameState.getDrawReason())
 * TIMEOUT            : salah satu pemain kehabisan waktu (lihat GameState.getLoserColor())
 * RESIGNATION        : salah satu pemain mengundurkan diri (lihat GameState.getLoserColor())
 * DISCONNECTED       : salah satu client terputus, game dihentikan
 */
public enum GameStatus implements Serializable {
    WAITING_FOR_PLAYER,
    PLAYING,
    CHECK,
    CHECKMATE,
    STALEMATE,
    DRAW,
    /**
     * Salah satu pemain kehabisan waktu di jam caturnya. Lihat
     * GameState.getLoserColor() untuk tahu pemain mana yang kalah.
     */
    TIMEOUT,
    /** Salah satu pemain mengundurkan diri. Lihat GameState.getLoserColor(). */
    RESIGNATION,
    DISCONNECTED
}
