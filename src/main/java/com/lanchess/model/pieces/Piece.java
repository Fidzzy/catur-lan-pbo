package com.lanchess.model.pieces;

import com.lanchess.model.PieceType;
import com.lanchess.model.PlayerColor;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Basis abstrak untuk semua bidak catur.
 *
 * Catatan desain:
 *  - getMoves() mengembalikan langkah "pseudo-legal": sudah memperhitungkan
 *    pola gerak bidak, blocking oleh bidak lain, dan capture lawan, TAPI
 *    BELUM memfilter langkah yang membuat raja sendiri diskak (skak).
 *    Filter itu tanggung jawab MoveValidator.isLegalMove(), karena butuh
 *    simulasi seluruh papan, bukan cuma satu bidak.
 *  - Castling & en passant TIDAK di-generate di sini karena butuh konteks
 *    tambahan di luar papan (riwayat langkah terakhir untuk en passant,
 *    status hasMoved raja+benteng untuk castling). Itu ditangani khusus
 *    oleh MoveValidator, memakai flag hasMoved di King/Rook/Pawn di sini.
 */
public abstract class Piece implements Serializable, Cloneable {

    private static final long serialVersionUID = 1L;


    protected PlayerColor color;
    protected int row;
    protected int col;
    protected boolean hasMoved;

    protected Piece(PlayerColor color, int row, int col) {
        this.color = color;
        this.row = row;
        this.col = col;
        this.hasMoved = false;
    }

    /**
     * @return daftar koordinat {row, col} tujuan yang pseudo-legal untuk
     *         bidak ini, berdasarkan posisi papan saat ini.
     */
    public abstract List<int[]> getMoves(Piece[][] board);

    public abstract PieceType getType();

    /**
     * Deep-copy bidak ini. Dipakai MoveValidator untuk mensimulasikan
     * sebuah langkah di papan bayangan tanpa mengubah GameState asli
     * (penting untuk cek "apakah langkah ini membuat raja sendiri skak").
     */
    public abstract Piece copy();

    // ---------- Helper umum dipakai semua subclass ----------

    /** Cek apakah koordinat masih di dalam papan 8x8. */
    protected static boolean inBounds(int r, int c) {
        return r >= 0 && r < 8 && c >= 0 && c < 8;
    }

    /**
     * Menelusuri arah geser (dr, dc) sampai mentok tepi papan, bidak
     * sendiri (berhenti sebelum), atau bidak lawan (berhenti setelah
     * memasukkan kotak capture). Dipakai Rook, Bishop, Queen.
     */
    protected List<int[]> slide(Piece[][] board, int[][] directions) {
        List<int[]> moves = new ArrayList<>();
        for (int[] dir : directions) {
            int r = row + dir[0];
            int c = col + dir[1];
            while (inBounds(r, c)) {
                Piece occupant = board[r][c];
                if (occupant == null) {
                    moves.add(new int[]{r, c});
                } else {
                    if (occupant.getColor() != this.color) {
                        moves.add(new int[]{r, c}); // capture
                    }
                    break; // mentok bidak apapun warnanya, berhenti menelusuri arah ini
                }
                r += dir[0];
                c += dir[1];
            }
        }
        return moves;
    }

    // ---------- Getter / Setter ----------

    public PlayerColor getColor() {
        return color;
    }

    public int getRow() {
        return row;
    }

    public void setRow(int row) {
        this.row = row;
    }

    public int getCol() {
        return col;
    }

    public void setCol(int col) {
        this.col = col;
    }

    public boolean hasMoved() {
        return hasMoved;
    }

    public void setHasMoved(boolean hasMoved) {
        this.hasMoved = hasMoved;
    }

    /** Dipanggil MoveValidator/GameState setelah bidak ini dipindah. */
    public void moveTo(int newRow, int newCol) {
        this.row = newRow;
        this.col = newCol;
        this.hasMoved = true;
    }
}
