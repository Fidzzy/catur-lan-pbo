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

import java.util.ArrayList;
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
 *
 * RESPONSIF: Canvas ini resizable - ukuran kotak dihitung dinamis dari
 * {@code min(width, height) / 8} sehingga papan selalu square dan terpusat
 * (letterbox) mengikuti ukuran window. Setiap resize otomatis menggambar
 * ulang dari cache render terakhir, jadi controller tidak perlu kode khusus.
 */
public class BoardView extends Canvas {

    public static final int DEFAULT_SQUARE_SIZE = 76;
    public static final double DEFAULT_BOARD_SIZE = DEFAULT_SQUARE_SIZE * 8.0;
    public static final double MIN_BOARD_SIZE = 280.0;

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

    // --- Cache render terakhir supaya resize bisa redraw tanpa controller ---
    private GameState lastState;
    private Integer lastSelectedRow;
    private Integer lastSelectedCol;
    private List<Move> lastLegalMoves = List.of();
    private Integer lastCheckRow;
    private Integer lastCheckCol;
    private List<PremoveQueue.Entry> lastPremoveEntries = List.of();
    private Integer lastHintFromRow;
    private Integer lastHintFromCol;
    private Integer lastHintToRow;
    private Integer lastHintToCol;

    public BoardView() {
        this(DEFAULT_SQUARE_SIZE);
    }

    private final double prefBoardSize;

    /** @param squareSize ukuran piksel tiap kotak awal (dipakai sebagai ukuran awal, tetap bisa di-resize). */
    public BoardView(int squareSize) {
        super(squareSize * 8.0, squareSize * 8.0);
        this.prefBoardSize = squareSize * 8.0;
        setManaged(true);
        // Backup: kalau ada yang setWidth/setHeight langsung, ikut redraw.
        widthProperty().addListener((o, a, b) -> redraw());
        heightProperty().addListener((o, a, b) -> redraw());
    }

    // --- Resizable contract: Canvas bukan Region, tapi parent (StackPane/HBox)
    // memanggil resize() kalau isResizable() true. Kita set width/height di sini. ---
    @Override
    public boolean isResizable() {
        return true;
    }

    @Override
    public void resize(double width, double height) {
        // Batas bawah sengaja kecil (120) supaya preview di layar setup tetap
        // bisa mengecil; papan gameplay dibatasi holder-nya (MIN_BOARD_SIZE).
        double w = Math.max(width, 120);
        double h = Math.max(height, 120);
        setWidth(w);
        setHeight(h);
        redraw();
    }

    /** Ukuran awal yang diminta (dipakai fallback sebelum layout jalan). */
    public double getPrefBoardSize() {
        return prefBoardSize;
    }

    /** Ukuran kotak dinamis = min(width, height) / 8. */
    public double getSquareSize() {
        double w = getWidth();
        double h = getHeight();
        if (w <= 0 || h <= 0) {
            return prefBoardSize / 8.0;
        }
        return Math.min(w, h) / 8.0;
    }

    /** Offset X agar papan square selalu terpusat horizontal (letterbox). */
    private double offsetX() {
        double sq = getSquareSize();
        return (getWidth() - sq * 8.0) / 2.0;
    }

    /** Offset Y agar papan square selalu terpusat vertikal (letterbox). */
    private double offsetY() {
        double sq = getSquareSize();
        return (getHeight() - sq * 8.0) / 2.0;
    }

    public void setFlipped(boolean flipped) {
        this.flipped = flipped;
        redraw();
    }

    /** Konversi koordinat pixel klik mouse -> baris papan sesungguhnya (memperhitungkan flip + letterbox). */
    public int pixelToRow(double y) {
        double sq = getSquareSize();
        if (sq <= 0) return -1;
        int displayRow = (int) ((y - offsetY()) / sq);
        if (displayRow < 0 || displayRow > 7) return -1;
        return flipped ? 7 - displayRow : displayRow;
    }

