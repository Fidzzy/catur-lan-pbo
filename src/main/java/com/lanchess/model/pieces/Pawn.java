package com.lanchess.model.pieces;

import com.lanchess.model.PieceType;
import com.lanchess.model.PlayerColor;

import java.util.ArrayList;
import java.util.List;

/**
 * Pion. Konvensi arah papan di project ini:
 *   - row 0 = baris paling atas = sisi BLACK (baris 8 di notasi catur)
 *   - row 7 = baris paling bawah = sisi WHITE (baris 1 di notasi catur)
 *   => WHITE bergerak maju dengan row BERKURANG (-1), BLACK dengan row BERTAMBAH (+1)
 *
 * getMoves() di sini menangani:
 *   - langkah maju 1 kotak (harus kosong)
 *   - langkah maju 2 kotak dari posisi awal (harus kosong, hasMoved == false)
 *   - capture diagonal (harus ada bidak lawan)
 *
 * TIDAK ditangani di sini (didelegasikan ke MoveValidator karena butuh
 * konteks di luar papan statis):
 *   - En passant: butuh tahu MOVE TERAKHIR lawan (apakah baru saja
 *     melangkah 2 kotak dan mendarat tepat di sebelah pion ini)
 *   - Promosi: bukan soal "ke mana pion boleh jalan" tapi "pion berubah
 *     jadi apa setelah sampai baris terakhir" -> disimpan di field
 *     Move.promotionType dan dieksekusi MoveValidator/GameState
 */
public class Pawn extends Piece {

    private static final long serialVersionUID = 1L;


    public Pawn(PlayerColor color, int row, int col) {
        super(color, row, col);
    }

    /** @return -1 untuk WHITE (maju ke row lebih kecil), +1 untuk BLACK */
    public int getDirection() {
        return color == PlayerColor.WHITE ? -1 : 1;
    }

    /** Baris awal pion sesuai warnanya (dipakai untuk cek langkah ganda). */
    public int getStartRow() {
        return color == PlayerColor.WHITE ? 6 : 1;
    }

    /** Baris promosi (baris terakhir di sisi lawan). */
    public int getPromotionRow() {
        return color == PlayerColor.WHITE ? 0 : 7;
    }

    @Override
    public List<int[]> getMoves(Piece[][] board) {
        List<int[]> moves = new ArrayList<>();
        int dir = getDirection();

        // --- Langkah maju 1 kotak ---
        int oneStepRow = row + dir;
        if (inBounds(oneStepRow, col) && board[oneStepRow][col] == null) {
            moves.add(new int[]{oneStepRow, col});

            // --- Langkah maju 2 kotak (hanya dari posisi awal, dan kotak pertama harus kosong juga) ---
            int twoStepRow = row + (2 * dir);
            if (!hasMoved && inBounds(twoStepRow, col) && board[twoStepRow][col] == null) {
                moves.add(new int[]{twoStepRow, col});
            }
        }

        // --- Capture diagonal kiri & kanan ---
        int[] captureCols = {col - 1, col + 1};
        for (int cc : captureCols) {
            int cr = row + dir;
            if (!inBounds(cr, cc)) continue;
            Piece target = board[cr][cc];
            if (target != null && target.getColor() != this.color) {
                moves.add(new int[]{cr, cc});
            }
        }

        return moves;
    }

    @Override
    public PieceType getType() {
        return PieceType.PAWN;
    }

    @Override
    public Piece copy() {
        Pawn copy = new Pawn(color, row, col);
        copy.hasMoved = this.hasMoved;
        return copy;
    }
}
