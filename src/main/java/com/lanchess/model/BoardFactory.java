package com.lanchess.model;

import com.lanchess.model.pieces.Bishop;
import com.lanchess.model.pieces.King;
import com.lanchess.model.pieces.Knight;
import com.lanchess.model.pieces.Pawn;
import com.lanchess.model.pieces.Piece;
import com.lanchess.model.pieces.Queen;
import com.lanchess.model.pieces.Rook;

/**
 * Factory Pattern: bertanggung jawab membuat papan Piece[8][8] dengan
 * posisi bidak awal standar catur. Dipisah dari GameState supaya GameState
 * tidak perlu tahu detail konstruksi tiap bidak (Single Responsibility).
 *
 * Konvensi papan (lihat juga Pawn.java):
 *   row 0..1 = sisi BLACK, row 6..7 = sisi WHITE, row 2..5 = kosong di awal.
 */
public final class BoardFactory {

    private BoardFactory() {
        // utility class, tidak boleh diinstansiasi
    }

    public static Piece[][] createStandardBoard() {
        Piece[][] board = new Piece[8][8];

        // Bidak berat baris belakang, urutan kolom 0..7:
        // Rook, Knight, Bishop, Queen, King, Bishop, Knight, Rook
        placeBackRank(board, 0, PlayerColor.BLACK);
        placeBackRank(board, 7, PlayerColor.WHITE);

        // Pion
        for (int col = 0; col < 8; col++) {
            board[1][col] = new Pawn(PlayerColor.BLACK, 1, col);
            board[6][col] = new Pawn(PlayerColor.WHITE, 6, col);
        }

        return board;
    }

    private static void placeBackRank(Piece[][] board, int row, PlayerColor color) {
        board[row][0] = new Rook(color, row, 0);
        board[row][1] = new Knight(color, row, 1);
        board[row][2] = new Bishop(color, row, 2);
        board[row][3] = new Queen(color, row, 3);
        board[row][4] = new King(color, row, 4);
        board[row][5] = new Bishop(color, row, 5);
        board[row][6] = new Knight(color, row, 6);
        board[row][7] = new Rook(color, row, 7);
    }
}
