package com.lanchess.model.pieces;

import com.lanchess.model.PieceType;
import com.lanchess.model.PlayerColor;

import java.util.List;

public class Rook extends Piece {

    private static final long serialVersionUID = 1L;


    private static final int[][] DIRECTIONS = {
            {-1, 0}, {1, 0}, {0, -1}, {0, 1} // atas, bawah, kiri, kanan
    };

    public Rook(PlayerColor color, int row, int col) {
        super(color, row, col);
    }

    @Override
    public List<int[]> getMoves(Piece[][] board) {
        return slide(board, DIRECTIONS);
    }

    @Override
    public PieceType getType() {
        return PieceType.ROOK;
    }

    @Override
    public Piece copy() {
        Rook copy = new Rook(color, row, col);
        copy.hasMoved = this.hasMoved;
        return copy;
    }
}
