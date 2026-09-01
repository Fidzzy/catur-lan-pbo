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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test untuk MoveValidator - satu-satunya sumber kebenaran aturan catur.
 *
 * Konvensi papan (sama dengan Pawn.java/GameState.java):
 *   row 0..1 = sisi BLACK, row 6..7 = sisi WHITE, kolom 0..7 = a..h.
 */
class MoveValidatorTest {

    // =========================================================================
    // Helper
    // =========================================================================

    /** Papan kosong 8x8, dipakai untuk menyusun posisi custom per skenario test. */
    private Piece[][] emptyBoard() {
        return new Piece[8][8];
    }

    private GameState stateWithBoard(Piece[][] board, PlayerColor turn) {
        GameState state = new GameState();
        state.setBoard(board);
        state.setCurrentTurn(turn);
        state.setStatus(GameStatus.PLAYING);
        return state;
    }

    /** Mainkan satu langkah lewat alur validasi resmi (findLegalMove -> executeMove), seperti server sungguhan. */
    private Move play(GameState state, int fromRow, int fromCol, int toRow, int toCol) {
        Piece piece = state.getPieceAt(fromRow, fromCol);
        assertTrue(piece != null, "Tidak ada bidak di (" + fromRow + "," + fromCol + ")");

        Move raw = new Move(fromRow, fromCol, toRow, toCol, piece.getType(), piece.getColor());
        Optional<Move> legal = MoveValidator.findLegalMove(state, raw);
        assertTrue(legal.isPresent(),
                "Diharapkan legal: (%d,%d)->(%d,%d)".formatted(fromRow, fromCol, toRow, toCol));

        MoveValidator.executeMove(state, legal.get());
        return legal.get();
    }

    private void assertIllegal(GameState state, int fromRow, int fromCol, int toRow, int toCol) {
        Piece piece = state.getPieceAt(fromRow, fromCol);
        assertTrue(piece != null, "Tidak ada bidak di (" + fromRow + "," + fromCol + ")");
        Move raw = new Move(fromRow, fromCol, toRow, toCol, piece.getType(), piece.getColor());
        Optional<Move> legal = MoveValidator.findLegalMove(state, raw);
        assertTrue(legal.isEmpty(),
                "Diharapkan ILEGAL: (%d,%d)->(%d,%d)".formatted(fromRow, fromCol, toRow, toCol));
    }

    // =========================================================================
    // Basic moves
    // =========================================================================

    @Test
    @DisplayName("Pion putih boleh maju 2 kotak dari posisi awal")
    void pawnDoubleStepFromStart() {
        GameState state = new GameState(); // papan standar
        Move move = play(state, 6, 4, 4, 4); // e2-e4
        assertFalse(move.isCapture());
        assertEquals(PlayerColor.BLACK, state.getCurrentTurn());
    }

    @Test
    @DisplayName("Pion TIDAK boleh maju 2 kotak kalau bukan dari posisi awal")
    void pawnCannotDoubleStepAfterMoving() {
        GameState state = new GameState();
        play(state, 6, 4, 4, 4); // e2-e4 (WHITE)
        play(state, 1, 0, 3, 0); // a7-a5 (BLACK, biar giliran balik ke WHITE)
        assertIllegal(state, 4, 4, 2, 4); // pion putih di e4 coba lompat 2 lagi -> ilegal
    }

    @Test
    @DisplayName("Bidak tidak boleh menembus bidak lain (sliding piece diblok)")
    void slidingPieceBlockedByOwnPiece() {
        GameState state = new GameState(); // papan standar, semua bidak berat masih diblok pion sendiri
        assertIllegal(state, 7, 0, 5, 0); // rook a1 coba tembus pion a2
    }

    @Test
    @DisplayName("Knight boleh melompati bidak lain")
    void knightCanJumpOverPieces() {
        GameState state = new GameState();
        Move move = play(state, 7, 1, 5, 2); // Nb1-c3, melompati pion sendiri
        assertEquals(PieceType.KNIGHT, move.getPieceType());
    }

