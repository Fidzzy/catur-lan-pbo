package com.lanchess.client;

import com.lanchess.model.Quiz;
import com.lanchess.model.QuizResult;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Overlay kuis full-screen yang menutupi papan selama kuis berlangsung.
 *
 * Dipasang sebagai child StackPane DI ATAS root GameController, jadi tidak
 * mengganggu layout papan. Saat invisible (setManaged(false)), overlay ini
 * tidak di-layout dan tidak menangkap input - papan tetap normal di mode
 * CLASSIC (controller ini tetap instantiate overlay, tapi tidak pernah
 * menampilkan-nya kalau tidak ada QUIZ_START dari server).
 *
 * Tanggung jawab:
 *   - menampilkan soal + 4 opsi
 *   - countdown 8 detik (server-side timeout yang otoritatif - timer client
 *     hanya visual; kalau server bilang timeout, QUIZ_RESULT akan datang
 *     dan overlay di-update)
 *   - memanggil callback saat user memilih (yang meneruskan ke NetworkClient)
 *   - menampilkan hasil akhir (highlight jawaban benar + pesan pemenang)
 *     lalu auto-dismiss setelah 2 detik
 *
 * Thread-safety: show() dan showResult() HARUS dipanggil dari JavaFX
 * Application Thread (dibungkus Platform.runLater oleh GameController).
 */
public class QuizOverlay extends StackPane {

    private static final Duration RESULT_DISPLAY = Duration.seconds(2.2);

    private final Label questionLabel = new Label();
    private final Label timerLabel = new Label();
    private final Label resultLabel = new Label();
    private final VBox optionsBox = new VBox(8);
    private final List<Button> optionButtons = new ArrayList<>(4);

    private Timeline countdown;
    private Timeline dismissTimeline;
    private IntConsumer answerCallback;
    private boolean answered = false;
    private int correctIndex = -1;
    private int localAnswer = -1;

    public QuizOverlay() {
        setPickOnBounds(true);   // overlay full-screen menangkap klik supaya tidak tembus ke papan
        setStyle("-fx-background-color: rgba(0, 0, 0, 0.72);");
        setAlignment(Pos.CENTER);
        setVisible(false);
        setManaged(false);

        VBox card = new VBox(12);
        card.getStyleClass().add("card-panel");
        card.setAlignment(Pos.CENTER);
        card.setMaxWidth(440);
        card.setMinWidth(360);
        card.setPadding(new Insets(24));

        Label title = new Label("KUIS");
        title.setFont(Font.font("Serif", FontWeight.BOLD, 24));
        title.setTextFill(Color.web("#4A90E2"));

        questionLabel.setWrapText(true);
        questionLabel.setFont(Font.font(15));
        questionLabel.setTextFill(Color.WHITE);
        questionLabel.setMaxWidth(400);
        questionLabel.setAlignment(Pos.CENTER);

        timerLabel.setFont(Font.font("Serif", FontWeight.BOLD, 18));
        timerLabel.setTextFill(Color.web("#F5C542"));

        for (int i = 0; i < 4; i++) {
            final int idx = i;
            Button b = new Button();
            b.getStyleClass().add("pill-button");
            b.setMaxWidth(Double.MAX_VALUE);
            b.setWrapText(true);
            b.setOnAction(e -> onAnswerClicked(idx));
            optionButtons.add(b);
            optionsBox.getChildren().add(b);
        }
        optionsBox.setFillWidth(true);

        resultLabel.setFont(Font.font(13));
        resultLabel.setTextFill(Color.WHITE);
        resultLabel.setWrapText(true);
        resultLabel.setMaxWidth(400);
        resultLabel.setAlignment(Pos.CENTER);
        resultLabel.setVisible(false);

        card.getChildren().addAll(title, questionLabel, timerLabel, optionsBox, resultLabel);
        getChildren().add(card);
    }

