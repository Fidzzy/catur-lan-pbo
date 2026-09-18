package com.lanchess.client;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

/**
 * Bar evaluasi vertikal untuk mode vs Bot: menampilkan keunggulan posisi
 * dari sudut pandang PUTIH (bagian putih tumbuh dari bawah) + label skor.
 *
 * Controller mengisi bar ini dari StockfishEngine.evaluateCentipawns() yang
 * dijalankan di background thread; method update() sendiri WAJIB dipanggil
 * dari JavaFX Application Thread (biasanya via Platform.runLater).
 *
 * RESPONSIF: tinggi bar mengikuti tinggi papan (lihat setBarHeight / bind ke
 * BoardView). Setiap resize menggambar ulang dari cache terakhir.
 */
public class EvalBar extends VBox {

    private static final double BAR_WIDTH = 36;
    public static final double MIN_BAR_HEIGHT = 200.0;

    private final Canvas canvas;
    private final Label scoreLabel;

    private double lastProb = 0.5;
    private String lastScoreText = "0.0";

    public EvalBar(double height) {
        this.canvas = new Canvas(BAR_WIDTH, Math.max(height, MIN_BAR_HEIGHT));
        this.scoreLabel = new Label("0.0");
        scoreLabel.getStyleClass().add("section-label");
        scoreLabel.setFont(Font.font("SansSerif", FontWeight.BOLD, 11));
        scoreLabel.setMaxWidth(84);
        scoreLabel.setWrapText(true);
        scoreLabel.setTextAlignment(TextAlignment.CENTER);
        scoreLabel.setStyle(scoreLabel.getStyle() + "-fx-alignment: center;");

        setSpacing(6);
        setFillWidth(true);
        setMinWidth(BAR_WIDTH + 10);
        setMaxWidth(100);
        // Canvas jangan ikut stretch horizontal oleh VBox, tapi boleh grow vertikal via setBarHeight.
        VBox.setVgrow(canvas, Priority.ALWAYS);
        getChildren().addAll(canvas, scoreLabel);
        reset();
    }

    /** Ikuti tinggi papan supaya bar selalu sejajar papan saat window di-resize. */
    public void setBarHeight(double height) {
        double h = Math.max(height, MIN_BAR_HEIGHT);
        if (Math.abs(canvas.getHeight() - h) < 0.5) return;
        canvas.setHeight(h);
        canvas.setWidth(BAR_WIDTH);
        repaint();
    }

    /** Kembalikan ke posisi awal (seimbang). */
    public void reset() {
        update(0.5, "0.0");
    }

    /**
     * @param whiteWinProb peluang menang putih 0.0 - 1.0 (0.5 = seimbang)
     * @param scoreText   teks skor dari sudut pandang putih, mis. "+1.2" atau "#"
     */
    public void update(double whiteWinProb, String scoreText) {
        this.lastProb = Math.max(0.0, Math.min(1.0, whiteWinProb));
        this.lastScoreText = scoreText;
        repaint();
    }

    private void repaint() {
        double barHeight = canvas.getHeight();
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, BAR_WIDTH, barHeight);

        // Latar hitam penuh, lalu timpa bagian bawah setinggi peluang putih
        gc.setFill(Color.web("#3A3A3A"));
        gc.fillRect(0, 0, BAR_WIDTH, barHeight);
        double whiteHeight = barHeight * lastProb;
        gc.setFill(Color.web("#F2F2F0"));
        gc.fillRect(0, barHeight - whiteHeight, BAR_WIDTH, whiteHeight);

        // Garis tengah + bingkai
        gc.setStroke(Color.web("#B9BAB8"));
        gc.setLineWidth(1);
        gc.strokeLine(0, barHeight / 2, BAR_WIDTH, barHeight / 2);
        gc.strokeRect(0.5, 0.5, BAR_WIDTH - 1, barHeight - 1);

        scoreLabel.setText(lastScoreText);
    }

    /**
     * Konversi evaluasi centipawn (sudut pandang putih) ke peluang menang
     * 0.0 - 1.0 memakai fungsi logistik standar komunitas catur.
     * Skor mate (±90000 ke atas) jenuh otomatis ke ~0/~1.
     */
    public static double winProbability(int centipawnsWhite) {
        return 1.0 / (1.0 + Math.exp(-0.003682 * centipawnsWhite));
    }

    /**
     * Format skor untuk label: "+1.2" / "-0.5" / "0.0" (putih unggul = +),
     * "#" kalau sudah skakmat di papan (skor mate dari engine).
     */
    public static String formatScore(int centipawnsWhite) {
        if (Math.abs(centipawnsWhite) >= 90_000) return "#";
        double pawns = centipawnsWhite / 100.0;
        if (Math.abs(pawns) < 0.05) return "0.0";
        return (pawns > 0 ? "+" : "") + String.format("%.1f", pawns);
    }

    /**
     * Teks peluang menang untuk label status, mis. "62%".
     * Selalu dari sudut pandang PUTIH (konsisten dengan arah bar).
     */
    public static String formatWinChance(double whiteWinProb) {
        return Math.round(whiteWinProb * 100) + "%";
    }
}
