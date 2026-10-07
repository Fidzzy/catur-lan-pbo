package com.lanchess.model;

import java.io.Serializable;

/**
 * Hasil akhir satu ronde kuis, dikirim server ke kedua client via QUIZ_RESULT.
 * Client memakai ini untuk menampilkan siapa yang menang + reward apa.
 *
 * winner == null -> tidak ada yang jawab benar (timeout / semua salah).
 * reward == null -> tidak ada reward yang diberikan (mengikuti winner == null).
 */
public class QuizResult implements Serializable {
    private static final long serialVersionUID = 1L;

    private final PlayerColor winner;       // null kalau seri/tidak ada
    private final int correctIndex;         // index jawaban benar (untuk highlight di UI)
    private final QuizReward reward;        // null kalau tidak ada winner
    private final String message;           // teks pendek untuk ditampilkan di overlay

    public QuizResult(PlayerColor winner, int correctIndex, QuizReward reward, String message) {
        this.winner = winner;
        this.correctIndex = correctIndex;
        this.reward = reward;
        this.message = message;
    }

    public PlayerColor getWinner() { return winner; }
    public int getCorrectIndex() { return correctIndex; }
    public QuizReward getReward() { return reward; }
    public String getMessage() { return message; }

    @Override
    public String toString() {
        return "QuizResult{winner=" + winner + ", correct=" + correctIndex
                + ", reward=" + reward + ", msg='" + message + "'}";
    }
}