    @Test
    @DisplayName("Langkah yang membuat raja sendiri diskak harus ditolak (pinned piece)")
    void cannotMoveIntoOrLeaveOwnKingInCheck() {
        // Raja putih e1, benteng hitam menyerang dari e8 lewat kolom e, pion putih di e2 pinned
        Piece[][] board = emptyBoard();
        board[7][4] = new King(PlayerColor.WHITE, 7, 4);   // Ke1
        board[6][4] = new Pawn(PlayerColor.WHITE, 6, 4);   // pion e2, pinned
        board[0][4] = new Rook(PlayerColor.BLACK, 0, 4);   // Re8
        board[0][0] = new King(PlayerColor.BLACK, 0, 0);   // Ka8, sekadar biar findKing tidak error

        GameState state = stateWithBoard(board, PlayerColor.WHITE);

        // Pion pinned tidak boleh geser ke samping (akan membuka skak), tapi maju di kolom yang sama masih boleh
        assertIllegal(state, 6, 4, 5, 3); // pion coba capture diagonal ke kolom lain -> ilegal (tidak ada apa2 di sana pula)
        Move forward = play(state, 6, 4, 5, 4); // maju lurus tetap di kolom e -> tetap legal, raja tetap terlindung
        assertEquals(PieceType.PAWN, forward.getPieceType());
    }

    // =========================================================================
    // Castling
    // =========================================================================

    @Test
    @DisplayName("Castling kingside legal ketika jalur kosong & tidak diserang")
    void kingsideCastlingLegalWhenClear() {
        Piece[][] board = emptyBoard();
        board[7][4] = new King(PlayerColor.WHITE, 7, 4);  // Ke1
        board[7][7] = new Rook(PlayerColor.WHITE, 7, 7);  // Rh1
        board[0][4] = new King(PlayerColor.BLACK, 0, 4);  // Ke8, jauh dari aksi

        GameState state = stateWithBoard(board, PlayerColor.WHITE);
        Move move = play(state, 7, 4, 7, 6); // O-O
        assertTrue(move.isCastling());

        // Benteng harus ikut pindah ke f1 (row7,col5)
        assertTrue(state.getPieceAt(7, 5) instanceof Rook);
        assertNull(state.getPieceAt(7, 7));
        assertTrue(state.getPieceAt(7, 6) instanceof King);
    }

    @Test
    @DisplayName("Castling queenside legal ketika jalur kosong & tidak diserang")
    void queensideCastlingLegalWhenClear() {
        Piece[][] board = emptyBoard();
        board[7][4] = new King(PlayerColor.WHITE, 7, 4);  // Ke1
        board[7][0] = new Rook(PlayerColor.WHITE, 7, 0);  // Ra1
        board[0][4] = new King(PlayerColor.BLACK, 0, 4);

        GameState state = stateWithBoard(board, PlayerColor.WHITE);
        Move move = play(state, 7, 4, 7, 2); // O-O-O
        assertTrue(move.isCastling());
        assertTrue(state.getPieceAt(7, 3) instanceof Rook);
        assertNull(state.getPieceAt(7, 0));
    }

    @Test
    @DisplayName("Castling ILEGAL kalau raja sudah pernah bergerak")
    void castlingIllegalIfKingHasMoved() {
        Piece[][] board = emptyBoard();
        King king = new King(PlayerColor.WHITE, 7, 4);
        king.setHasMoved(true); // simulasikan raja sudah pernah gerak lalu balik lagi
        board[7][4] = king;
        board[7][7] = new Rook(PlayerColor.WHITE, 7, 7);
        board[0][4] = new King(PlayerColor.BLACK, 0, 4);

        GameState state = stateWithBoard(board, PlayerColor.WHITE);
        assertIllegal(state, 7, 4, 7, 6);
    }

    @Test
    @DisplayName("Castling ILEGAL kalau raja sedang skak")
    void castlingIllegalWhileInCheck() {
        Piece[][] board = emptyBoard();
        board[7][4] = new King(PlayerColor.WHITE, 7, 4);  // Ke1
        board[7][7] = new Rook(PlayerColor.WHITE, 7, 7);  // Rh1
        board[0][4] = new Rook(PlayerColor.BLACK, 0, 4);  // Re8, menyerang lurus ke Ke1
        board[0][0] = new King(PlayerColor.BLACK, 0, 0);

        GameState state = stateWithBoard(board, PlayerColor.WHITE);
        assertTrue(MoveValidator.isInCheck(state, PlayerColor.WHITE));
        assertIllegal(state, 7, 4, 7, 6);
    }

