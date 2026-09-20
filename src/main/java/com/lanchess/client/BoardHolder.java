package com.lanchess.client;

import javafx.geometry.Insets;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;

/**
 * Wadah papan yang selalu persegi dan selalu muat di ruang yang tersedia.
 *
 * Cara kerja: setiap kali layout berjalan (termasuk saat window
 * di-maximize/fullscreen), holder mengukur ruangnya sendiri lalu memaksa
 * {@link BoardView} berukuran {@code min(lebar, tinggi)} dan menaruhnya
 * tepat di tengah. Berbeda dengan StackPane biasa (yang bisa me-resize
 * canvas tidak persegi sehingga ukurannya tidak deterministik), di sini
 * canvas TIDAK PERNAH lebih besar dari holder ke arah manapun - jadi papan
 * tidak mungkin terpotong seberapa pun besar/kecilnya window.
 *
 * UPDATE: Sekarang mendukung CSS padding (untuk efek bingkai papan).
 * Logika layout dikurangi dengan insets agar papan tetap presisi di tengah.
 */
public class BoardHolder extends Pane {

    private final BoardView boardView;

    public BoardHolder(BoardView boardView) {
        this.boardView = boardView;
        getChildren().add(boardView);
        getStyleClass().add("board-frame");
        setMinSize(180, 180);
        setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        maxWidthProperty().bind(heightProperty());

        HBox.setHgrow(this, Priority.ALWAYS);
    }

    public BoardView getBoardView() {
        return boardView;
    }

    @Override
    protected void layoutChildren() {
        // Hitung ruang yang tersedia dikurangi padding/insets dari CSS
        Insets insets = getInsets();
        double availableW = getWidth() - insets.getLeft() - insets.getRight();
        double availableH = getHeight() - insets.getTop() - insets.getBottom();

        if (availableW <= 0 || availableH <= 0) return;

        double s = Math.min(availableW, availableH);

        // Resize papan
        boardView.resize(s, s);

        // Posisikan papan tepat di tengah area yang sudah dikurangi padding
        double x = insets.getLeft() + Math.max(0, (availableW - s) / 2.0);
        double y = insets.getTop() + Math.max(0, (availableH - s) / 2.0);

        boardView.relocate(x, y);
    }

    @Override
    protected double computePrefWidth(double height) {
        // Lebar yang disukai = ukuran papan + padding kiri & kanan
        return boardView.getPrefBoardSize() + getInsets().getLeft() + getInsets().getRight();
    }

    @Override
    protected double computePrefHeight(double width) {
        // Tinggi yang disukai = ukuran papan + padding atas & bawah
        return boardView.getPrefBoardSize() + getInsets().getTop() + getInsets().getBottom();
    }
}