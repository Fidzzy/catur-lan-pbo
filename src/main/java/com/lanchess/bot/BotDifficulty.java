package com.lanchess.bot;

/**
 * Preset tingkat kesulitan bot berdasarkan rating ELO asli, memetakan ke
 * opsi UCI "UCI_LimitStrength" + "UCI_Elo" Stockfish (rentang valid
 * 1320-3190 di Stockfish 16, diverifikasi lewat "uci" handshake). Selain
 * ELO, tiap preset juga punya movetime (berapa lama engine berpikir per
 * langkah, dalam milidetik).
 */
public enum BotDifficulty {

    BEGINNER("Pemula", 1320, 300),
    EASY("Mudah", 1500, 500),
    MEDIUM("Menengah", 1800, 800),
    HARD("Sulit", 2200, 1200),
    EXPERT("Ahli", 2600, 1800),
    MAXIMUM("Maksimal", 3190, 2500);

    private final String label;
    private final int eloRating;
    private final int moveTimeMs;

    BotDifficulty(String label, int eloRating, int moveTimeMs) {
        this.label = label;
        this.eloRating = eloRating;
        this.moveTimeMs = moveTimeMs;
    }

    public String getLabel() {
        return label;
    }

    public int getEloRating() {
        return eloRating;
    }

    public int getMoveTimeMs() {
        return moveTimeMs;
    }

    @Override
    public String toString() {
        return eloRating + " ELO - " + label;
    }
}
