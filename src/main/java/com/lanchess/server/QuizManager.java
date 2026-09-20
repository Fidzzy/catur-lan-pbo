package com.lanchess.server;

import com.lanchess.model.GameMode;
import com.lanchess.model.Message;
import com.lanchess.model.MessageType;
import com.lanchess.model.PlayerColor;
import com.lanchess.model.Quiz;
import com.lanchess.model.QuizResult;
import com.lanchess.model.QuizReward;

import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Mengelola satu ronde kuis pada satu waktu.
 *
 * Server-otoritatif sepenuhnya:
 *   - server membuat soal (dari QuestionBank)
 *   - server menyimpan waktu mulai (System.currentTimeMillis())
 *   - server menentukan pemenang dari waktu KEDATANGAN jawaban benar pertama
 *     (client tidak pernah dipercaya soal timestamp)
 *   - server yang apply reward (time bonus langsung, free capture via pending)
 *
 * Thread-safety: semua method public synchronized. Scheduler timeout
 * memanggil onTimeout() yang juga synchronized - aman balapan dengan
 * submitAnswer() dari thread ClientHandler lain.
 */
public class QuizManager {

    private final GameServer server;
    private final Random random = new Random();
    private final List<Quiz> questionBank;
    private final ScheduledExecutorService scheduler;

    // ===== state per-ronde (di-null-kan setelah selesai) =====
    private Quiz currentQuiz;
    private long quizStartMillis;
    private boolean[] hasAnswered;                 // index by PlayerColor.ordinal()
    private PlayerColor fastestCorrect;            // null = belum ada yang benar
    private long fastestCorrectElapsedMs;
    private ScheduledFuture<?> timeoutFuture;

    // ===== reward pending (kalau pemenang bukan pemain yang sedang giliran) =====
    private QuizReward pendingReward;
    private PlayerColor pendingRewardFor;

