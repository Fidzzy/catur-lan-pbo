package com.lanchess.model;

import java.io.Serializable;

/**
 * State machine status permainan.
 *
 * Alur transisi normal:
 *   WAITING_FOR_PLAYER -> PLAYING -> CHECK -> PLAYING -> ... -> CHECKMATE / STALEMATE
 *
 * WAITING_FOR_PLAYER : server baru punya 1 client, menunggu client kedua connect
 * PLAYING            : kedua client sudah connect, permainan berjalan normal, tidak ada raja yang diskak
 * CHECK              : giliran pemain saat ini rajanya sedang diskak (harus keluar dari skak)
 * CHECKMATE          : pemain yang sedang giliran diskak dan tidak ada langkah legal -> game over, lawan menang
 * STALEMATE          : pemain yang sedang giliran TIDAK diskak tapi tidak ada langkah legal -> game over, seri
 * DRAW               : seri karena alasan lain (mis. threefold repetition / 50-move rule) - opsional
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
     * Salah satu pemain kehabisan waktu di jam caturnya. Sama seperti
     * CHECKMATE, getCurrentTurn() pada state saat status ini di-set adalah
     * pemain yang KALAH (waktunya habis), bukan pemenangnya.
     */
    TIMEOUT,
    DISCONNECTED
}
