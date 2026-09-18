package com.lanchess.client;

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
 */
public class BoardHolder extends Pane {

    private final BoardView boardView;

    public BoardHolder(BoardView boardView) {
        this.boardView = boardView;
        getChildren().add(boardView);
        setMinSize(180, 180);
        setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        HBox.setHgrow(this, Priority.ALWAYS);
    }

    public BoardView getBoardView() {
        return boardView;
    }

    @Override
    protected void layoutChildren() {
        double w = getWidth();
        double h = getHeight();
        if (w <= 0 || h <= 0) return;
        double s = Math.min(w, h);
        boardView.resize(s, s);
        boardView.relocate(Math.max(0, (w - s) / 2.0), Math.max(0, (h - s) / 2.0));
    }

    @Override
    protected double computePrefWidth(double height) {
        return boardView.getPrefBoardSize();
    }

    @Override
    protected double computePrefHeight(double width) {
        return boardView.getPrefBoardSize();
    }
}
