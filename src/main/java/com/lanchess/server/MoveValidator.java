package com.lanchess.server;

import com.lanchess.model.GameState;
import com.lanchess.model.GameStatus;
import com.lanchess.model.Move;
import com.lanchess.model.PieceType;
import com.lanchess.model.PlayerColor;
import com.lanchess.model.pieces.King;
import com.lanchess.model.pieces.Pawn;
import com.lanchess.model.pieces.Piece;
import com.lanchess.model.pieces.Queen;
import com.lanchess.model.pieces.Rook;
import com.lanchess.model.pieces.Bishop;
import com.lanchess.model.pieces.Knight;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Semua aturan catur (SATU-SATUNYA sumber kebenaran) tinggal di sini.
 * Client TIDAK PERNAH menentukan legal/tidaknya sebuah langkah - client
 * hanya kirim niat langkah (Move dengan from/to/promotionType), server yang
 * memutuskan lewat class ini, lalu broadcast hasilnya.
 *
 * Alur pemakaian di ClientHandler/GameServer:
 *   1. terima Move mentah dari client
 *   2. Optional<Move> enriched = MoveValidator.findLegalMove(state, rawMove)
 *   3. jika kosong -> kirim MOVE_REJECTED
 *   4. jika ada -> MoveValidator.executeMove(state, enriched.get())
 *                  lalu MoveValidator.updateGameStatus(state) untuk cek skak/mati
 *                  lalu broadcast STATE_UPDATE ke kedua client
 */
public final class MoveValidator {

    private MoveValidator() {
    }

    // =========================================================================
    // LEGAL MOVE GENERATION
    // =========================================================================

    /**
     * @return semua langkah LEGAL (sudah difilter agar tidak membuat raja
     *         sendiri skak) untuk bidak di (row, col). List kosong jika
     *         kotak kosong atau tidak ada langkah legal.
     */
    public static List<Move> getLegalMoves(GameState state, int row, int col) {
        Piece[][] board = state.getBoard();
        Piece piece = board[row][col];
        if (piece == null) return List.of();

        PlayerColor color = piece.getColor();
        List<Move> candidates = new ArrayList<>();

        // Langkah pseudo-legal dasar (pola gerak bidak + blocking + capture biasa)
        for (int[] target : piece.getMoves(board)) {
            candidates.add(buildBasicMove(board, piece, target[0], target[1]));
        }

        // Langkah spesial yang butuh konteks di luar papan statis
        if (piece instanceof Pawn pawn) {
            addEnPassantCandidate(state, pawn, candidates);
        }
        if (piece instanceof King king) {
            addCastlingCandidates(state, board, king, candidates);
        }

        // Filter: buang langkah yang membuat raja sendiri (setelah langkah) diskak
        List<Move> legal = new ArrayList<>();
        for (Move m : candidates) {
            if (!leavesKingInCheck(state, m, color)) {
                legal.add(m);
            }
        }
        return legal;
    }

