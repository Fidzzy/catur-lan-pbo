package com.lanchess.client;

import com.lanchess.model.Move;
import com.lanchess.model.PieceType;

/**
 * Format Move menjadi notasi aljabar ringkas untuk panel riwayat langkah di
 * GUI. CATATAN: ini SIMPLIFIKASI, bukan SAN (Standard Algebraic Notation)
 * lengkap - tidak ada disambiguasi kalau 2 bidak sejenis bisa mencapai
 * kotak yang sama (mis. "Nbd7" vs "Nfd7"), dan tidak ada suffix +/# untuk
 * skak/skakmat. Cukup untuk tampilan referensi visual, bukan untuk
 * ekspor PGN yang valid.
 */
public final class MoveNotationFormatter {

    private MoveNotationFormatter() {
    }

    public static String format(Move move) {
        if (move.isCastling()) {
            return move.isKingsideCastling() ? "O-O" : "O-O-O";
        }

        StringBuilder sb = new StringBuilder();
        if (move.getPieceType() != PieceType.PAWN) {
            sb.append(move.getPieceType().getNotation());
        }
        if (move.isCapture()) {
            if (move.getPieceType() == PieceType.PAWN) {
                sb.append((char) ('a' + move.getFromCol()));
            }
            sb.append('x');
        }
        sb.append(squareName(move.getToRow(), move.getToCol()));
        if (move.isPromotion()) {
            sb.append('=').append(move.getPromotionType().getNotation());
        }
        return sb.toString();
    }

    /** Format sepasang half-move (putih & hitam, hitam boleh null kalau belum jalan) jadi satu baris riwayat. */
    public static String formatPair(int moveNumber, Move whiteMove, Move blackMove) {
        StringBuilder sb = new StringBuilder();
        sb.append(moveNumber).append(". ").append(format(whiteMove));
        if (blackMove != null) {
            sb.append("   ").append(format(blackMove));
        }
        return sb.toString();
    }

    private static String squareName(int row, int col) {
        char file = (char) ('a' + col);
        int rank = 8 - row;
        return "" + file + rank;
    }
}
