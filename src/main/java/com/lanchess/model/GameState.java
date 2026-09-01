package com.lanchess.model;

import com.lanchess.model.pieces.King;
import com.lanchess.model.pieces.Piece;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Snapshot lengkap state permainan. Instance ini yang dikirim server ke
 * kedua client lewat Message(STATE_UPDATE, gameState) setiap kali terjadi
 * perubahan (Observer pattern: server = subject, client = observer).
 *
 * PENTING: field `board` berisi objek Piece (bukan enum/String) supaya
 * client bisa langsung render tanpa perlu tabel lookup terpisah. Karena itu
 * SEMUA class di package model & model.pieces wajib Serializable.
 */
public class GameState implements Serializable {

    private static final long serialVersionUID = 1L;


    private Piece[][] board;
    private PlayerColor currentTurn;
    private GameStatus status;
    private final List<Move> moveHistory;

    private TimeControl timeControl = TimeControl.UNLIMITED;
    private long whiteMillisRemaining;
    private long blackMillisRemaining;

    public GameState() {
        this.board = BoardFactory.createStandardBoard();
        this.currentTurn = PlayerColor.WHITE; // WHITE selalu jalan duluan
        this.status = GameStatus.WAITING_FOR_PLAYER;
        this.moveHistory = new ArrayList<>();
    }

    // ---------- Query helper ----------

    public Piece getPieceAt(int row, int col) {
        if (row < 0 || row >= 8 || col < 0 || col >= 8) return null;
        return board[row][col];
    }

    /** Move terakhir yang sudah dieksekusi, atau null jika belum ada langkah. Dipakai untuk cek en passant. */
    public Move getLastMove() {
        if (moveHistory.isEmpty()) return null;
        return moveHistory.get(moveHistory.size() - 1);
    }

    /** Cari posisi raja dengan warna tertentu di papan. */
    public King findKing(PlayerColor color) {
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                Piece p = board[r][c];
                if (p instanceof King && p.getColor() == color) {
                    return (King) p;
                }
            }
        }
        // Seharusnya tidak pernah terjadi di permainan valid (raja tidak boleh tertangkap)
        throw new IllegalStateException("Raja " + color + " tidak ditemukan di papan");
    }

    /**
     * Deep-copy papan (bidak baru, tapi state row/col/hasMoved sama persis).
     * Dipakai MoveValidator untuk mensimulasikan langkah di papan bayangan
     * tanpa mengubah GameState asli, saat cek "apakah langkah ini
     * meninggalkan/menempatkan raja sendiri dalam skak".
     */
    public Piece[][] deepCopyBoard() {
        Piece[][] copy = new Piece[8][8];
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                if (board[r][c] != null) {
                    copy[r][c] = board[r][c].copy();
                }
            }
        }
        return copy;
    }

    // ---------- Getter / Setter ----------

    public Piece[][] getBoard() {
        return board;
    }

    public void setBoard(Piece[][] board) {
        this.board = board;
    }

    public PlayerColor getCurrentTurn() {
        return currentTurn;
    }

    public void setCurrentTurn(PlayerColor currentTurn) {
        this.currentTurn = currentTurn;
    }

    public void switchTurn() {
        this.currentTurn = this.currentTurn.opposite();
    }

    public GameStatus getStatus() {
        return status;
    }

    public void setStatus(GameStatus status) {
        this.status = status;
    }

    public List<Move> getMoveHistory() {
        return moveHistory;
    }

    public void addMove(Move move) {
        moveHistory.add(move);
    }

    // ---------- Jam catur ----------

    /** Set kontrol waktu DAN reset sisa waktu kedua pemain ke waktu awal preset ini. */
    public void setTimeControl(TimeControl timeControl) {
        this.timeControl = timeControl;
        this.whiteMillisRemaining = timeControl.getInitialMillis();
        this.blackMillisRemaining = timeControl.getInitialMillis();
    }

    public TimeControl getTimeControl() {
        return timeControl;
    }

    public long getRemainingMillis(PlayerColor color) {
        return color == PlayerColor.WHITE ? whiteMillisRemaining : blackMillisRemaining;
    }

    public void setRemainingMillis(PlayerColor color, long millis) {
        if (color == PlayerColor.WHITE) {
            whiteMillisRemaining = millis;
        } else {
            blackMillisRemaining = millis;
        }
    }

    /** Kurangi sisa waktu warna tertentu sebesar elapsedMillis, dibatasi minimum 0. */
    public void deductElapsed(PlayerColor color, long elapsedMillis) {
        long remaining = getRemainingMillis(color) - elapsedMillis;
        setRemainingMillis(color, Math.max(0, remaining));
    }
}
