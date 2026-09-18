package com.lanchess.client;

import javafx.scene.media.AudioClip;

import java.util.HashMap;
import java.util.Map;

/**
 * Efek suara game (langkah, capture, skak, menang/kalah, notifikasi chat)
 * dari file WAV di {@code /com/lanchess/client/sounds/*.wav}.
 *
 * Aman dipanggil kapan saja: kalau file hilang / JavaFX belum siap / headless,
 * suara dilewati diam-diam tanpa mengganggu permainan. Bisa di-mute lewat
 * tombol 🔊/🔇 di layar permainan (flag global di sini).
 */
public final class SoundManager {

    private static final Map<String, AudioClip> CACHE = new HashMap<>();
    private static boolean muted = false;

    private SoundManager() {
    }

    public static boolean isMuted() {
        return muted;
    }

    public static void setMuted(boolean muted) {
        SoundManager.muted = muted;
    }

    public static void playMove() {
        play("move.wav", 1.0);
    }

    public static void playCapture() {
        play("capture.wav", 1.0);
    }

    public static void playCheck() {
        play("check.wav", 1.0);
    }

    public static void playWin() {
        play("win.wav", 1.0);
    }

    public static void playLose() {
        play("lose.wav", 1.0);
    }

    public static void playNotify() {
        play("notify.wav", 0.8);
    }

    private static void play(String file, double volume) {
        if (muted) return;
        try {
            AudioClip clip = CACHE.get(file);
            if (clip == null) {
                var url = SoundManager.class.getResource("/com/lanchess/client/sounds/" + file);
                if (url == null) return;
                clip = new AudioClip(url.toExternalForm());
                CACHE.put(file, clip);
            }
            clip.setVolume(volume);
            clip.play();
        } catch (Throwable ignored) {
            // Headless / toolkit belum jalan / format tak didukung - main tanpa suara.
        }
    }
}