    /**
     * Tampilkan soal. onAnswer dipanggil SEKALI saat user memilih index
     * (0..3). Tidak dipanggil kalau timeout - server yang akan mengirim
     * QUIZ_RESULT tanpa winner.
     */
    public void show(Quiz quiz, IntConsumer onAnswer) {
        if (quiz == null) return;

        stopCountdown();
        stopDismissTimeline();

        this.answerCallback = onAnswer;
        this.correctIndex = quiz.getCorrectIndex();
        this.answered = false;
        this.localAnswer = -1;

        questionLabel.setText(quiz.getQuestion());
        List<String> opts = quiz.getOptions();
        for (int i = 0; i < optionButtons.size(); i++) {
            Button b = optionButtons.get(i);
            b.setText((char) ('A' + i) + ". " + opts.get(i));
            b.setDisable(false);
            b.setStyle("");   // buang highlight hasil kuis sebelumnya
        }
        resultLabel.setText("");
        resultLabel.setVisible(false);

        setManaged(true);
        setVisible(true);
        toFront();

        startCountdown(quiz.getTimeLimitMs());
    }

    /** Tampilkan hasil akhir. Highlight jawaban benar, lalu auto-dismiss. */
    public void showResult(QuizResult result) {
        if (result == null) return;
        stopCountdown();

        int correct = result.getCorrectIndex();
        for (int i = 0; i < optionButtons.size(); i++) {
            Button b = optionButtons.get(i);
            b.setDisable(true);
            if (i == correct) {
                b.setStyle("-fx-background-color: #2e7d32; -fx-text-fill: white; -fx-background-radius: 20;");
            } else if (i == localAnswer) {
                b.setStyle("-fx-background-color: #b71c1c; -fx-text-fill: white; -fx-background-radius: 20;");
            }
        }

        String msg = result.getMessage();
        resultLabel.setText(msg == null ? "" : msg);
        resultLabel.setVisible(true);
        timerLabel.setText("");

        dismissTimeline = new Timeline(new KeyFrame(RESULT_DISPLAY, e -> hide()));
        dismissTimeline.play();
    }

    public boolean isShowing() {
        return isVisible();
    }

    /** Sembunyikan overlay (dipanggil otomatis setelah RESULT_DISPLAY atau manual). */
    public void hide() {
        stopCountdown();
        stopDismissTimeline();
        setVisible(false);
        setManaged(false);
        answerCallback = null;
    }

    // ===================== internal =====================

    private void startCountdown(long timeLimitMs) {
        long startAt = System.currentTimeMillis();
        timerLabel.setText(formatRemaining(timeLimitMs));

        countdown = new Timeline(new KeyFrame(Duration.millis(100), e -> {
            long elapsed = System.currentTimeMillis() - startAt;
            long remaining = Math.max(0, timeLimitMs - elapsed);
            timerLabel.setText(formatRemaining(remaining));
            if (remaining <= 0) {
                stopCountdown();
                // Timeout client-side: matikan tombol saja. JANGAN kirim
                // QUIZ_ANSWER - server yang akan mengirim QUIZ_RESULT
                // (tanpa winner) saat timeout-nya sendiri tercapai.
                // Ini menjaga prinsip "server otoritatif".
                for (Button b : optionButtons) b.setDisable(true);
            }
        }));
        countdown.setCycleCount(Timeline.INDEFINITE);
        countdown.play();
    }

    private static String formatRemaining(long ms) {
        long sec = (ms + 999) / 1000;   // ceil, biar "1" tidak langsung jadi "0" di detik terakhir
        return "Waktu: " + sec + "s";
    }

    private void onAnswerClicked(int index) {
        if (answered) return;
        answered = true;
        localAnswer = index;
        for (Button b : optionButtons) b.setDisable(true);
        if (answerCallback != null) {
            // Panggil callback dari FX thread - NetworkClient.sendMessage aman dipanggil dari thread manapun.
            answerCallback.accept(index);
        }
    }

    private void stopCountdown() {
        if (countdown != null) {
            countdown.stop();
            countdown = null;
        }
    }

    private void stopDismissTimeline() {
        if (dismissTimeline != null) {
            dismissTimeline.stop();
            dismissTimeline = null;
        }
    }
}