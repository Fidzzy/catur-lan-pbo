package com.lanchess.client;

import com.lanchess.model.GameState;
import com.lanchess.model.Move;
import com.lanchess.model.PieceType;
import com.lanchess.model.PlayerColor;
import com.lanchess.model.pieces.Bishop;
import com.lanchess.model.pieces.Knight;
import com.lanchess.model.pieces.Piece;
import com.lanchess.model.pieces.Queen;
import com.lanchess.model.pieces.Rook;
import com.lanchess.server.MoveValidator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Antrean premove TAK TERBATAS yang dipakai bersama oleh GameController
 * (mode LAN) dan BotGameController (mode vs Bot).
 *
 * Cara pakai: saat BUKAN giliran pemain, setiap klik papan diteruskan ke
 * {@link #handleClick} - klik yang membentuk langkah valid (di atas posisi
 * proyeksi) dikunci menjadi satu entri antrean. Begitu giliran tiba,
 * controller memanggil {@link #pollHead} SATU KALI per giliran: entri
 * terdepan divalidasi ulang terhadap posisi NYATA - kalau legal dijalankan/
 * dikirim, kalau tidak SELURUH sisa antrean dibuang (berhenti).
 *
 * Aturan berhenti (sesuai permintaan):
 *  - Kotak asal premove sudah tidak berisi bidak sendiri (kosong / terisi
 *    bidak lawan karena tertangkap), ATAU kotak tujuan terisi bidak sendiri
 *    (terhalang) - terdeteksi karena tidak ada langkah legal yang cocok.
 *  - Langkah akan membuat raja sendiri skak - terdeteksi karena
 *    MoveValidator.getLegalMoves() sudah memfilter semua langkah yang
 *    meninggalkan raja dalam skak (termasuk posisi sedang skak yang tidak
 *    terselesaikan oleh premove ini).
 *  Kedua kasus di atas membuang seluruh sisa antrean, bukan cuma entri itu.
 *
 * Undo sesuai urutan (sebelum lawan bergerak):
 *  - Klik kotak ASAL premove TERAKHIR membatalkan (pop) premove itu.
 *  - Merangkai langkah yang persis KEBALIKAN premove terakhir (pilih bidak
 *    di kotak tujuan premove terakhir lalu klik kotak asalnya) juga pop.
 *  - Klik-kanan membuang SELURUH antrean (ditangani controller).
 *
 * CATATAN: validasi di sini HANYA preview/UX. Eksekusi final tetap lewat
 * MoveValidator terhadap state nyata (server di mode LAN, applyMove lokal
 * di mode Bot) - server tidak pernah memercayai antrean ini.
 */
public class PremoveQueue {

    /** Satu langkah yang diantrikan. Flag castling/enPassant disalin dari Move legal saat dikunci. */
    public record Entry(int fromRow, int fromCol, int toRow, int toCol,
                        PieceType promotionType, PieceType pieceType,
                        boolean castling, boolean enPassant) {
    }

    public enum ClickOutcome {
        SELECTED, SELECTION_CHANGED, DESELECTED, QUEUED, UNDONE_LAST, IGNORED
    }

    private final List<Entry> entries = new ArrayList<>();

    /**
     * Papan PROYEKSI = posisi nyata + semua entri antrean diterapkan berurutan.
     * Dipakai agar premove ke-2, ke-3, ... bisa dipilih di atas hasil premove
     * sebelumnya (bidak sudah "pindah" di tampilan proyeksi). Direfresh dari
     * posisi nyata setiap ada klik / state baru.
     */
    private Piece[][] simBoard;

    // Seleksi pending (bidak yang diklik tapi tujuannya belum dikunci)
    private Integer selRow;
    private Integer selCol;
    private List<Move> selLegal = List.of();

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public int size() {
        return entries.size();
    }

    public List<Entry> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    public boolean hasSelection() {
        return selRow != null;
    }

    public Integer getSelRow() {
        return selRow;
    }

    public Integer getSelCol() {
        return selCol;
    }

    public List<Move> getSelLegal() {
        return selLegal;
    }

    /** Buang seluruh antrean + seleksi pending (klik-kanan / game over / langkah manual). */
    public void clear() {
        entries.clear();
        clearSelection();
    }

    /** Buang seleksi pending saja, antrean tetap. */
    public void clearSelection() {
        selRow = null;
        selCol = null;
        selLegal = List.of();
    }

    /** Sinkronkan papan proyeksi ke posisi nyata terbaru (panggil saat STATE_UPDATE / setelah bot jalan). */
    public void refresh(GameState realState, PlayerColor me) {
        syncToPosition(realState, me);
    }

    /**
     * Tangani satu klik saat bukan giliran pemain.
     *
     * @param promotionChooser dipanggil (dialog) hanya jika premove yang dikunci adalah promosi pion.
     */
    public ClickOutcome handleClick(GameState realState, PlayerColor me,
                                    int row, int col, Supplier<PieceType> promotionChooser) {
        if (row < 0 || row >= 8 || col < 0 || col >= 8) return ClickOutcome.IGNORED;
        syncToPosition(realState, me);

        // --- Belum ada seleksi: klik harus ke bidak sendiri (di atas proyeksi), atau UNDO premove terakhir ---
        if (selRow == null) {
            if (!entries.isEmpty()) {
                Entry last = entries.get(entries.size() - 1);
                if (last.fromRow() == row && last.fromCol() == col) {
                    entries.remove(entries.size() - 1);
                    clearSelection();
                    syncToPosition(realState, me);
                    return ClickOutcome.UNDONE_LAST;
                }
            }
            Piece clicked = simBoard[row][col];
            if (clicked != null && clicked.getColor() == me) {
                selRow = row;
                selCol = col;
                selLegal = legalOnSim(realState, me, row, col);
                return ClickOutcome.SELECTED;
            }
            return ClickOutcome.IGNORED;
        }

        // --- Sudah ada seleksi, klik ini adalah kandidat tujuan ---
        if (row == selRow && col == selCol) {
            clearSelection();
            return ClickOutcome.DESELECTED;
        }

        // UNDO sesuai urutan: mengembalikan bidak persis ke kotak asalnya
        // (pilih bidak di tujuan premove terakhir -> klik asal premove terakhir).
        // Dicek SEBELUM legalitas karena langkah mundur (mis. pion e4->e2)
        // memang tidak pernah legal - justru itu sinyal pembatalan.
        if (!entries.isEmpty()) {
            Entry last = entries.get(entries.size() - 1);
            if (last.toRow() == selRow && last.toCol() == selCol
                    && last.fromRow() == row && last.fromCol() == col) {
                entries.remove(entries.size() - 1);
                clearSelection();
                syncToPosition(realState, me);
                return ClickOutcome.UNDONE_LAST;
            }
        }

        Optional<Move> target = selLegal.stream()
                .filter(m -> m.getToRow() == row && m.getToCol() == col)
                .findFirst();

        if (target.isPresent()) {
            Move mv = target.get();
            PieceType promo = null;
            if (mv.isPromotion()) {
                promo = promotionChooser.get();
            }
            entries.add(new Entry(selRow, selCol, row, col, promo,
                    mv.getPieceType(), mv.isCastling(), mv.isEnPassant()));
            clearSelection();
            syncToPosition(realState, me);
            return ClickOutcome.QUEUED;
        }

        // Bukan tujuan legal: klik bidak sendiri lain = pindah seleksi, klik kosong = batalkan seleksi.
        Piece clicked = simBoard[row][col];
        if (clicked != null && clicked.getColor() == me) {
            selRow = row;
            selCol = col;
            selLegal = legalOnSim(realState, me, row, col);
            return ClickOutcome.SELECTION_CHANGED;
        }
        clearSelection();
        return ClickOutcome.DESELECTED;
    }

    /**
     * Ambil entri terdepan untuk dijalankan SEKARANG (dipanggil tepat sekali
     * setiap kali giliran tiba). Entri divalidasi ulang terhadap posisi NYATA:
     *  - legal -> entri dibuang dari antrean (pop) dan Move enriched dikembalikan.
     *  - ilegal (kotak asal tak berisi bidak sendiri / tujuan terisi kawan /
     *    raja akan skak) -> SELURUH sisa antrean dibuang (berhenti), empty.
     * Kalau belum giliran pemain, antrean dipertahankan dan empty dikembalikan.
     */
    public Optional<Move> pollHead(GameState realState, PlayerColor me) {
        if (entries.isEmpty()) return Optional.empty();
        if (realState.getCurrentTurn() != me) return Optional.empty();

        Entry head = entries.get(0);

        // Kotak asal HARUS berisi bidak sendiri - mencakup "kotak terisi"
        // (kosong atau sudah ditempati bidak lawan karena tertangkap).
        Piece from = realState.getPieceAt(head.fromRow(), head.fromCol());
        if (from == null || from.getColor() != me) {
            clear();
            return Optional.empty();
        }

        // getLegalMoves() sudah mengecualikan tujuan yang terisi bidak sendiri
        // ("terhalang") dan semua langkah yang membuat raja sendiri skak.
        List<Move> legal = MoveValidator.getLegalMoves(realState, head.fromRow(), head.fromCol());
        Optional<Move> match = legal.stream()
                .filter(m -> m.getToRow() == head.toRow() && m.getToCol() == head.toCol())
                .findFirst();

        if (match.isEmpty()) {
            clear();
            return Optional.empty();
        }

        Move move = match.get();
        if (move.isPromotion()) {
            move.setPromotionType(head.promotionType() != null ? head.promotionType() : PieceType.QUEEN);
        }
        entries.remove(0);
        clearSelection();
        syncToPosition(realState, me);
        return Optional.of(move);
    }

    // =========================================================================
    // Internal: papan proyeksi
    // =========================================================================

    private void syncToPosition(GameState realState, PlayerColor me) {
        Piece[][] base = realState.getBoard();
        Piece[][] sim = new Piece[8][8];
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                sim[r][c] = base[r][c] != null ? base[r][c].copy() : null;
            }
        }
        // Terapkan antrean untuk preview; berhenti di entri pertama yang
        // sudah tidak bisa diterapkan (lawan mengubah posisi) - entri tetap
        // disimpan, keputusan buang diambil saat pollHead().
        for (Entry e : entries) {
            Piece p = sim[e.fromRow()][e.fromCol()];
            if (p == null || p.getColor() != me) break;
            applyEntryToBoard(sim, e, me);
        }
        simBoard = sim;

        // Seleksi pending ikut divalidasi ulang di atas proyeksi terbaru.
        if (selRow != null) {
            Piece p = sim[selRow][selCol];
            if (p != null && p.getColor() == me) {
                selLegal = legalOnSim(realState, me, selRow, selCol);
            } else {
                clearSelection();
            }
        }
    }

    /** Langkah legal di atas papan proyeksi (preview saja, eksekusi tetap validasi ulang). */
    private List<Move> legalOnSim(GameState realState, PlayerColor me, int row, int col) {
        if (simBoard == null) return List.of();
        Piece p = simBoard[row][col];
        if (p == null || p.getColor() != me) return List.of();
        GameState tmp = new GameState();
        tmp.setBoard(simBoard);
        tmp.setCurrentTurn(me);
        // Wariskan langkah terakhir agar kandidat en passant bisa muncul di preview.
        if (realState.getLastMove() != null) {
            tmp.addMove(realState.getLastMove());
        }
        return MoveValidator.getLegalMoves(tmp, row, col);
    }

    /** Terapkan satu entri ke papan (simulasi proyeksi, bukan eksekusi resmi). */
    private static void applyEntryToBoard(Piece[][] board, Entry e, PlayerColor mover) {
        Piece piece = board[e.fromRow()][e.fromCol()];
        if (piece == null) return;

        if (e.enPassant()) {
            board[e.fromRow()][e.toCol()] = null;
        }
        if (e.castling()) {
            int r = e.fromRow();
            if (e.toCol() == 6) {
                Piece rook = board[r][7];
                board[r][5] = rook;
                board[r][7] = null;
                if (rook != null) rook.moveTo(r, 5);
            } else {
                Piece rook = board[r][0];
                board[r][3] = rook;
                board[r][0] = null;
                if (rook != null) rook.moveTo(r, 3);
            }
        }

        board[e.toRow()][e.toCol()] = piece;
        board[e.fromRow()][e.fromCol()] = null;
        piece.moveTo(e.toRow(), e.toCol());

        if (e.promotionType() != null) {
            Piece promoted = switch (e.promotionType()) {
                case QUEEN -> new Queen(mover, e.toRow(), e.toCol());
                case ROOK -> new Rook(mover, e.toRow(), e.toCol());
                case BISHOP -> new Bishop(mover, e.toRow(), e.toCol());
                case KNIGHT -> new Knight(mover, e.toRow(), e.toCol());
                default -> null;
            };
            if (promoted != null) {
                promoted.setHasMoved(true);
                board[e.toRow()][e.toCol()] = promoted;
            }
        }
    }
}
