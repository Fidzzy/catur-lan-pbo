package com.lanchess.model;

import java.io.Serializable;
import java.util.List;

/**
 * Satu soal kuis multiple-choice yang dikirim server ke kedua client.
 *
 * correctIndex & timeLimitMs tetap diserialisasi (bukan disembunyikan)
 * karena ini LAN game antar teman - bukan skenario adversarial. Server
 * tetap otoritatif: client hanya kirim QUIZ_ANSWER (index pilihan),
 * server yang menentukan benar/salah & pemenang berdasarkan waktu
 * kedatangan (System.currentTimeMillis() di server, BUKAN timestamp client).
 */
public class Quiz implements Serializable {
    private static final long serialVersionUID = 1L;

    private final String question;
    private final List<String> options;   // harus berisi tepat 4 opsi
    private final int correctIndex;       // 0..3
    private final long timeLimitMs;       // mis. 8000 = 8 detik

    public Quiz(String question, List<String> options, int correctIndex, long timeLimitMs) {
        if (options == null || options.size() != 4) {
            throw new IllegalArgumentException("Quiz butuh tepat 4 opsi, dapat: "
                    + (options == null ? "null" : options.size()));
        }
        if (correctIndex < 0 || correctIndex >= options.size()) {
            throw new IllegalArgumentException("correctIndex di luar range: " + correctIndex);
        }
        this.question = question;
        this.options = List.copyOf(options);
        this.correctIndex = correctIndex;
        this.timeLimitMs = timeLimitMs;
    }

    public String getQuestion() { return question; }
    public List<String> getOptions() { return options; }
    public int getCorrectIndex() { return correctIndex; }
    public long getTimeLimitMs() { return timeLimitMs; }

    public boolean isCorrect(int answerIndex) {
        return answerIndex == correctIndex;
    }

    @Override
    public String toString() {
        return "Quiz{q='" + question + "', correct=" + correctIndex
                + ", limit=" + timeLimitMs + "ms}";
    }
}