    @Test
    @DisplayName("Castling ILEGAL kalau raja melewati kotak yang diserang lawan")
    void castlingIllegalIfPassingThroughAttackedSquare() {
        Piece[][] board = emptyBoard();
        board[7][4] = new King(PlayerColor.WHITE, 7, 4);  // Ke1
        board[7][7] = new Rook(PlayerColor.WHITE, 7, 7);  // Rh1
        board[0][5] = new Rook(PlayerColor.BLACK, 0, 5);  // Rf8, menyerang kolom f (f1 dilewati raja saat O-O)
        board[0][0] = new King(PlayerColor.BLACK, 0, 0);

        GameState state = stateWithBoard(board, PlayerColor.WHITE);
        assertFalse(MoveValidator.isInCheck(state, PlayerColor.WHITE)); // raja sendiri belum diskak
        assertIllegal(state, 7, 4, 7, 6); // tapi kotak f1 yang dilewati diserang -> tetap ilegal
    }

    // =========================================================================
    // En passant
    // =========================================================================

    @Test
    @DisplayName("En passant legal tepat 1 giliran setelah lawan langkah ganda")
    void enPassantLegalImmediatelyAfterDoubleStep() {
        Piece[][] board = emptyBoard();
        board[3][4] = new Pawn(PlayerColor.WHITE, 3, 4);  // pion putih sudah di e5
        board[1][3] = new Pawn(PlayerColor.BLACK, 1, 3);  // pion hitam di d7, belum gerak
        board[7][4] = new King(PlayerColor.WHITE, 7, 4);
        board[0][4] = new King(PlayerColor.BLACK, 0, 4);

        GameState state = stateWithBoard(board, PlayerColor.BLACK);
        play(state, 1, 3, 3, 3); // d7-d5 (langkah ganda, mendarat tepat di sebelah pion putih)

        Move ep = play(state, 3, 4, 2, 3); // exd6 e.p.
        assertTrue(ep.isEnPassant());
        assertEquals(PieceType.PAWN, ep.getCapturedType());
        assertNull(state.getPieceAt(3, 3), "Pion hitam yang di-en-passant harus hilang dari kotak asalnya");
        assertTrue(state.getPieceAt(2, 3) instanceof Pawn);
    }

    @Test
    @DisplayName("En passant TIDAK legal lagi kalau ditunda 1 giliran")
    void enPassantExpiresAfterOneTurn() {
        Piece[][] board = emptyBoard();
        board[3][4] = new Pawn(PlayerColor.WHITE, 3, 4);
        board[1][3] = new Pawn(PlayerColor.BLACK, 1, 3);
        board[7][4] = new King(PlayerColor.WHITE, 7, 4);
        board[7][0] = new Pawn(PlayerColor.WHITE, 6, 0); // dummy biar putih punya langkah lain
        board[0][4] = new King(PlayerColor.BLACK, 0, 4);
        board[1][7] = new Pawn(PlayerColor.BLACK, 1, 7); // dummy biar hitam punya langkah lain

        GameState state = stateWithBoard(board, PlayerColor.BLACK);
        play(state, 1, 3, 3, 3); // d7-d5
        play(state, 6, 0, 5, 0); // WHITE main langkah lain dulu (menunda en passant)
        play(state, 1, 7, 3, 7); // BLACK juga main langkah lain

        assertIllegal(state, 3, 4, 2, 3); // en passant sudah kedaluwarsa
    }

    // =========================================================================
    // Promosi
    // =========================================================================

    @Test
    @DisplayName("Pion promosi ke Queen (default) saat mencapai baris terakhir")
    void pawnPromotesToQueenByDefault() {
        Piece[][] board = emptyBoard();
        board[1][0] = new Pawn(PlayerColor.WHITE, 1, 0); // pion putih di a7
        board[7][4] = new King(PlayerColor.WHITE, 7, 4);
        board[0][4] = new King(PlayerColor.BLACK, 0, 4);

        GameState state = stateWithBoard(board, PlayerColor.WHITE);
        Move move = play(state, 1, 0, 0, 0); // a7-a8

        assertTrue(move.isPromotion());
        assertEquals(PieceType.QUEEN, move.getPromotionType());
        assertTrue(state.getPieceAt(0, 0) instanceof Queen);
        assertTrue(state.getPieceAt(0, 0).hasMoved());
    }

    @Test
    @DisplayName("Pion boleh underpromotion ke Knight sesuai pilihan client")
    void pawnCanUnderpromoteToKnight() {
        Piece[][] board = emptyBoard();
        board[1][0] = new Pawn(PlayerColor.WHITE, 1, 0);
        board[7][4] = new King(PlayerColor.WHITE, 7, 4);
        board[0][4] = new King(PlayerColor.BLACK, 0, 4);

        GameState state = stateWithBoard(board, PlayerColor.WHITE);
        Move raw = new Move(1, 0, 0, 0, PieceType.PAWN, PlayerColor.WHITE);
        raw.setPromotionType(PieceType.KNIGHT);

        Optional<Move> legal = MoveValidator.findLegalMove(state, raw);
        assertTrue(legal.isPresent());
        assertEquals(PieceType.KNIGHT, legal.get().getPromotionType());

        MoveValidator.executeMove(state, legal.get());
        assertEquals(PieceType.KNIGHT, state.getPieceAt(0, 0).getType());
    }

