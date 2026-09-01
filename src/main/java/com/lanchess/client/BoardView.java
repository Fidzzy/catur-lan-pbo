package com.lanchess.client;

import com.lanchess.model.GameState;
import com.lanchess.model.Move;
import com.lanchess.model.PlayerColor;
import com.lanchess.model.pieces.Piece;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;

import java.util.List;
import java.util.Map;

/**
 * Canvas yang menggambar papan catur 8x8 memakai GraphicsContext.
 * Class ini HANYA bertanggung jawab menggambar - tidak menyimpan logika
 * game apapun. GameController yang memanggil render() setiap kali ada
 * perubahan state (STATE_UPDATE dari server / seleksi bidak lokal).
 *
 * Mendukung "flip" papan (giliran BLACK melihat papan dari sisi mereka
 * sendiri di bagian bawah layar) lewat parameter flipped di render().
 */
public class BoardView extends Canvas {

    public static final int DEFAULT_SQUARE_SIZE = 76;

    public final int squareSize;
    public final int boardPixels;

    private static final Color LIGHT_SQUARE = Color.web("#F2F2F0");
    private static final Color DARK_SQUARE = Color.web("#DDDEDC");
    private static final Color GRID_LINE = Color.web("#B9BAB8");
    private static final Color SELECTED_HIGHLIGHT = Color.web("#F2C94C", 0.55);
    private static final Color LEGAL_MOVE_DOT = Color.web("#3A3A3A", 0.45);
    private static final Color LEGAL_CAPTURE_RING = Color.web("#E0524A", 0.75);
    private static final Color LAST_MOVE_HIGHLIGHT = Color.web("#8FD08F", 0.35);
    private static final Color CHECK_HIGHLIGHT = Color.web("#E0524A", 0.55);

    private static final Map<String, String> UNICODE_SYMBOLS = Map.ofEntries(
            Map.entry("WHITE_KING", "\u2654"), Map.entry("WHITE_QUEEN", "\u2655"),
            Map.entry("WHITE_ROOK", "\u2656"), Map.entry("WHITE_BISHOP", "\u2657"),
            Map.entry("WHITE_KNIGHT", "\u2658"), Map.entry("WHITE_PAWN", "\u2659"),
            Map.entry("BLACK_KING", "\u265A"), Map.entry("BLACK_QUEEN", "\u265B"),
            Map.entry("BLACK_ROOK", "\u265C"), Map.entry("BLACK_BISHOP", "\u265D"),
            Map.entry("BLACK_KNIGHT", "\u265E"), Map.entry("BLACK_PAWN", "\u265F")
    );

    private boolean flipped = false;

    public BoardView() {
        this(DEFAULT_SQUARE_SIZE);
    }

    /** @param squareSize ukuran piksel tiap kotak - dipakai layar setup untuk preview papan yang lebih kecil. */
    public BoardView(int squareSize) {
        super(squareSize * 8, squareSize * 8);
        this.squareSize = squareSize;
        this.boardPixels = squareSize * 8;
    }

    public void setFlipped(boolean flipped) {
        this.flipped = flipped;
    }

    /** Konversi koordinat pixel klik mouse -> baris papan sesungguhnya (memperhitungkan flip). */
    public int pixelToRow(double y) {
        int displayRow = (int) (y / squareSize);
        return flipped ? 7 - displayRow : displayRow;
    }

    /** Konversi koordinat pixel klik mouse -> kolom papan sesungguhnya (memperhitungkan flip). */
    public int pixelToCol(double x) {
        int displayCol = (int) (x / squareSize);
        return flipped ? 7 - displayCol : displayCol;
    }

    private int toDisplayRow(int row) {
        return flipped ? 7 - row : row;
    }

    private int toDisplayCol(int col) {
        return flipped ? 7 - col : col;
    }

