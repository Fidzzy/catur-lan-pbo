package com.lanchess.bot;

import com.lanchess.model.GameState;
import com.lanchess.model.Move;
import com.lanchess.model.PieceType;
import com.lanchess.model.PlayerColor;
import com.lanchess.model.pieces.King;
import com.lanchess.model.pieces.Pawn;
import com.lanchess.model.pieces.Piece;
import com.lanchess.model.pieces.Rook;

/**
 * Jembatan format data antara model internal LAN Chess Arena dan protokol
 * UCI yang dipahami Stockfish (atau engine UCI lain manapun).
 *
 * FEN (Forsyth-Edwards Notation) dipakai untuk MENGIRIM posisi ke engine
 * lewat perintah "position fen ...". Notasi UCI move ("e2e4", "e7e8q")
 * dipakai untuk MENERIMA balasan "bestmove ..." dari engine.
 */
public final class FenConverter {

    private FenConverter() {
    }

    // =========================================================================
    // GameState -> FEN
    // =========================================================================

    public static String toFen(GameState state) {
        StringBuilder fen = new StringBuilder();

        appendPiecePlacement(fen, state);
        fen.append(' ').append(state.getCurrentTurn() == PlayerColor.WHITE ? 'w' : 'b');
        fen.append(' ').append(castlingAvailability(state));
        fen.append(' ').append(enPassantTarget(state));
        fen.append(' ').append(halfmoveClock(state));
        fen.append(' ').append(fullmoveNumber(state));

        return fen.toString();
    }

    private static void appendPiecePlacement(StringBuilder fen, GameState state) {
        for (int r = 0; r < 8; r++) {
            int emptyRun = 0;
            for (int c = 0; c < 8; c++) {
                Piece piece = state.getPieceAt(r, c);
                if (piece == null) {
                    emptyRun++;
                    continue;
                }
                if (emptyRun > 0) {
                    fen.append(emptyRun);
                    emptyRun = 0;
                }
                fen.append(pieceLetter(piece));
            }
            if (emptyRun > 0) {
                fen.append(emptyRun);
            }
            if (r < 7) {
                fen.append('/');
            }
        }
    }

    private static char pieceLetter(Piece piece) {
        char letter = switch (piece.getType()) {
            case KING -> 'k';
            case QUEEN -> 'q';
            case ROOK -> 'r';
            case BISHOP -> 'b';
            case KNIGHT -> 'n';
            case PAWN -> 'p';
        };
        return piece.getColor() == PlayerColor.WHITE ? Character.toUpperCase(letter) : letter;
    }

    /**
     * String hak castling (KQkq / sebagian / "-"). Didekasi dari flag
     * hasMoved raja & benteng terkait - pendekatan praktis yang cukup akurat
     * untuk kebutuhan "engine memilih langkah terbaik" (bukan validasi legal
     * penuh - validasi legal tetap tanggung jawab MoveValidator kita sendiri).
     */
    private static String castlingAvailability(GameState state) {
        StringBuilder rights = new StringBuilder();

        Piece whiteKing = state.getPieceAt(7, 4);
        if (whiteKing instanceof King && !whiteKing.hasMoved()) {
            if (isUnmovedRook(state, 7, 7, PlayerColor.WHITE)) rights.append('K');
            if (isUnmovedRook(state, 7, 0, PlayerColor.WHITE)) rights.append('Q');
        }
        Piece blackKing = state.getPieceAt(0, 4);
        if (blackKing instanceof King && !blackKing.hasMoved()) {
            if (isUnmovedRook(state, 0, 7, PlayerColor.BLACK)) rights.append('k');
            if (isUnmovedRook(state, 0, 0, PlayerColor.BLACK)) rights.append('q');
        }

        return rights.isEmpty() ? "-" : rights.toString();
    }

    private static boolean isUnmovedRook(GameState state, int row, int col, PlayerColor color) {
        Piece p = state.getPieceAt(row, col);
        return p instanceof Rook && !p.hasMoved() && p.getColor() == color;
    }

    private static String enPassantTarget(GameState state) {
        Move last = state.getLastMove();
        if (last == null || last.getPieceType() != PieceType.PAWN) return "-";
        if (Math.abs(last.getToRow() - last.getFromRow()) != 2) return "-";

        int targetRow = (last.getFromRow() + last.getToRow()) / 2;
        int targetCol = last.getToCol();
        return squareName(targetRow, targetCol);
    }

    /** Halfmove clock (untuk 50-move rule): jumlah half-move sejak capture/pawn-move terakhir. */
    private static int halfmoveClock(GameState state) {
        var history = state.getMoveHistory();
        int count = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            Move m = history.get(i);
            if (m.isCapture() || m.getPieceType() == PieceType.PAWN) break;
            count++;
        }
        return count;
    }

    private static int fullmoveNumber(GameState state) {
        return (state.getMoveHistory().size() / 2) + 1;
    }

    // =========================================================================
    // UCI move string -> Move (raw, sebelum divalidasi/enrich MoveValidator)
    // =========================================================================

    /**
     * Parse notasi UCI seperti "e2e4" atau "e7e8q" (promosi) menjadi Move
     * mentah. Move ini WAJIB tetap dilewatkan ke
     * {@code MoveValidator.findLegalMove()} sebelum dieksekusi - sama seperti
     * langkah dari client manusia, supaya server tetap satu-satunya sumber
     * kebenaran dan flag castling/enPassant/capturedType terisi benar.
     */
    public static Move parseUciMove(String uci, GameState state) {
        if (uci == null || uci.length() < 4) {
            throw new IllegalArgumentException("Notasi UCI tidak valid: " + uci);
        }

        int fromCol = uci.charAt(0) - 'a';
        int fromRow = 8 - Character.getNumericValue(uci.charAt(1));
        int toCol = uci.charAt(2) - 'a';
        int toRow = 8 - Character.getNumericValue(uci.charAt(3));

        Piece piece = state.getPieceAt(fromRow, fromCol);
        if (piece == null) {
            throw new IllegalStateException("Engine mengirim langkah dari kotak kosong: " + uci);
        }

        Move move = new Move(fromRow, fromCol, toRow, toCol, piece.getType(), piece.getColor());

        if (uci.length() == 5) {
            move.setPromotionType(mapPromotionLetter(uci.charAt(4)));
        }

        return move;
    }

    private static PieceType mapPromotionLetter(char letter) {
        return switch (Character.toLowerCase(letter)) {
            case 'q' -> PieceType.QUEEN;
            case 'r' -> PieceType.ROOK;
            case 'b' -> PieceType.BISHOP;
            case 'n' -> PieceType.KNIGHT;
            default -> throw new IllegalArgumentException("Huruf promosi UCI tidak dikenal: " + letter);
        };
    }

    private static String squareName(int row, int col) {
        char file = (char) ('a' + col);
        int rank = 8 - row;
        return "" + file + rank;
    }
}