    /** Konversi koordinat pixel klik mouse -> kolom papan sesungguhnya (memperhitungkan flip + letterbox). */
    public int pixelToCol(double x) {
        double sq = getSquareSize();
        if (sq <= 0) return -1;
        int displayCol = (int) ((x - offsetX()) / sq);
        if (displayCol < 0 || displayCol > 7) return -1;
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
        this.lastState = state;
        this.lastSelectedRow = selectedRow;
        this.lastSelectedCol = selectedCol;
        this.lastLegalMoves = legalMoves == null ? List.of() : List.copyOf(legalMoves);
        this.lastCheckRow = kingInCheckRow;
        this.lastCheckCol = kingInCheckCol;
        redrawBase();
        // Overlay premove + hint yang sudah terkunci ikut digambar ulang di atas base.
        drawPremoveOverlay();
        drawHintOverlay();
    }

    /** Gambar ulang penuh dari cache (dipanggil otomatis saat resize). */
    private void redraw() {
        if (lastState == null) return;
        redrawBase();
        drawPremoveOverlay();
        drawHintOverlay();
    }

    private void redrawBase() {
        if (lastState == null) return;
        GraphicsContext gc = getGraphicsContext2D();
        gc.clearRect(0, 0, getWidth(), getHeight());

        drawSquares(gc);
        highlightLastMove(gc, lastState);
        if (lastCheckRow != null && lastCheckCol != null) {
            fillSquare(gc, lastCheckRow, lastCheckCol, CHECK_HIGHLIGHT);
        }
        if (lastSelectedRow != null && lastSelectedCol != null) {
            fillSquare(gc, lastSelectedRow, lastSelectedCol, SELECTED_HIGHLIGHT);
        }
        drawPieces(gc, lastState);
        if (lastLegalMoves != null && !lastLegalMoves.isEmpty()) {
            drawLegalMoveHints(gc, lastState, lastLegalMoves);
        }
    }

    private void drawSquares(GraphicsContext gc) {
        double sq = getSquareSize();
        double ox = offsetX();
        double oy = offsetY();
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                boolean light = (r + c) % 2 == 0;
                gc.setFill(light ? LIGHT_SQUARE : DARK_SQUARE);
                int dr = toDisplayRow(r);
                int dc = toDisplayCol(c);
                double x = ox + dc * sq;
                double y = oy + dr * sq;
                gc.fillRect(x, y, sq, sq);
                gc.setStroke(GRID_LINE);
                gc.setLineWidth(1);
                gc.strokeRect(x, y, sq, sq);
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
        double sq = getSquareSize();
        int dr = toDisplayRow(row);
        int dc = toDisplayCol(col);
        gc.setFill(color);
        gc.fillRect(offsetX() + dc * sq, offsetY() + dr * sq, sq, sq);
    }

