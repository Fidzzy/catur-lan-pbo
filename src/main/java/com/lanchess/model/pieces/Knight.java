package com.lanchess.model.pieces;

import com.lanchess.model.PieceType;
import com.lanchess.model.PlayerColor;

import java.util.ArrayList;
import java.util.List;

public class Knight extends Piece {

    private static final long serialVersionUID = 1L;


    // 8 kemungkinan lompatan L-shape (2+1)
    private static final int[][] OFFSETS = {
            {-2, -1}, {-2, 1}, {-1, -2}, {-1, 2},
            {1, -2}, {1, 2}, {2, -1}, {2, 1}
    };

    public Knight(PlayerColor color, int row, int col) {
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
            // Knight boleh lompat ke kotak kosong atau capture bidak lawan
            // (tidak peduli bidak apapun ada di antara sumber-tujuan, karena melompat)
            if (occupant == null || occupant.getColor() != this.color) {
                moves.add(new int[]{r, c});
            }
        }
        return moves;
    }

    @Override
    public PieceType getType() {
        return PieceType.KNIGHT;
    }

    @Override
    public Piece copy() {
        Knight copy = new Knight(color, row, col);
        copy.hasMoved = this.hasMoved;
        return copy;
    }
}
