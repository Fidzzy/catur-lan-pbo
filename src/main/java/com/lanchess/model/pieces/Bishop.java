package com.lanchess.model.pieces;

import com.lanchess.model.PieceType;
import com.lanchess.model.PlayerColor;

import java.util.List;

public class Bishop extends Piece {

    private static final long serialVersionUID = 1L;


    private static final int[][] DIRECTIONS = {
            {-1, -1}, {-1, 1}, {1, -1}, {1, 1} // 4 arah diagonal
    };

    public Bishop(PlayerColor color, int row, int col) {
        super(color, row, col);
    }

    @Override
    public List<int[]> getMoves(Piece[][] board) {
        return slide(board, DIRECTIONS);
    }

    @Override
    public PieceType getType() {
        return PieceType.BISHOP;
    }

    @Override
    public Piece copy() {
        Bishop copy = new Bishop(color, row, col);
        copy.hasMoved = this.hasMoved;
        return copy;
    }
}
