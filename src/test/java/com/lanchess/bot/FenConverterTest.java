package com.lanchess.bot;

import com.lanchess.model.GameState;
import com.lanchess.model.GameStatus;
import com.lanchess.model.Move;
import com.lanchess.model.PieceType;
import com.lanchess.model.PlayerColor;
import com.lanchess.model.pieces.King;
import com.lanchess.model.pieces.Pawn;
import com.lanchess.model.pieces.Piece;
import com.lanchess.model.pieces.Rook;
import com.lanchess.server.MoveValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test untuk FenConverter - jembatan format antara GameState internal
 * dan protokol UCI yang dipakai Stockfish. Sudah diverifikasi juga secara
 * end-to-end memakai Stockfish sungguhan selama pengembangan (self-play
 * beberapa half-move, semua langkah balik tervalidasi MoveValidator kita).
 */
class FenConverterTest {

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

    @Test
    @DisplayName("FEN posisi awal standar harus persis sesuai spesifikasi FEN")
    void startingPositionFen() {
        GameState state = new GameState();
        String fen = FenConverter.toFen(state);
        assertEquals("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1", fen);
    }

    @Test
    @DisplayName("Hak castling hilang untuk sisi yang rajanya sudah pernah bergerak")
    void castlingRightsLostWhenKingMoved() {
        GameState state = new GameState();
        state.getPieceAt(7, 4).setHasMoved(true); // paksa raja putih "sudah pernah gerak"

        String fen = FenConverter.toFen(state);
        String castlingField = fen.split(" ")[2];

        assertFalse(castlingField.contains("K"));
        assertFalse(castlingField.contains("Q"));
        assertTrue(castlingField.contains("k"));
        assertTrue(castlingField.contains("q"));
    }

    @Test
    @DisplayName("Hak castling hilang untuk satu sisi saja kalau cuma satu benteng yang pernah gerak")
    void castlingRightsLostForOneSideOnly() {
        GameState state = new GameState();
        state.getPieceAt(7, 7).setHasMoved(true); // rook h1 (kingside) pernah gerak

        String fen = FenConverter.toFen(state);
        String castlingField = fen.split(" ")[2];

        assertFalse(castlingField.contains("K")); // kingside putih hilang
        assertTrue(castlingField.contains("Q"));   // queenside putih tetap ada
    }

    @Test
    @DisplayName("Kotak en passant muncul di FEN tepat setelah pion lompat 2 kotak")
    void enPassantTargetAppearsAfterDoubleStep() {
        GameState state = new GameState();
        Move raw = new Move(6, 4, 4, 4, PieceType.PAWN, PlayerColor.WHITE); // e2-e4
        Optional<Move> legal = MoveValidator.findLegalMove(state, raw);
        MoveValidator.executeMove(state, legal.orElseThrow());

        String fen = FenConverter.toFen(state);
        String enPassantField = fen.split(" ")[3];
        assertEquals("e3", enPassantField);
    }

    @Test
    @DisplayName("Kotak en passant kembali '-' setelah giliran berikutnya (tidak diambil)")
    void enPassantTargetClearsNextTurn() {
        GameState state = new GameState();
        MoveValidator.executeMove(state,
                MoveValidator.findLegalMove(state, new Move(6, 4, 4, 4, PieceType.PAWN, PlayerColor.WHITE)).orElseThrow());
        // Langkah knight (BUKAN pion) supaya tidak membuat target en passant baru,
        // sehingga benar-benar menguji target lama sudah "kedaluwarsa"
        MoveValidator.executeMove(state,
                MoveValidator.findLegalMove(state, new Move(0, 1, 2, 2, PieceType.KNIGHT, PlayerColor.BLACK)).orElseThrow());

        String fen = FenConverter.toFen(state);
        assertEquals("-", fen.split(" ")[3]);
    }

    @Test
    @DisplayName("Giliran aktif ('w'/'b') di FEN sesuai currentTurn")
    void activeColorReflectsCurrentTurn() {
        GameState state = new GameState();
        assertEquals("w", FenConverter.toFen(state).split(" ")[1]);

        MoveValidator.executeMove(state,
                MoveValidator.findLegalMove(state, new Move(6, 4, 4, 4, PieceType.PAWN, PlayerColor.WHITE)).orElseThrow());
        assertEquals("b", FenConverter.toFen(state).split(" ")[1]);
    }

    @Test
    @DisplayName("parseUciMove mengonversi notasi UCI biasa menjadi Move dengan koordinat benar")
    void parseUciMoveBasic() {
        GameState state = new GameState();
        Move move = FenConverter.parseUciMove("e2e4", state);

        assertEquals(6, move.getFromRow());
        assertEquals(4, move.getFromCol());
        assertEquals(4, move.getToRow());
        assertEquals(4, move.getToCol());
        assertEquals(PieceType.PAWN, move.getPieceType());
        assertEquals(PlayerColor.WHITE, move.getPieceColor());
        assertFalse(move.isPromotion());
    }

    @Test
    @DisplayName("parseUciMove mendeteksi suffix promosi (mis. 'e7e8q')")
    void parseUciMoveWithPromotion() {
        Piece[][] board = emptyBoard();
        board[1][4] = new Pawn(PlayerColor.WHITE, 1, 4);
        board[7][4] = new King(PlayerColor.WHITE, 7, 4);
        board[0][0] = new King(PlayerColor.BLACK, 0, 0);
        GameState state = stateWithBoard(board, PlayerColor.WHITE);

        Move move = FenConverter.parseUciMove("e7e8q", state);
        assertTrue(move.isPromotion());
        assertEquals(PieceType.QUEEN, move.getPromotionType());
    }

    @Test
    @DisplayName("Round-trip: Move hasil parseUciMove tetap dikenali legal oleh MoveValidator")
    void parsedUciMoveIsAcceptedByMoveValidator() {
        GameState state = new GameState();
        Move parsed = FenConverter.parseUciMove("g1f3", state); // Nf3, langkah pembuka umum

        Optional<Move> legal = MoveValidator.findLegalMove(state, parsed);
        assertTrue(legal.isPresent(), "Move hasil parsing UCI harus tetap lolos validasi MoveValidator");
        assertEquals(PieceType.KNIGHT, legal.get().getPieceType());
    }

    @Test
    @DisplayName("Rook yang bukan di kotak sudut tidak keliru dianggap memenuhi syarat castling")
    void nonCornerRookDoesNotGrantCastlingRights() {
        Piece[][] board = emptyBoard();
        board[7][4] = new King(PlayerColor.WHITE, 7, 4);
        board[0][4] = new King(PlayerColor.BLACK, 0, 4);
        board[7][3] = new Rook(PlayerColor.WHITE, 7, 3); // rook di d1, BUKAN a1/h1
        GameState state = stateWithBoard(board, PlayerColor.WHITE);

        String castlingField = FenConverter.toFen(state).split(" ")[2];
        assertEquals("-", castlingField);
    }
}