    // =========================================================================
    // Checkmate & Stalemate
    // =========================================================================

    @Test
    @DisplayName("Fool's Mate - checkmate tercepat dalam catur (4 langkah)")
    void foolsMateResultsInCheckmate() {
        GameState state = new GameState();
        play(state, 6, 5, 5, 5); // 1. f3
        play(state, 1, 4, 3, 4); // 1... e5
        play(state, 6, 6, 4, 6); // 2. g4
        play(state, 0, 3, 4, 7); // 2... Qh4#

        assertEquals(GameStatus.CHECKMATE, state.getStatus());
        assertEquals(PlayerColor.WHITE, state.getCurrentTurn()); // WHITE yang terkena mat
        assertTrue(MoveValidator.getAllLegalMoves(state, PlayerColor.WHITE).isEmpty());
    }

    @Test
    @DisplayName("Posisi stalemate klasik (King+Queen vs King) terdeteksi benar")
    void classicStalemateIsDetected() {
        Piece[][] board = emptyBoard();
        board[0][0] = new King(PlayerColor.BLACK, 0, 0);   // Ka8
        board[1][2] = new King(PlayerColor.WHITE, 1, 2);   // Kc7
        board[2][1] = new Queen(PlayerColor.WHITE, 2, 1);  // Qb6

        GameState state = stateWithBoard(board, PlayerColor.BLACK);
        MoveValidator.updateGameStatus(state);

        assertFalse(MoveValidator.isInCheck(state, PlayerColor.BLACK), "Stalemate berarti TIDAK sedang skak");
        assertTrue(MoveValidator.getAllLegalMoves(state, PlayerColor.BLACK).isEmpty());
        assertEquals(GameStatus.STALEMATE, state.getStatus());
    }

    @Test
    @DisplayName("Status CHECK (bukan checkmate) terdeteksi ketika masih ada langkah keluar dari skak")
    void checkStatusWhenEscapeMoveExists() {
        Piece[][] board = emptyBoard();
        board[7][4] = new King(PlayerColor.WHITE, 7, 4);  // Ke1, punya banyak ruang kabur
        board[6][4] = new Rook(PlayerColor.BLACK, 6, 4);  // Re2, menyerang lurus ke Ke1
        board[0][0] = new King(PlayerColor.BLACK, 0, 0);

        GameState state = stateWithBoard(board, PlayerColor.WHITE);
        MoveValidator.updateGameStatus(state);

        assertEquals(GameStatus.CHECK, state.getStatus());
        assertFalse(MoveValidator.getAllLegalMoves(state, PlayerColor.WHITE).isEmpty());
    }

    // =========================================================================
    // Server-authoritative: klaim flag dari client tidak dipercaya
    // =========================================================================

    @Test
    @DisplayName("Server mengabaikan klaim isCastling dari client dan menentukan ulang sendiri")
    void serverRederivesFlagsIndependently() {
        GameState state = new GameState();

        // Client (nakal atau buggy) kirim raw Move biasa TANPA menandai castling,
        // tapi server tetap harus mengenali ini sebagai castling kalau memang
        // koordinatnya cocok, karena findLegalMove mencocokkan ke katalog
        // legal move yang di-generate ulang sepenuhnya oleh server.
        Piece[][] board = emptyBoard();
        board[7][4] = new King(PlayerColor.WHITE, 7, 4);
        board[7][7] = new Rook(PlayerColor.WHITE, 7, 7);
        board[0][4] = new King(PlayerColor.BLACK, 0, 4);
        GameState custom = stateWithBoard(board, PlayerColor.WHITE);

        Move rawFromClient = new Move(7, 4, 7, 6, PieceType.KING, PlayerColor.WHITE);
        // sengaja TIDAK memanggil rawFromClient.setCastling(true) - simulasikan client polos

        Optional<Move> resolved = MoveValidator.findLegalMove(custom, rawFromClient);
        assertTrue(resolved.isPresent());
        assertTrue(resolved.get().isCastling(), "Server harus tetap mendeteksi ini sebagai castling walau client tidak menandainya");
    }
}
