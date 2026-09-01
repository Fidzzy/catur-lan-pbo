package com.lanchess.model.pieces;

import com.lanchess.model.PieceType;
import com.lanchess.model.PlayerColor;

import java.util.List;

public class Queen extends Piece {

    private static final long serialVersionUID = 1L;


    private static final int[][] DIRECTIONS = {
            {-1, 0}, {1, 0}, {0, -1}, {0, 1},   // lurus (seperti Rook)
            {-1, -1}, {-1, 1}, {1, -1}, {1, 1}  // diagonal (seperti Bishop)
    };

    public Queen(PlayerColor color, int row, int col) {
        super(color, row, col);
    }

    @Override
    public List<int[]> getMoves(Piece[][] board) {
        return slide(board, DIRECTIONS);
    }

    @Override
    public PieceType getType() {
        return PieceType.QUEEN;
    }

    @Override
    public Piece copy() {
        Queen copy = new Queen(color, row, col);
        copy.hasMoved = this.hasMoved;
        return copy;
    }
}