    private void drawPieces(GraphicsContext gc, GameState state) {
        double sq = getSquareSize();
        gc.setFont(Font.font("Serif", FontWeight.BOLD, sq * 0.72));
        gc.setTextAlign(TextAlignment.CENTER);

        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                Piece piece = state.getPieceAt(r, c);
                if (piece == null) continue;

                String key = piece.getColor().name() + "_" + piece.getType().name();
                String symbol = UNICODE_SYMBOLS.get(key);

                int dr = toDisplayRow(r);
                int dc = toDisplayCol(c);
                double x = offsetX() + dc * sq + sq / 2.0;
                double y = offsetY() + dr * sq + sq * 0.80;

                // Outline tipis supaya bidak putih tetap terbaca di kotak terang
                gc.setStroke(piece.getColor() == PlayerColor.WHITE ? Color.BLACK : Color.web("#444444"));
                gc.setLineWidth(Math.max(1, sq * 0.016));
                gc.strokeText(symbol, x, y);

                gc.setFill(piece.getColor() == PlayerColor.WHITE ? Color.WHITE : Color.BLACK);
                gc.fillText(symbol, x, y);
            }
        }
    }

    private static final Color PREMOVE_HIGHLIGHT = Color.web("#4A90D9", 0.45);
    private static final Color HINT_FROM_HIGHLIGHT = Color.web("#2FA84F", 0.55);
    private static final Color HINT_TO_RING = Color.web("#2FA84F", 0.9);

    /** Highlight SELURUH antrean premove + nomor urut tiap langkah. Panggil SETELAH render(). */
    public void drawPremoveHighlights(List<PremoveQueue.Entry> entries) {
        this.lastPremoveEntries = entries == null ? List.of() : List.copyOf(entries);
        drawPremoveOverlay();
    }

    private void drawPremoveOverlay() {
        if (lastPremoveEntries == null || lastPremoveEntries.isEmpty()) return;
        if (lastState == null) return;
        double sq = getSquareSize();
        GraphicsContext gc = getGraphicsContext2D();
        int n = 1;
        for (PremoveQueue.Entry e : lastPremoveEntries) {
            fillSquare(gc, e.fromRow(), e.fromCol(), PREMOVE_HIGHLIGHT);
            fillSquare(gc, e.toRow(), e.toCol(), PREMOVE_HIGHLIGHT);
            // Nomor urut antrean di tengah kotak tujuan
            int dr = toDisplayRow(e.toRow());
            int dc = toDisplayCol(e.toCol());
            double cx = offsetX() + dc * sq + sq / 2.0;
            double cy = offsetY() + dr * sq + sq / 2.0;
            gc.setFill(Color.web("#1D4ED8"));
            gc.setFont(Font.font("SansSerif", FontWeight.BOLD, sq * 0.30));
            gc.setTextAlign(TextAlignment.CENTER);
            gc.fillText(String.valueOf(n), cx, cy + sq * 0.11);
            n++;
        }
    }

    /**
     * Highlight saran langkah (hint) dari engine: kotak asal hijau penuh +
     * ring tebal di kotak tujuan. Panggil SETELAH render() (dan setelah
     * highlight premove supaya hint selalu terlihat paling atas).
     */
    public void drawHintHighlight(int fromRow, int fromCol, int toRow, int toCol) {
        this.lastHintFromRow = fromRow;
        this.lastHintFromCol = fromCol;
        this.lastHintToRow = toRow;
        this.lastHintToCol = toCol;
        drawHintOverlay();
    }

    /** Hapus hint tersimpan (dipakai saat hint dibatalkan agar tidak muncul lagi setelah resize). */
    public void clearHintHighlight() {
        this.lastHintFromRow = null;
        this.lastHintFromCol = null;
        this.lastHintToRow = null;
        this.lastHintToCol = null;
    }

    private void drawHintOverlay() {
        if (lastHintFromRow == null || lastState == null) return;
        double sq = getSquareSize();
        GraphicsContext gc = getGraphicsContext2D();
        fillSquare(gc, lastHintFromRow, lastHintFromCol, HINT_FROM_HIGHLIGHT);

        int dr = toDisplayRow(lastHintToRow);
        int dc = toDisplayCol(lastHintToCol);
        double centerX = offsetX() + dc * sq + sq / 2.0;
        double centerY = offsetY() + dr * sq + sq / 2.0;
        gc.setStroke(HINT_TO_RING);
        gc.setLineWidth(Math.max(3, sq * 0.065));
        double radius = sq * 0.42;
        gc.strokeOval(centerX - radius, centerY - radius, radius * 2, radius * 2);
    }

    private void drawLegalMoveHints(GraphicsContext gc, GameState state, List<Move> legalMoves) {
        double sq = getSquareSize();
        double ox = offsetX();
        double oy = offsetY();
        for (Move move : legalMoves) {
            int dr = toDisplayRow(move.getToRow());
            int dc = toDisplayCol(move.getToCol());
            double centerX = ox + dc * sq + sq / 2.0;
            double centerY = oy + dr * sq + sq / 2.0;

            boolean isCapture = state.getPieceAt(move.getToRow(), move.getToCol()) != null || move.isEnPassant();
            if (isCapture) {
                gc.setStroke(LEGAL_CAPTURE_RING);
                gc.setLineWidth(Math.max(2, sq * 0.05));
                double radius = sq * 0.42;
                gc.strokeOval(centerX - radius, centerY - radius, radius * 2, radius * 2);
            } else {
                gc.setFill(LEGAL_MOVE_DOT);
                double radius = sq * 0.14;
                gc.fillOval(centerX - radius, centerY - radius, radius * 2, radius * 2);
            }
        }
    }

    // --- Kompatibilitas: kode lama membaca squareSize/boardPixels sebagai field ---
    /** @deprecated pakai {@link #getSquareSize()} yang dinamis mengikuti ukuran window. */
    @Deprecated
    public int getLegacySquareSize() {
        return (int) Math.round(getSquareSize());
    }

    /** Daftar langkah legal terakhir (copy) - untuk kebutuhan debug. */
    public List<Move> getLastLegalMoves() {
        return new ArrayList<>(lastLegalMoves);
    }
}