    /** Semua langkah legal untuk SEMUA bidak warna tertentu. Dipakai deteksi checkmate/stalemate. */
    public static List<Move> getAllLegalMoves(GameState state, PlayerColor color) {
        List<Move> all = new ArrayList<>();
        Piece[][] board = state.getBoard();
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                Piece p = board[r][c];
                if (p != null && p.getColor() == color) {
                    all.addAll(getLegalMoves(state, r, c));
                }
            }
        }
        return all;
    }

    /**
     * Cocokkan Move "mentah" dari client (hanya from/to/promotionType yang
     * bisa dipercaya) dengan daftar langkah legal aktual di server.
     *
     * @return Move versi ENRICHED (castling/enPassant/capturedType terisi
     *         benar oleh server) jika langkah itu legal, atau empty jika ilegal.
     */
    public static Optional<Move> findLegalMove(GameState state, Move rawMove) {
        Piece piece = state.getPieceAt(rawMove.getFromRow(), rawMove.getFromCol());
        if (piece == null || piece.getColor() != state.getCurrentTurn()) {
            return Optional.empty();
        }

        List<Move> legalMoves = getLegalMoves(state, rawMove.getFromRow(), rawMove.getFromCol());
        for (Move legal : legalMoves) {
            if (legal.getToRow() == rawMove.getToRow() && legal.getToCol() == rawMove.getToCol()) {
                if (legal.isPromotion()) {
                    // Pion sampai baris terakhir: promotionType WAJIB valid dari client
                    PieceType chosen = rawMove.getPromotionType();
                    if (chosen == null || chosen == PieceType.KING || chosen == PieceType.PAWN) {
                        chosen = PieceType.QUEEN; // default aman kalau client tidak kirim/kirim tidak valid
                    }
                    legal.setPromotionType(chosen);
                }
                return Optional.of(legal);
            }
        }
        return Optional.empty();
    }

    // =========================================================================
    // EXECUTE MOVE (mengubah GameState SESUNGGUHNYA, hanya dipanggil setelah lolos validasi)
    // =========================================================================

    public static void executeMove(GameState state, Move move) {
        Piece[][] board = state.getBoard();
        Piece piece = board[move.getFromRow()][move.getFromCol()];

        // --- En passant: bidak yang tertangkap BUKAN di kotak tujuan, tapi di sebelahnya ---
        if (move.isEnPassant()) {
            board[move.getFromRow()][move.getToCol()] = null;
        }

        // --- Castling: pindahkan juga benteng terkait ---
        if (move.isCastling()) {
            int row = move.getFromRow();
            if (move.isKingsideCastling()) {
                Piece rook = board[row][7];
                board[row][5] = rook;
                board[row][7] = null;
                rook.moveTo(row, 5);
            } else {
                Piece rook = board[row][0];
                board[row][3] = rook;
                board[row][0] = null;
                rook.moveTo(row, 3);
            }
        }

        // --- Pindahkan bidak utama ---
        board[move.getToRow()][move.getToCol()] = piece;
        board[move.getFromRow()][move.getFromCol()] = null;
        piece.moveTo(move.getToRow(), move.getToCol());

        // --- Promosi: ganti bidak pion dengan bidak baru sesuai pilihan ---
        if (move.isPromotion()) {
            Piece promoted = createPiece(move.getPromotionType(), piece.getColor(),
                    move.getToRow(), move.getToCol());
            promoted.setHasMoved(true);
            board[move.getToRow()][move.getToCol()] = promoted;
        }

        state.addMove(move);
        state.switchTurn();
        updateGameStatus(state);
    }

    /** Update GameStatus (PLAYING/CHECK/CHECKMATE/STALEMATE) sesuai giliran SETELAH langkah dieksekusi. */
    public static void updateGameStatus(GameState state) {
        PlayerColor turn = state.getCurrentTurn();
        boolean inCheck = isInCheck(state, turn);
        boolean hasLegalMove = !getAllLegalMoves(state, turn).isEmpty();

        if (inCheck && !hasLegalMove) {
            state.setStatus(GameStatus.CHECKMATE);
        } else if (!inCheck && !hasLegalMove) {
            state.setStatus(GameStatus.STALEMATE);
        } else if (inCheck) {
            state.setStatus(GameStatus.CHECK);
        } else {
            state.setStatus(GameStatus.PLAYING);
        }
    }

    // =========================================================================
    // CHECK DETECTION
    // =========================================================================

    public static boolean isInCheck(GameState state, PlayerColor color) {
        King king = state.findKing(color);
        return isSquareAttacked(state.getBoard(), king.getRow(), king.getCol(), color.opposite());
    }

    /**
     * @return true jika kotak (row, col) diserang oleh bidak warna attackerColor.
     *         Dipakai untuk: cek skak, dan cek keamanan kotak yang dilewati/dituju
     *         raja saat castling.
     */
    public static boolean isSquareAttacked(Piece[][] board, int row, int col, PlayerColor attackerColor) {
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                Piece p = board[r][c];
                if (p == null || p.getColor() != attackerColor) continue;

                if (p instanceof Pawn pawn) {
                    // Kotak yang "diserang" pion beda dengan kotak yang boleh ia LANGKAHI:
                    // pion menyerang diagonal walau kotak itu kosong (relevan untuk
                    // menentukan raja lawan boleh/tidak mendarat di situ).
                    int attackRow = pawn.getRow() + pawn.getDirection();
                    if (attackRow == row && Math.abs(pawn.getCol() - col) == 1) {
                        return true;
                    }
                } else {
                    for (int[] mv : p.getMoves(board)) {
                        if (mv[0] == row && mv[1] == col) return true;
                    }
                }
            }
        }
        return false;
    }

    // =========================================================================
    // HELPER: bangun kandidat Move, deteksi capture normal
    // =========================================================================

    private static Move buildBasicMove(Piece[][] board, Piece piece, int toRow, int toCol) {
        Move move = new Move(piece.getRow(), piece.getCol(), toRow, toCol,
                piece.getType(), piece.getColor());

        Piece target = board[toRow][toCol];
        if (target != null) {
            move.setCapturedType(target.getType());
        }

        // Tandai promosi (promotionType diisi belakangan oleh client/default QUEEN)
        if (piece instanceof Pawn pawn && toRow == pawn.getPromotionRow()) {
            move.setPromotionType(PieceType.QUEEN); // placeholder, akan di-overwrite findLegalMove()
        }

        return move;
    }

    private static void addEnPassantCandidate(GameState state, Pawn pawn, List<Move> candidates) {
        Move last = state.getLastMove();
        if (last == null || last.getPieceType() != PieceType.PAWN) return;
        if (Math.abs(last.getToRow() - last.getFromRow()) != 2) return; // harus baru saja langkah ganda
        if (last.getToRow() != pawn.getRow()) return;                  // harus sebaris dengan pion kita
        if (Math.abs(last.getToCol() - pawn.getCol()) != 1) return;    // harus tepat di sebelah

        int targetRow = pawn.getRow() + pawn.getDirection();
        int targetCol = last.getToCol();

        Move enPassant = new Move(pawn.getRow(), pawn.getCol(), targetRow, targetCol,
                PieceType.PAWN, pawn.getColor());
        enPassant.setEnPassant(true);
        enPassant.setCapturedType(PieceType.PAWN);
        candidates.add(enPassant);
    }

    private static void addCastlingCandidates(GameState state, Piece[][] board, King king, List<Move> candidates) {
        if (king.hasMoved()) return;

        PlayerColor color = king.getColor();
        PlayerColor opponent = color.opposite();
        int row = king.getRow();

        // Raja sedang skak -> tidak boleh castling sama sekali
        if (isSquareAttacked(board, row, king.getCol(), opponent)) return;

        // --- Kingside (raja & benteng di kolom 7, raja pindah ke kolom 6) ---
        Piece kingsideRook = board[row][7];
        if (kingsideRook instanceof Rook && !kingsideRook.hasMoved() && kingsideRook.getColor() == color
                && board[row][5] == null && board[row][6] == null
                && !isSquareAttacked(board, row, 5, opponent)
                && !isSquareAttacked(board, row, 6, opponent)) {
            Move castle = new Move(row, king.getCol(), row, 6, PieceType.KING, color);
            castle.setCastling(true);
            candidates.add(castle);
        }

        // --- Queenside (raja & benteng di kolom 0, raja pindah ke kolom 2) ---
        Piece queensideRook = board[row][0];
        if (queensideRook instanceof Rook && !queensideRook.hasMoved() && queensideRook.getColor() == color
                && board[row][1] == null && board[row][2] == null && board[row][3] == null
                && !isSquareAttacked(board, row, 3, opponent)
                && !isSquareAttacked(board, row, 2, opponent)) {
            Move castle = new Move(row, king.getCol(), row, 2, PieceType.KING, color);
            castle.setCastling(true);
            candidates.add(castle);
        }
    }

    // =========================================================================
    // HELPER: simulasi langkah di papan bayangan untuk cek skak
    // =========================================================================

    private static boolean leavesKingInCheck(GameState state, Move move, PlayerColor color) {
        Piece[][] simBoard = state.deepCopyBoard();
        applySimulated(simBoard, move);

        int kingRow, kingCol;
        if (move.getPieceType() == PieceType.KING) {
            kingRow = move.getToRow();
            kingCol = move.getToCol();
        } else {
            King king = findKingOnBoard(simBoard, color);
            kingRow = king.getRow();
            kingCol = king.getCol();
        }

        return isSquareAttacked(simBoard, kingRow, kingCol, color.opposite());
    }

    private static void applySimulated(Piece[][] board, Move move) {
        Piece piece = board[move.getFromRow()][move.getFromCol()];

        if (move.isEnPassant()) {
            board[move.getFromRow()][move.getToCol()] = null;
        }
        if (move.isCastling()) {
            int row = move.getFromRow();
            if (move.isKingsideCastling()) {
                Piece rook = board[row][7];
                board[row][5] = rook;
                board[row][7] = null;
                if (rook != null) rook.moveTo(row, 5);
            } else {
                Piece rook = board[row][0];
                board[row][3] = rook;
                board[row][0] = null;
                if (rook != null) rook.moveTo(row, 3);
            }
        }

        board[move.getToRow()][move.getToCol()] = piece;
        board[move.getFromRow()][move.getFromCol()] = null;
        if (piece != null) piece.moveTo(move.getToRow(), move.getToCol());
        // Promosi tidak relevan untuk simulasi cek-skak (jenis bidak baru sama-sama
        // bukan Raja, tidak mempengaruhi hasil isSquareAttacked terhadap raja sendiri)
    }

    private static King findKingOnBoard(Piece[][] board, PlayerColor color) {
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                if (board[r][c] instanceof King k && k.getColor() == color) {
                    return k;
                }
            }
        }
        throw new IllegalStateException("Raja " + color + " tidak ditemukan di papan simulasi");
    }

    private static Piece createPiece(PieceType type, PlayerColor color, int row, int col) {
        return switch (type) {
            case QUEEN -> new Queen(color, row, col);
            case ROOK -> new Rook(color, row, col);
            case BISHOP -> new Bishop(color, row, col);
            case KNIGHT -> new Knight(color, row, col);
            default -> throw new IllegalArgumentException("Tipe promosi tidak valid: " + type);
        };
    }
}
