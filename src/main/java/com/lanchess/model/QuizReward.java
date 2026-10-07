package com.lanchess.model;

import java.io.Serializable;

/**
 * Hadiah untuk pemenang kuis.
 *   TIME_BONUS   : +30 detik ke jam pemain (WAJIB, MVP).
 *   FREE_CAPTURE : hapus 1 pion lawan (OPSIONAL - kalau pion lawan masih ada).
 *
 * Kalau pemenang kuis tidak bisa langsung memakai reward (mis. FREE_CAPTURE
 * saat bukan gilirannya), reward disimpan sebagai pending di QuizManager
 * dan di-apply saat giliran pemain itu tiba.
 */
public enum QuizReward implements Serializable {
    TIME_BONUS,
    FREE_CAPTURE;

    /** Nilai time bonus dalam milidetik (const di enum biar gampang diubah). */
    public static final long TIME_BONUS_MS = 30_000L;
}