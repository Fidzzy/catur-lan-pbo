package com.lanchess.model;

import com.lanchess.model.pieces.King;
import com.lanchess.model.pieces.Piece;
import com.lanchess.model.pieces.Rook;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /**
     * Warna pemain yang KALAH, hanya relevan (non-null) saat status
     * CHECKMATE, TIMEOUT, atau RESIGNATION. Untuk status DRAW/STALEMATE
     * tidak ada pihak yang kalah, tetap null.
     */
    private PlayerColor loserColor;

    /** Alasan spesifik saat status == DRAW (repetisi/50-move/kesepakatan). Null kalau status bukan DRAW. */
    private DrawReason drawReason;

    /**
     * Hitungan kemunculan tiap posisi (piece placement + giliran + hak
     * castling + target en passant) sepanjang permainan, dipakai deteksi
     * threefold repetition. Key dihasilkan currentPositionKey().
     */
    private final Map<String, Integer> positionCounts = new HashMap<>();

    public GameState() {
        this.board = BoardFactory.createStandardBoard();
        this.currentTurn = PlayerColor.WHITE; // WHITE selalu jalan duluan
        this.status = GameStatus.WAITING_FOR_PLAYER;
        this.moveHistory = new ArrayList<>();
        recordCurrentPositionAndGetCount(); // posisi awal ikut dihitung untuk threefold repetition
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

    // ---------- Resign / Timeout / Draw ----------

    public PlayerColor getLoserColor() {
        return loserColor;
    }

    public void setLoserColor(PlayerColor loserColor) {
        this.loserColor = loserColor;
    }

    public DrawReason getDrawReason() {
        return drawReason;
    }

    public void setDrawReason(DrawReason drawReason) {
        this.drawReason = drawReason;
    }

    // ---------- Threefold repetition & 50-move rule ----------

    /**
     * Kunci posisi (piece placement + giliran + hak castling + target en
     * passant) sesuai definisi FIDE untuk threefold repetition - SENGAJA
     * tidak termasuk halfmove/fullmove clock, karena dua posisi dengan
     * papan identik tapi "jam" berbeda tetap dianggap posisi yang sama.
     *
     * Implementasi ini SENGAJA mandiri (tidak reuse FenConverter di
     * package bot) supaya package model tidak bergantung pada package
     * bot untuk aturan inti seperti ini - ada duplikasi kecil logika FEN
     * dengan FenConverter, itu trade-off yang disengaja demi layering
     * yang bersih (bot boleh depend ke model, tidak sebaliknya).
     */
    public String currentPositionKey() {
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < 8; r++) {
            int emptyRun = 0;
            for (int c = 0; c < 8; c++) {
                Piece p = board[r][c];
                if (p == null) {
                    emptyRun++;
                    continue;
                }
                if (emptyRun > 0) {
                    sb.append(emptyRun);
                    emptyRun = 0;
                }
                char letter = switch (p.getType()) {
                    case KING -> 'k';
                    case QUEEN -> 'q';
                    case ROOK -> 'r';
                    case BISHOP -> 'b';
                    case KNIGHT -> 'n';
                    case PAWN -> 'p';
                };
                sb.append(p.getColor() == PlayerColor.WHITE ? Character.toUpperCase(letter) : letter);
            }
            if (emptyRun > 0) sb.append(emptyRun);
            if (r < 7) sb.append('/');
        }
        sb.append(' ').append(currentTurn == PlayerColor.WHITE ? 'w' : 'b');
        sb.append(' ').append(castlingRightsKey());
        sb.append(' ').append(enPassantTargetKey());
        return sb.toString();
    }

    private String castlingRightsKey() {
        StringBuilder rights = new StringBuilder();
        Piece whiteKing = getPieceAt(7, 4);
        if (whiteKing instanceof King && !whiteKing.hasMoved()) {
            if (isUnmovedRook(7, 7, PlayerColor.WHITE)) rights.append('K');
            if (isUnmovedRook(7, 0, PlayerColor.WHITE)) rights.append('Q');
        }
        Piece blackKing = getPieceAt(0, 4);
        if (blackKing instanceof King && !blackKing.hasMoved()) {
            if (isUnmovedRook(0, 7, PlayerColor.BLACK)) rights.append('k');
            if (isUnmovedRook(0, 0, PlayerColor.BLACK)) rights.append('q');
        }
        return rights.isEmpty() ? "-" : rights.toString();
    }

    private boolean isUnmovedRook(int row, int col, PlayerColor color) {
        Piece p = getPieceAt(row, col);
        return p instanceof Rook && !p.hasMoved() && p.getColor() == color;
    }

    private String enPassantTargetKey() {
        Move last = getLastMove();
        if (last == null || last.getPieceType() != PieceType.PAWN) return "-";
        if (Math.abs(last.getToRow() - last.getFromRow()) != 2) return "-";
        int targetRow = (last.getFromRow() + last.getToRow()) / 2;
        int targetCol = last.getToCol();
        char file = (char) ('a' + targetCol);
        int rank = 8 - targetRow;
        return "" + file + rank;
    }

    /**
     * Catat posisi SAAT INI ke penghitung repetisi, kembalikan jumlah
     * kemunculannya (termasuk yang baru saja dicatat ini). Dipanggil
     * MoveValidator TEPAT SETELAH sebuah move dieksekusi.
     */
    public int recordCurrentPositionAndGetCount() {
        String key = currentPositionKey();
        return positionCounts.merge(key, 1, Integer::sum);
    }

    /**
     * Jumlah half-move sejak capture/pawn-move terakhir (dipakai 50-move
     * rule DAN field halfmove clock di FEN oleh FenConverter - single
     * source of truth di sini, FenConverter tinggal delegasi ke method ini).
     */
    public int computeHalfmoveClock() {
        int count = 0;
        for (int i = moveHistory.size() - 1; i >= 0; i--) {
            Move m = moveHistory.get(i);
            if (m.isCapture() || m.getPieceType() == PieceType.PAWN) break;
            count++;
        }
        return count;
    }
}
