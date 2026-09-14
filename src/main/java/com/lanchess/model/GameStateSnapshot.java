package com.lanchess.model;

import com.lanchess.model.pieces.Piece;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Salinan beku satu titik posisi permainan, dipakai fitur undo/takeback
 * (mode vs Bot). Berisi deep-copy papan + riwayat + penghitung repetisi,
 * sehingga restore() mengembalikan SEMUA aspek state tanpa sisa:
 * bidak (termasuk flag hasMoved & bidak yang tertangkap), giliran, status,
 * jam kedua pemain, dan data threefold/50-move rule.
 *
 * CATATAN: sekali pakai - setelah dipakai restore(), objek ini dibuang
 * (controller selalu pop dari stack undo). Tidak dikirim via network,
 * jadi tidak perlu Serializable.
 */
public final class GameStateSnapshot {

    final Piece[][] board;
    final PlayerColor currentTurn;
    final GameStatus status;
    final List<Move> moveHistory;
    final TimeControl timeControl;
    final long whiteMillisRemaining;
    final long blackMillisRemaining;
    final PlayerColor loserColor;
    final DrawReason drawReason;
    final Map<String, Integer> positionCounts;

    GameStateSnapshot(GameState state) {
        this.board = state.deepCopyBoard();
        this.currentTurn = state.getCurrentTurn();
        this.status = state.getStatus();
        List<Move> historyCopy = new ArrayList<>(state.getMoveHistory().size());
        for (Move move : state.getMoveHistory()) {
            historyCopy.add(move.copy());
        }
        this.moveHistory = historyCopy;
        this.timeControl = state.getTimeControl();
        this.whiteMillisRemaining = state.getRemainingMillis(PlayerColor.WHITE);
        this.blackMillisRemaining = state.getRemainingMillis(PlayerColor.BLACK);
        this.loserColor = state.getLoserColor();
        this.drawReason = state.getDrawReason();
        this.positionCounts = new HashMap<>(state.getPositionCounts());
    }
}
