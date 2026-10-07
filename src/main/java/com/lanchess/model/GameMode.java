package com.lanchess.model;

import java.io.Serializable;

/**
 * Sub-mode permainan Vs Friend.
 *   CLASSIC : perilaku lama - catur murni, tanpa kuis.
 *   QUIZ    : catur + mini-game kuis setiap 10 langkah penuh (lihat QuizManager).
 *
 * Dikirim client -> server via SET_MODE pada awal koneksi. Server memvalidasi
 * mode yang dipilih client harus sama dengan mode yang dikonfigurasi host
 * (lihat GameServer.configure).
 */
public enum GameMode implements Serializable {
    CLASSIC,
    QUIZ;

    /** Parsing aman dari string; default CLASSIC kalau null/tidak dikenal. */
    public static GameMode fromString(String s) {
        if (s == null) return CLASSIC;
        try {
            return GameMode.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return CLASSIC;
        }
    }
}