    public QuizManager(GameServer server) {
        this.server = server;
        this.questionBank = QuestionBank.load();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "QuizManager-Timer");
            t.setDaemon(true);
            return t;
        });
    }

    /** True kalau ada kuis yang sedang berjalan (dipakai sebagai guard trigger). */
    public synchronized boolean isQuizActive() {
        return currentQuiz != null;
    }

    /**
     * Mulai ronde kuis baru. Dipanggil dari GameServer.maybeTriggerQuiz()
     * SETELAH memastikan game masih aktif & tidak ada kuis lain.
     *
     * Efek samping: broadcast QUIZ_START ke kedua client, dan (pemanggil
     * yang bertanggung jawab) pause clock - lihat GameServer.maybeTriggerQuiz().
     */
    public synchronized void startQuiz() {
        if (currentQuiz != null) return;
        currentQuiz = questionBank.get(random.nextInt(questionBank.size()));
        quizStartMillis = System.currentTimeMillis();
        hasAnswered = new boolean[PlayerColor.values().length];
        fastestCorrect = null;
        fastestCorrectElapsedMs = Long.MAX_VALUE;

        System.out.println("[QuizManager] Mulai kuis: " + currentQuiz);

        Message start = new Message(MessageType.QUIZ_START, currentQuiz);
        server.broadcastToAll(start);

        // timeout server-side
        final Quiz quizRef = currentQuiz;   // capture untuk guard
        timeoutFuture = scheduler.schedule(() -> {
            synchronized (QuizManager.this) {
                if (currentQuiz == quizRef) onTimeoutLocked();
            }
        }, currentQuiz.getTimeLimitMs(), TimeUnit.MILLISECONDS);
    }

    /**
     * Terima jawaban dari client. Pemenang ditentukan dari jawaban BENAR
     * pertama (elapsed terkecil dari waktu mulai kuis). Kalau dua client
     * menjawab benar hampir bersamaan, yang sampai duluan di server menang.
     */
    public synchronized void submitAnswer(PlayerColor color, int answerIndex) {
        if (currentQuiz == null) return;
        if (hasAnswered[color.ordinal()]) return;   // sudah jawab, abaikan

        long elapsed = System.currentTimeMillis() - quizStartMillis;
        hasAnswered[color.ordinal()] = true;

        if (currentQuiz.isCorrect(answerIndex) && elapsed < fastestCorrectElapsedMs) {
            fastestCorrect = color;
            fastestCorrectElapsedMs = elapsed;
            System.out.println("[QuizManager] Jawaban benar dari " + color + " pada " + elapsed + "ms");
        } else {
            System.out.println("[QuizManager] Jawaban dari " + color + ": index " + answerIndex
                    + (currentQuiz.isCorrect(answerIndex) ? " (benar, tapi bukan tercepat)" : " (salah)"));
        }

        // kalau dua-duanya sudah menjawab, tidak perlu tunggu timeout
        boolean bothAnswered = true;
        for (boolean b : hasAnswered) if (!b) bothAnswered = false;
        if (bothAnswered) {
            finalizeQuizLocked("Kedua pemain sudah menjawab");
        }
    }

    /**
     * Dipanggil server ketika ClientHandler disconnect di tengah kuis.
     * Yang disconnect otomatis dianggap KALAH (tidak dapat reward), dan
     * lawannya otomatis menang KALAU lawan sudah menjawab benar. Kalau
     * lawan belum jawab benar, kuis langsung dibatalkan tanpa winner.
     */
    public synchronized void onPlayerDisconnect(PlayerColor disconnected) {
        if (currentQuiz == null) return;
        System.out.println("[QuizManager] " + disconnected + " disconnect di tengah kuis.");
        if (fastestCorrect != null && fastestCorrect != disconnected) {
            finalizeQuizLocked("Lawan disconnect");
        } else {
            // tidak ada winner valid, batalkan
            currentQuiz = null;
            cancelTimeoutLocked();
            Message result = new Message(MessageType.QUIZ_RESULT,
                    new QuizResult(null, currentQuizCorrectIndexFallback(), null,
                            "Kuis dibatalkan (pemain disconnect)"));
            server.broadcastToAll(result);
            server.resumeClockAfterQuiz();
        }
    }

    /** Dipanggil dari scheduler timeout - sudah di-synchronized oleh caller. */
    private void onTimeoutLocked() {
        finalizeQuizLocked("Waktu habis");
    }

    /** Ambil correctIndex terakhir sebelum quiz di-null-kan (untuk pesan hasil). */
    private int currentQuizCorrectIndexFallback() {
        return (currentQuiz != null) ? currentQuiz.getCorrectIndex() : -1;
    }

    /**
     * Selesaikan ronde kuis: tentukan winner, bangun QuizResult, broadcast,
     * apply reward (langsung atau pending), resume clock.
     */
    private void finalizeQuizLocked(String reason) {
        Quiz finished = currentQuiz;
        PlayerColor winner = fastestCorrect;
        currentQuiz = null;
        cancelTimeoutLocked();

        QuizReward reward = null;
        String message;
        if (winner == null) {
            message = reason + " - tidak ada yang menjawab benar";
        } else {
            reward = QuizReward.TIME_BONUS;   // MVP: time bonus dulu
            message = winner + " menang kuis (+30 detik)";
        }

        QuizResult result = new QuizResult(winner, finished.getCorrectIndex(), reward, message);
        System.out.println("[QuizManager] Kuis selesai: " + result);
        server.broadcastToAll(new Message(MessageType.QUIZ_RESULT, result));

        if (winner != null && reward != null) {
            applyRewardLocked(winner, reward);
        }

        server.resumeClockAfterQuiz();
    }

    /**
     * Terapkan reward ke pemenang. TIME_BONUS selalu instan (tidak butuh
     * giliran). FREE_CAPTURE (opsional nanti) butuh giliran - disimpan
     * sebagai pending kalau bukan giliran pemenang.
     */
    private void applyRewardLocked(PlayerColor winner, QuizReward reward) {
        switch (reward) {
            case TIME_BONUS -> server.addTimeBonus(winner, QuizReward.TIME_BONUS_MS);
            case FREE_CAPTURE -> {
                // TODO (opsional): simpan sebagai pendingReward, apply saat giliran winner tiba.
                // MVP: belum diimplementasikan; fallback ke time bonus.
                System.out.println("[QuizManager] FREE_CAPTURE belum diimplementasi, fallback ke TIME_BONUS.");
                server.addTimeBonus(winner, QuizReward.TIME_BONUS_MS);
            }
        }
    }

    private void cancelTimeoutLocked() {
        if (timeoutFuture != null) {
            timeoutFuture.cancel(false);
            timeoutFuture = null;
        }
    }

    /** Pending reward yang menunggu giliran - dipakai GameServer saat turn berubah. */
    public synchronized QuizReward consumePendingRewardFor(PlayerColor color) {
        if (pendingRewardFor == color && pendingReward != null) {
            QuizReward r = pendingReward;
            pendingReward = null;
            pendingRewardFor = null;
            return r;
        }
        return null;
    }
}