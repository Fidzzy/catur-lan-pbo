package com.lanchess.model;

import java.io.Serializable;

/**
 * Preset kontrol waktu (time control) untuk jam catur. "Sudden death" murni
 * (tanpa increment) - dipilih demi kesederhanaan untuk lingkup proyek ini.
 */
public enum TimeControl implements Serializable {
    BULLET_1(1, "1 menit"),
    BLITZ_3(3, "3 menit"),
    BLITZ_5(5, "5 menit"),
    RAPID_10(10, "10 menit"),
    RAPID_15(15, "15 menit"),
    RAPID_30(30, "30 menit"),
    UNLIMITED(0, "Tanpa batas");

    private final int minutes;
    private final String label;

    TimeControl(int minutes, String label) {
        this.minutes = minutes;
        this.label = label;
    }

    public long getInitialMillis() {
        return minutes * 60_000L;
    }

    public boolean isUnlimited() {
        return this == UNLIMITED;
    }

    public String getLabel() {
        return label;
    }

    @Override
    public String toString() {
        return label;
    }
}
