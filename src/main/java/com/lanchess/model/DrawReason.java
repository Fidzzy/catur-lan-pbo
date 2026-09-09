package com.lanchess.model;

import java.io.Serializable;

/**
 * Alasan spesifik kenapa GameStatus.DRAW terjadi. Dipakai UI untuk
 * menampilkan pesan yang tepat ("Threefold repetition" vs "50-move rule"
 * vs "Kedua pemain setuju seri"), karena GameStatus.STALEMATE sudah
 * punya statusnya sendiri terpisah (bukan bagian dari enum ini).
 */
public enum DrawReason implements Serializable {
    AGREEMENT,
    THREEFOLD_REPETITION,
    FIFTY_MOVE_RULE
}