    /**
     * Gambar ulang seluruh papan.
     *
     * @param state          state permainan terkini
     * @param selectedRow    baris bidak yang sedang dipilih (null jika tidak ada seleksi)
     * @param selectedCol    kolom bidak yang sedang dipilih (null jika tidak ada seleksi)
     * @param legalMoves     daftar langkah legal dari bidak terpilih (dipakai untuk highlight titik tujuan)
     * @param kingInCheckRow baris raja yang sedang diskak, null jika tidak ada yang skak
     * @param kingInCheckCol kolom raja yang sedang diskak, null jika tidak ada yang skak
     */
    public void render(GameState state, Integer selectedRow, Integer selectedCol,
                        List<Move> legalMoves, Integer kingInCheckRow, Integer kingInCheckCol) {
        GraphicsContext gc = getGraphicsContext2D();
        gc.clearRect(0, 0, boardPixels, boardPixels);

        drawSquares(gc);
        highlightLastMove(gc, state);
        if (kingInCheckRow != null) {
            fillSquare(gc, kingInCheckRow, kingInCheckCol, CHECK_HIGHLIGHT);
        }
        if (selectedRow != null) {
            fillSquare(gc, selectedRow, selectedCol, SELECTED_HIGHLIGHT);
        }
        drawPieces(gc, state);
        if (legalMoves != null) {
            drawLegalMoveHints(gc, state, legalMoves);
        }
    }

    private void drawSquares(GraphicsContext gc) {
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                boolean light = (r + c) % 2 == 0;
                gc.setFill(light ? LIGHT_SQUARE : DARK_SQUARE);
                int dr = toDisplayRow(r);
                int dc = toDisplayCol(c);
                double x = dc * squareSize;
                double y = dr * squareSize;
                gc.fillRect(x, y, squareSize, squareSize);
                gc.setStroke(GRID_LINE);
                gc.setLineWidth(1);
                gc.strokeRect(x, y, squareSize, squareSize);
            }
        }
    }

    private void highlightLastMove(GraphicsContext gc, GameState state) {
        Move last = state.getLastMove();
        if (last == null) return;
        fillSquare(gc, last.getFromRow(), last.getFromCol(), LAST_MOVE_HIGHLIGHT);
        fillSquare(gc, last.getToRow(), last.getToCol(), LAST_MOVE_HIGHLIGHT);
    }

    private void fillSquare(GraphicsContext gc, int row, int col, Color color) {
        int dr = toDisplayRow(row);
        int dc = toDisplayCol(col);
        gc.setFill(color);
        gc.fillRect(dc * squareSize, dr * squareSize, squareSize, squareSize);
    }

    private void drawPieces(GraphicsContext gc, GameState state) {
        gc.setFont(Font.font("Serif", FontWeight.BOLD, squareSize * 0.72));
        gc.setTextAlign(TextAlignment.CENTER);

        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                Piece piece = state.getPieceAt(r, c);
                if (piece == null) continue;

                String key = piece.getColor().name() + "_" + piece.getType().name();
                String symbol = UNICODE_SYMBOLS.get(key);

                int dr = toDisplayRow(r);
                int dc = toDisplayCol(c);
                double x = dc * squareSize + squareSize / 2.0;
                double y = dr * squareSize + squareSize * 0.80;

                // Outline tipis supaya bidak putih tetap terbaca di kotak terang
                gc.setStroke(piece.getColor() == PlayerColor.WHITE ? Color.BLACK : Color.web("#444444"));
                gc.setLineWidth(1.2);
                gc.strokeText(symbol, x, y);

                gc.setFill(piece.getColor() == PlayerColor.WHITE ? Color.WHITE : Color.BLACK);
                gc.fillText(symbol, x, y);
            }
        }
    }

    private void drawLegalMoveHints(GraphicsContext gc, GameState state, List<Move> legalMoves) {
        for (Move move : legalMoves) {
            int dr = toDisplayRow(move.getToRow());
            int dc = toDisplayCol(move.getToCol());
            double centerX = dc * squareSize + squareSize / 2.0;
            double centerY = dr * squareSize + squareSize / 2.0;

            boolean isCapture = state.getPieceAt(move.getToRow(), move.getToCol()) != null || move.isEnPassant();
            if (isCapture) {
                gc.setStroke(LEGAL_CAPTURE_RING);
                gc.setLineWidth(4);
                double radius = squareSize * 0.42;
                gc.strokeOval(centerX - radius, centerY - radius, radius * 2, radius * 2);
            } else {
                gc.setFill(LEGAL_MOVE_DOT);
                double radius = squareSize * 0.14;
                gc.fillOval(centerX - radius, centerY - radius, radius * 2, radius * 2);
            }
        }
    }
}
