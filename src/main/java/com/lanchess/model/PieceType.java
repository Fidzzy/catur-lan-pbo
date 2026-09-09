package com.lanchess.model;

import java.io.Serializable;

/**
 * Jenis bidak catur. Dipakai untuk:
 *  - identifikasi tipe tanpa perlu instanceof di client (mis. saat render)
 *  - memilih bidak hasil promosi pion (Move.promotionType)
 */
public enum PieceType implements Serializable {
    KING("K"),
    QUEEN("Q"),
    ROOK("R"),
    BISHOP("B"),
    KNIGHT("N"),
    PAWN("P");

    private final String notation;

    PieceType(String notation) {
        this.notation = notation;
    }

    public String getNotation() {
        return notation;
    }
}
