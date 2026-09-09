package com.lanchess.model.pieces;

import com.lanchess.model.PieceType;
import com.lanchess.model.PlayerColor;

import java.util.ArrayList;
import java.util.List;

/**
 * Raja. getMoves() di sini HANYA mengembalikan 8 langkah standar 1 kotak.
 * Castling TIDAK dimasukkan di sini karena:
 *   1. Butuh cek apakah raja & benteng terkait belum pernah bergerak (hasMoved)
 *   2. Butuh cek jalur di antara raja-benteng kosong
 *   3. Butuh cek raja tidak sedang skak, dan tidak melewati/berhenti di kotak
 *      yang diserang lawan (aturan ini butuh MoveValidator.isSquareAttacked(),
 *      yang butuh scan seluruh papan - di luar tanggung jawab satu Piece).
 * Jadi castling di-generate & divalidasi penuh oleh MoveValidator.
 */
public class King extends Piece {

    private static final long serialVersionUID = 1L;


    private static final int[][] OFFSETS = {
            {-1, -1}, {-1, 0}, {-1, 1},
            {0, -1},           {0, 1},
            {1, -1},  {1, 0},  {1, 1}
    };

    public King(PlayerColor color, int row, int col) {
        super(color, row, col);
    }

    @Override
    public List<int[]> getMoves(Piece[][] board) {
        List<int[]> moves = new ArrayList<>();
        for (int[] offset : OFFSETS) {
            int r = row + offset[0];
            int c = col + offset[1];
            if (!inBounds(r, c)) continue;

            Piece occupant = board[r][c];
            if (occupant == null || occupant.getColor() != this.color) {
                moves.add(new int[]{r, c});
            }
        }
        return moves;
    }

    @Override
    public PieceType getType() {
        return PieceType.KING;
    }

    @Override
    public Piece copy() {
        King copy = new King(color, row, col);
        copy.hasMoved = this.hasMoved;
        return copy;
    }
}
