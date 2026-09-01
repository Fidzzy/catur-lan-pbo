package com.lanchess.model;

import java.io.Serializable;
import java.util.Objects;

/**
 * Merepresentasikan satu langkah catur.
 *
 * Dipakai dua arah:
 *  - Client -> Server: client isi fromRow/fromCol/toRow/toCol (+ promotionType
 *    kalau pion mencapai baris terakhir), lalu kirim lewat Message(MOVE, move).
 *    Server yang MENENTUKAN ULANG isCastling/isEnPassant/capturedType secara
 *    independen saat validasi (client tidak dipercaya untuk klaim flag ini).
 *  - Server -> Client: setelah move divalidasi & dieksekusi, move (dengan
 *    flag sudah lengkap) dimasukkan ke GameState.moveHistory lalu di-broadcast
 *    via STATE_UPDATE, dipakai client untuk render notasi & highlight langkah terakhir.
 */
public class Move implements Serializable {

    private static final long serialVersionUID = 1L;


    private final int fromRow;
    private final int fromCol;
    private final int toRow;
    private final int toCol;

    /** Jenis bidak yang bergerak. */
    private final PieceType pieceType;

    /** Warna bidak yang bergerak. */
    private final PlayerColor pieceColor;

    /** True jika langkah ini adalah castling (raja+benteng). Diisi server saat validasi. */
    private boolean castling;

    /** True jika langkah ini adalah en passant capture. Diisi server saat validasi. */
    private boolean enPassant;

    /**
     * Jenis bidak hasil promosi pion (QUEEN/ROOK/BISHOP/KNIGHT).
     * Null jika langkah ini bukan promosi.
     * Diisi CLIENT (pemain memilih) saat mengirim MOVE, karena hanya pemain
     * yang tahu mau promosi jadi apa.
     */
    private PieceType promotionType;

    /**
     * Jenis bidak lawan yang tertangkap oleh langkah ini, null jika tidak
     * ada capture. Diisi server saat validasi (dipakai untuk notasi & histori).
     */
    private PieceType capturedType;

    public Move(int fromRow, int fromCol, int toRow, int toCol,
                PieceType pieceType, PlayerColor pieceColor) {
        this.fromRow = fromRow;
        this.fromCol = fromCol;
        this.toRow = toRow;
        this.toCol = toCol;
        this.pieceType = pieceType;
        this.pieceColor = pieceColor;
    }

    // ---------- Getter ----------

    public int getFromRow() {
        return fromRow;
    }

    public int getFromCol() {
        return fromCol;
    }

    public int getToRow() {
        return toRow;
    }

    public int getToCol() {
        return toCol;
    }

    public PieceType getPieceType() {
        return pieceType;
    }

    public PlayerColor getPieceColor() {
        return pieceColor;
    }

    public boolean isCastling() {
        return castling;
    }

    public void setCastling(boolean castling) {
        this.castling = castling;
    }

    public boolean isEnPassant() {
        return enPassant;
    }

    public void setEnPassant(boolean enPassant) {
        this.enPassant = enPassant;
    }

    public PieceType getPromotionType() {
        return promotionType;
    }

    public void setPromotionType(PieceType promotionType) {
        this.promotionType = promotionType;
    }

    public boolean isPromotion() {
        return promotionType != null;
    }

    public PieceType getCapturedType() {
        return capturedType;
    }

    public void setCapturedType(PieceType capturedType) {
        this.capturedType = capturedType;
    }

    public boolean isCapture() {
        return capturedType != null;
    }

    /** True jika castling ini kingside (raja bergerak ke kanan / kolom bertambah). */
    public boolean isKingsideCastling() {
        return castling && toCol > fromCol;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Move move)) return false;
        return fromRow == move.fromRow && fromCol == move.fromCol
                && toRow == move.toRow && toCol == move.toCol
                && pieceType == move.pieceType && pieceColor == move.pieceColor;
    }

    @Override
    public int hashCode() {
        return Objects.hash(fromRow, fromCol, toRow, toCol, pieceType, pieceColor);
    }

    @Override
    public String toString() {
        return "%s%s: (%d,%d) -> (%d,%d)%s%s".formatted(
                pieceColor, pieceType, fromRow, fromCol, toRow, toCol,
                isCapture() ? " x" + capturedType : "",
                isPromotion() ? " =" + promotionType : ""
        );
    }
}
