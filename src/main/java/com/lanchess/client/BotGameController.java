package com.lanchess.client;

import com.lanchess.bot.BotDifficulty;
import com.lanchess.bot.ChessEngine;
import com.lanchess.bot.FenConverter;
import com.lanchess.bot.StockfishEngine;
import com.lanchess.model.DrawReason;
import com.lanchess.model.GameState;
import com.lanchess.model.GameStateSnapshot;
import com.lanchess.model.GameStatus;
import com.lanchess.model.Move;
import com.lanchess.model.PieceType;
import com.lanchess.model.PlayerColor;
import com.lanchess.model.TimeControl;
import com.lanchess.model.pieces.Piece;
import com.lanchess.server.GameClock;
import com.lanchess.server.MoveValidator;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Mode single-player melawan Stockfish. TIDAK memakai NetworkClient/Socket
 * sama sekali - game berjalan sepenuhnya lokal, memakai MoveValidator
 * langsung sebagai "server" in-process. Setelah giliran pemain, kalau
 * giliran berikutnya adalah bot, controller ini query StockfishEngine di
 * background thread lalu mengeksekusi langkahnya lewat MoveValidator yang
 * SAMA PERSIS dipakai mode LAN.
 *
 * JAM CATUR: dipakai ulang class GameClock (awalnya ditulis untuk server
 * LAN) secara LOKAL di sini - karena mode Bot cuma satu JVM, GameClock bisa
 * langsung jadi otoritatif tanpa perlu split authoritative/display seperti
 * mode LAN. Callback timeout dari GameClock datang dari thread Timer
 * terpisah, jadi tetap dibungkus Platform.runLater().
 */
public class BotGameController {

    private final Stage stage;
    private final ChessEngine engine;
    private final BotDifficulty difficulty;
    private final TimeControl timeControl;
    private final PlayerColor myColor;
    private final PlayerColor botColor;
    private final String enginePath;

    /**
     * SATU-SATUNYA jalur pemakaian engine: semua pemanggilan blocking
     * (getBestMove/evaluate) antre di executor single-thread ini, sehingga
     * TIDAK PERNAH ada dua request UCI bersamaan walau hint/eval/draw/offer
     * dan giliran bot diminta berurutan cepat. Daemon - tidak menahan exit.
     */
    private final ExecutorService engineExec = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "BotEngineWorker");
        thread.setDaemon(true);
        return thread;
    });

    /** True setelah controller ini dibuang (rematch/menu/close) - semua callback telat wajib no-op. */
    private boolean disposed = false;

    private final GameState state = new GameState();
    private GameClock localClock;

    private Integer selectedRow;
    private Integer selectedCol;
    private List<Move> currentLegalMoves = List.of();
    private boolean gameOver = false;
    private boolean botThinking = false;

    // --- Premove: antrean tak terbatas selagi bot berpikir (logika di PremoveQueue) ---
    private final PremoveQueue premoveQueue = new PremoveQueue();

    // --- Hint: saran langkah engine (kotak asal+tujuan, null = tidak ada hint aktif) ---
    private Integer hintFromRow;
    private Integer hintFromCol;
    private Integer hintToRow;
    private Integer hintToCol;
    private boolean hintThinking = false;

    // --- Eval bar: dihitung berkala saat engine menganggur ---
    private boolean evalRunning = false;

    // --- Undo: snapshot SEBELUM tiap langkah dieksekusi (maksimal 200 half-move) ---
    private static final int MAX_UNDO_SNAPSHOTS = 200;
    private final Deque<GameStateSnapshot> undoStack = new ArrayDeque<>();

    private final BoardView boardView = new BoardView();
    private final Label statusLabel = new Label();
    private final Label infoLabel = new Label();
    private final ProgressIndicator thinkingIndicator = new ProgressIndicator();
    private final ClockPanel clockPanel = new ClockPanel();
    private final MoveHistoryPanel historyPanel = new MoveHistoryPanel();
    private final EvalBar evalBar = new EvalBar(BoardView.DEFAULT_SQUARE_SIZE * 8);
    private AnimationTimer clockTicker;

    private Button hintButton;
    private Button undoButton;

    public BotGameController(Stage stage, ChessEngine engine, BotDifficulty difficulty,
                             TimeControl timeControl, PlayerColor myColor, String enginePath) {
        this.stage = stage;
        this.engine = engine;
        this.difficulty = difficulty;
        this.timeControl = timeControl;
        this.myColor = myColor;
        this.botColor = myColor.opposite();
        this.enginePath = enginePath;

        boardView.setFlipped(myColor == PlayerColor.BLACK);
        state.setTimeControl(timeControl);
        state.setStatus(GameStatus.PLAYING);

        if (!timeControl.isUnlimited()) {
            localClock = new GameClock(state, this::handleTimeout);
            localClock.startTurn();
        }

        show();
        startClockTicker();

        // Kalau pemain kebagian BLACK, bot (WHITE) jalan duluan
        if (state.getCurrentTurn() == botColor) {
            requestBotMove();
        }
    }

    private void show() {
        BorderPane root = new BorderPane();
        root.setPadding(new Insets(16));
        root.getStyleClass().add("root");

        infoLabel.getStyleClass().add("title-text");
        infoLabel.setStyle(infoLabel.getStyle() + "-fx-font-size: 16px;");
        infoLabel.setText("Kamu (" + myColor + ") vs Stockfish [" + difficulty + "] (" + botColor + ")");

        statusLabel.getStyleClass().add("status-text");

        thinkingIndicator.setPrefSize(20, 20);
        thinkingIndicator.setVisible(false);

        Button backButton = new Button("Kembali ke Menu");
        backButton.getStyleClass().add("pill-button-secondary");
        backButton.setOnAction(e -> confirmAndReturnToMenu());

        Button resignButton = new Button("Resign");
        resignButton.getStyleClass().add("pill-button-secondary");
        resignButton.setOnAction(e -> onResignClicked());

        Button offerDrawButton = new Button("Tawarkan Seri");
        offerDrawButton.getStyleClass().add("pill-button-secondary");
        offerDrawButton.setOnAction(e -> onOfferDrawClicked());

        hintButton = new Button("💡 Hint");
        hintButton.getStyleClass().add("pill-button-secondary");
        hintButton.setOnAction(e -> onHintClicked());

        undoButton = new Button("↩ Undo");
        undoButton.getStyleClass().add("pill-button-secondary");
        undoButton.setOnAction(e -> onUndoClicked());

        Button muteButton = new Button(SoundManager.isMuted() ? "🔇" : "🔊");
        muteButton.getStyleClass().add("pill-button-secondary");
        muteButton.setOnAction(e -> {
            SoundManager.setMuted(!SoundManager.isMuted());
            muteButton.setText(SoundManager.isMuted() ? "🔇" : "🔊");
        });

        HBox statusRow = new HBox(8, statusLabel, thinkingIndicator);
        statusRow.setAlignment(Pos.CENTER);

        HBox actionRow = new HBox(8, resignButton, offerDrawButton, hintButton, undoButton, muteButton, backButton);
        actionRow.setAlignment(Pos.CENTER);

        VBox topBox = new VBox(6, infoLabel, clockPanel, statusRow, actionRow);
        topBox.setAlignment(Pos.CENTER);
        root.setTop(topBox);
        BorderPane.setAlignment(topBox, Pos.CENTER);

        boardView.setOnMouseClicked(event -> {
            // Klik-kanan = buang seluruh antrean premove.
            if (event.getButton() == MouseButton.SECONDARY) {
                premoveQueue.clear();
                redrawBoard();
                refreshStatus();
                return;
            }
            if (event.getButton() != MouseButton.PRIMARY) return;
            int row = boardView.pixelToRow(event.getY());
            int col = boardView.pixelToCol(event.getX());
            handleSquareClick(row, col);
        });

        historyPanel.refresh(state.getMoveHistory());

        // --- Layout responsif: BoardHolder memaksa papan selalu persegi & muat
        // (aman saat maximize/fullscreen); eval bar mengikuti tinggi papan ---
        BoardHolder boardHolder = new BoardHolder(boardView);
        HBox.setHgrow(boardHolder, Priority.ALWAYS);
        boardView.heightProperty().addListener((o, a, b) -> evalBar.setBarHeight(b.doubleValue()));

        historyPanel.setPrefWidth(240);
        historyPanel.setMinWidth(190);
        historyPanel.setMaxWidth(300);
        VBox.setVgrow(historyPanel, Priority.ALWAYS);

        HBox center = new HBox(16, evalBar, boardHolder, historyPanel);
        center.setAlignment(Pos.CENTER);
        center.setFillHeight(true);
        HBox.setHgrow(boardHolder, Priority.ALWAYS);
        root.setCenter(center);
        BorderPane.setAlignment(center, Pos.CENTER);

        refreshStatus();
        updateActionButtons();
        redrawBoard();

        // Evaluasi posisi awal (engine masih menganggur di sini, kecuali bot jalan duluan).
        requestEvalUpdate();

        UiNav.show(stage, root, "LAN Chess Arena - vs Stockfish", 800, 600, 1080, 720);
        // UiNav mempertahankan ukuran window; BoardHolder mengatur ukuran papan saat layout.
        evalBar.setBarHeight(boardView.getHeight());

        stage.setOnCloseRequest(e -> {
            dispose();
            engine.quit();
        });
    }

    /** Tandai controller dibuang + hentikan ticker/jam/executor (callback telat jadi no-op). */
    private void dispose() {
        disposed = true;
        if (clockTicker != null) clockTicker.stop();
        if (localClock != null) localClock.stop();
        engineExec.shutdownNow();
    }

    private void startClockTicker() {
        clockTicker = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (gameOver) {
                    clockPanel.update(state.getRemainingMillis(PlayerColor.WHITE),
                            state.getRemainingMillis(PlayerColor.BLACK), null, state.getTimeControl().isUnlimited());
                    return;
                }
                long whiteDisplay = state.getRemainingMillis(PlayerColor.WHITE);
                long blackDisplay = state.getRemainingMillis(PlayerColor.BLACK);
                if (localClock != null) {
                    long elapsed = localClock.getElapsedInCurrentTurn();
                    if (state.getCurrentTurn() == PlayerColor.WHITE) whiteDisplay -= elapsed;
                    else blackDisplay -= elapsed;
                }
                clockPanel.update(Math.max(0, whiteDisplay), Math.max(0, blackDisplay),
                        state.getCurrentTurn(), state.getTimeControl().isUnlimited());
            }
        };
        clockTicker.start();
    }

    // =========================================================================
    // Interaksi papan
    // =========================================================================

    private void handleSquareClick(int row, int col) {
        DebugLog.log("BOT-CLICK", "klik (%d,%d) | giliran=%s saya=%s status=%s botThinking=%s selected=%s".formatted(
                row, col, state.getCurrentTurn(), myColor, state.getStatus(), botThinking,
                selectedRow == null ? "-" : "(" + selectedRow + "," + selectedCol + ")"));
        if (gameOver) return;
        if (row < 0 || row >= 8 || col < 0 || col >= 8) return;

        // Klik baru membatalkan hint yang sedang ditampilkan.
        if (hintFromRow != null) {
            clearHint();
        }

        if (state.getCurrentTurn() != myColor || botThinking) {
            DebugLog.log("BOT-CLICK", "-> bukan giliran saya / bot berpikir, masuk premove");
            handlePremoveClick(row, col);
            return;
        }

        // Giliran kita: seleksi pending premove tidak relevan di sini
        // (antrean tetap tersimpan, hanya seleksi pending yang dibersihkan).
        premoveQueue.clearSelection();
        Piece clicked = state.getPieceAt(row, col);

        if (selectedRow == null) {
            DebugLog.log("BOT-CLICK", "-> seleksi: diklik="
                    + (clicked == null ? "kosong" : clicked.getColor() + " " + clicked.getType()));
            trySelect(row, col, clicked);
            DebugLog.log("BOT-CLICK", "-> legal moves dari sini: " + currentLegalMoves.size());
            return;
        }

        if (row == selectedRow && col == selectedCol) {
            clearSelection();
            redrawBoard();
            return;
        }

        Optional<Move> chosen = currentLegalMoves.stream()
                .filter(m -> m.getToRow() == row && m.getToCol() == col)
                .findFirst();

        if (chosen.isEmpty()) {
            DebugLog.log("BOT-CLICK", "-> tujuan TIDAK ada di legal moves, coba seleksi ulang");
            trySelect(row, col, clicked);
            return;
        }

        Move move = chosen.get();
        if (move.isPromotion()) {
            move.setPromotionType(askPromotionChoice());
        }

        DebugLog.log("BOT-CLICK", "-> EKSEKUSI lokal: " + move);
        applyMove(move);
        // Langkah manual membatalkan sisa antrean premove (rencana lama tak berlaku lagi).
        premoveQueue.clear();
        clearSelection();
        clearHint();
        redrawBoard();
        refreshStatus();
        updateActionButtons();
        historyPanel.refresh(state.getMoveHistory());

        if (gameOver) {
            showGameOverDialog();
        } else if (state.getCurrentTurn() == botColor) {
            requestBotMove();
        }
    }

    /**
     * Alur klik selagi giliran bot / bot sedang berpikir - diteruskan ke
     * antrean premove tak terbatas (boleh banyak; klik asal premove terakhir
     * = undo). Seleksi normal tidak disentuh di sini.
     */
    private void handlePremoveClick(int row, int col) {
        if (row < 0 || row >= 8 || col < 0 || col >= 8) return;
        clearSelection();
        PremoveQueue.ClickOutcome outcome =
                premoveQueue.handleClick(state, myColor, row, col, this::askPromotionChoice);
        DebugLog.log("BOT-PREMOVE", "klik (%d,%d) -> %s | antrean=%d".formatted(
                row, col, outcome, premoveQueue.size()));
        redrawBoard();
        refreshStatus();
    }

    /**
     * Jalankan entri terdepan antrean setelah bot selesai jalan (satu entri
     * per giliran; giliran berikutnya memicu pemanggilan berikutnya). Kalau
     * entri tidak legal di posisi nyata (kotak terisi / raja akan skak),
     * seluruh sisa antrean berhenti (dibuang) diam-diam.
     */
    private void trySubmitPremove() {
        if (gameOver || botThinking || state.getCurrentTurn() != myColor) return;
        Optional<Move> next = premoveQueue.pollHead(state, myColor);
        if (next.isEmpty()) {
            redrawBoard();
            refreshStatus();
            return;
        }
        DebugLog.log("BOT-PREMOVE", "eksekusi premove antrean: " + next.get()
                + " | sisa antrean=" + premoveQueue.size());
        applyMove(next.get());
        clearHint();
        redrawBoard();
        refreshStatus();
        updateActionButtons();
        historyPanel.refresh(state.getMoveHistory());
        if (gameOver) {
            showGameOverDialog();
        } else if (state.getCurrentTurn() == botColor) {
            requestBotMove();
        }
    }

    private void trySelect(int row, int col, Piece clicked) {
        if (clicked != null && clicked.getColor() == myColor) {
            selectedRow = row;
            selectedCol = col;
            currentLegalMoves = MoveValidator.getLegalMoves(state, row, col);
        } else {
            clearSelection();
        }
        redrawBoard();
    }

    private void clearSelection() {
        selectedRow = null;
        selectedCol = null;
        currentLegalMoves = List.of();
    }

    private PieceType askPromotionChoice() {
        ChoiceDialog<PieceType> dialog = new ChoiceDialog<>(PieceType.QUEEN,
                PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT);
        dialog.setTitle("Promosi Pion");
        dialog.setHeaderText("Pion mencapai baris terakhir!");
        dialog.setContentText("Promosikan menjadi:");
        return dialog.showAndWait().orElse(PieceType.QUEEN);
    }

    /**
     * Eksekusi langkah yang SUDAH divalidasi (dipanggil untuk langkah pemain
     * maupun bot). Snapshot SEBELUM eksekusi selalu disimpan untuk undo -
     * mencakup papan, giliran, status, riwayat, jam, dan data repetisi.
     */
    private void applyMove(Move validatedMove) {
        undoStack.push(state.createSnapshot());
        while (undoStack.size() > MAX_UNDO_SNAPSHOTS) {
            undoStack.removeFirst();
        }
        // Deteksi capture SEBELUM dieksekusi (kotak tujuan terisi / en passant).
        boolean captured = state.getPieceAt(validatedMove.getToRow(), validatedMove.getToCol()) != null
                || validatedMove.isEnPassant();
        PlayerColor mover = state.getCurrentTurn();
        MoveValidator.executeMove(state, validatedMove);
        if (localClock != null) {
            localClock.onMoveMade(mover);
        }
        GameStatus status = state.getStatus();
        if (status == GameStatus.CHECKMATE || status == GameStatus.STALEMATE || status == GameStatus.DRAW) {
            gameOver = true;
            if (localClock != null) localClock.stop();
            playEndingSound();
        } else if (status == GameStatus.CHECK) {
            SoundManager.playCheck();
        } else if (captured) {
            SoundManager.playCapture();
        } else {
            SoundManager.playMove();
        }
    }

    /** Bunyi akhir game: menang/kalah dari sudut pandang pemain, seri = notifikasi netral. */
    private void playEndingSound() {
        if (state.getLoserColor() == null) {
            SoundManager.playNotify();
        } else if (state.getLoserColor() == myColor) {
            SoundManager.playLose();
        } else {
            SoundManager.playWin();
        }
    }

    /** Callback dari GameClock (thread Timer terpisah) ketika salah satu pemain kehabisan waktu. */
    private void handleTimeout(PlayerColor timedOutColor) {
        Platform.runLater(() -> {
            if (disposed || gameOver) return;
            gameOver = true;
            state.setLoserColor(timedOutColor);
            state.setStatus(GameStatus.TIMEOUT);
            clearHint();
            premoveQueue.clear();
            redrawBoard();
            refreshStatus();
            updateActionButtons();
            playEndingSound();
            showGameOverDialog();
        });
    }

    // =========================================================================
    // Giliran bot (Stockfish)
    // =========================================================================

    private void requestBotMove() {
        botThinking = true;
        setThinkingIndicator(true);
        updateActionButtons();

        // Lewat engineExec (single-thread) supaya tidak pernah balapan dengan hint/eval/draw.
        engineExec.submit(() -> {
            String fen = FenConverter.toFen(state);
            final String uciMove;
            try {
                uciMove = engine.getBestMove(fen, difficulty.getMoveTimeMs());
            } catch (Exception e) {
                Platform.runLater(() -> {
                    if (disposed) return;
                    botThinking = false;
                    setThinkingIndicator(false);
                    updateActionButtons();
                    showAlert(Alert.AlertType.ERROR, "Error Engine", "Gagal mendapat langkah dari Stockfish: " + e.getMessage());
                });
                return;
            }
            Move raw = FenConverter.parseUciMove(uciMove, state);
            Optional<Move> legal = MoveValidator.findLegalMove(state, raw);

            if (legal.isEmpty()) {
                Platform.runLater(() -> {
                    if (disposed) return;
                    botThinking = false;
                    setThinkingIndicator(false);
                    updateActionButtons();
                    showAlert(Alert.AlertType.ERROR, "Error Engine",
                            "Stockfish mengirim langkah yang tidak dikenali validator kita: " + uciMove);
                });
                return;
            }

            Platform.runLater(() -> {
                if (disposed) return;
                applyMove(legal.get());
                botThinking = false;
                setThinkingIndicator(false);
                clearHint();
                premoveQueue.refresh(state, myColor);
                redrawBoard();
                refreshStatus();
                updateActionButtons();
                historyPanel.refresh(state.getMoveHistory());

                if (gameOver) {
                    showGameOverDialog();
                } else {
                    // Premove dulu (langsung jalan lagi = engine sibuk lagi
                    // dan requestEvalUpdate dilewati oleh guard-nya sendiri);
                    // kalau antrean kosong, evaluasi posisi terbaru.
                    trySubmitPremove();
                    if (premoveQueue.isEmpty()) {
                        requestEvalUpdate();
                    }
                }
            });
        });
    }

    private void setThinkingIndicator(boolean thinking) {
        thinkingIndicator.setVisible(thinking);
        statusLabel.setText(thinking ? "Stockfish sedang berpikir..." : describeStatus());
    }

    // =========================================================================
    // Render & status
    // =========================================================================

    private void redrawBoard() {
        Integer checkRow = null;
        Integer checkCol = null;
        if (state.getStatus() == GameStatus.CHECK || state.getStatus() == GameStatus.CHECKMATE) {
            try {
                var king = state.findKing(state.getCurrentTurn());
                checkRow = king.getRow();
                checkCol = king.getCol();
            } catch (IllegalStateException ignored) {
            }
        }
        // Seleksi pending premove (jika ada) ditampilkan sebagai highlight kuning biasa;
        // entri antrean yang terkunci digambar dengan highlight biru + nomor urut.
        Integer hlRow = selectedRow;
        Integer hlCol = selectedCol;
        List<Move> hlHints = currentLegalMoves;
        if (premoveQueue.hasSelection()) {
            hlRow = premoveQueue.getSelRow();
            hlCol = premoveQueue.getSelCol();
            hlHints = premoveQueue.getSelLegal();
        }
        boardView.render(state, hlRow, hlCol, hlHints, checkRow, checkCol);
        boardView.drawPremoveHighlights(premoveQueue.getEntries());
        if (hintFromRow != null) {
            boardView.drawHintHighlight(hintFromRow, hintFromCol, hintToRow, hintToCol);
        }
    }

    /** Sinkronkan enable/disable tombol aksi dengan status game + kesibukan engine. */
    private void updateActionButtons() {
        if (hintButton == null || undoButton == null) return;
        hintButton.setDisable(gameOver || botThinking || hintThinking);
        undoButton.setDisable(botThinking || hintThinking || undoStack.isEmpty());
    }

    private void refreshStatus() {
        statusLabel.setText(describeStatus());
    }

    private String describeStatus() {
        if (gameOver) return describeEnding();
        String turnText = state.getCurrentTurn() == myColor ? "Giliranmu" : "Giliran Stockfish";
        String premoveNote = premoveQueue.isEmpty() ? ""
                : " (" + premoveQueue.size() + " premove diantrikan)";
        return switch (state.getStatus()) {
            case CHECK -> turnText + " - SKAK!" + premoveNote;
            case PLAYING -> turnText + premoveNote;
            default -> state.getStatus().toString();
        };
    }

    private String describeEnding() {
        return switch (state.getStatus()) {
            case CHECKMATE -> {
                if (state.getLoserColor() == null) yield "Skakmat! Permainan berakhir.";
                PlayerColor winner = state.getLoserColor().opposite();
                yield winner == myColor ? "Skakmat! Kamu menang!" : "Skakmat! Stockfish menang.";
            }
            case STALEMATE -> "Stalemate - permainan seri.";
            case TIMEOUT -> {
                PlayerColor loser = state.getLoserColor();
                if (loser == null) yield "Waktu habis! Permainan berakhir.";
                yield loser == myColor ? "Waktu habis! Stockfish menang." : "Waktu habis! Kamu menang!";
            }
            case RESIGNATION -> {
                PlayerColor loser = state.getLoserColor();
                if (loser == null) yield "Permainan selesai (salah satu pemain menyerah).";
                yield loser == myColor ? "Kamu mengundurkan diri. Stockfish menang." : "Stockfish mengundurkan diri. Kamu menang!";
            }
            case DRAW -> {
                if (state.getDrawReason() == null) yield "Seri - permainan berakhir seri.";
                yield switch (state.getDrawReason()) {
                    case THREEFOLD_REPETITION -> "Seri - posisi berulang 3 kali (threefold repetition).";
                    case FIFTY_MOVE_RULE -> "Seri - 50 langkah tanpa capture/pion jalan (50-move rule).";
                    case AGREEMENT -> "Seri - kesepakatan bersama.";
                };
            }
            default -> "Permainan berakhir: " + state.getStatus();
        };
    }

    private void onResignClicked() {
        if (gameOver) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Resign");
        confirm.setHeaderText(null);
        confirm.setContentText("Yakin mau mengundurkan diri?");
        Optional<javafx.scene.control.ButtonType> result = confirm.showAndWait();
        if (result.isEmpty() || result.get() != javafx.scene.control.ButtonType.OK) return;

        gameOver = true;
        state.setLoserColor(myColor);
        state.setStatus(GameStatus.RESIGNATION);
        premoveQueue.clear();
        clearHint();
        if (localClock != null) localClock.stop();
        redrawBoard();
        refreshStatus();
        updateActionButtons();
        playEndingSound();
        showGameOverDialog();
    }

    /**
     * Tawaran seri ke bot. Bot memutuskan berdasarkan evaluasi Stockfish
     * SENDIRI terhadap posisi saat ini (bukan random) - kalau bot merasa
     * jelas unggul (dari sudut pandang bot > +150 centipawn), bot menolak;
     * kalau posisi mendekati seimbang atau bot tertinggal, bot menerima.
     */
    private void onOfferDrawClicked() {
        if (gameOver || botThinking || hintThinking) return;
        statusLabel.setText("Menunggu keputusan Stockfish atas tawaran seri...");

        String fen = FenConverter.toFen(state);
        PlayerColor moverAtRequest = state.getCurrentTurn();
        // Lewat engineExec supaya berurutan dengan request engine lain.
        engineExec.submit(() -> {
            final int evalForMover;
            try {
                evalForMover = engine.evaluateCentipawns(fen, 500);
            } catch (Exception e) {
                Platform.runLater(() -> {
                    if (disposed) return;
                    refreshStatus();
                    showAlert(Alert.AlertType.ERROR, "Error", "Gagal mengevaluasi posisi: " + e.getMessage());
                });
                return;
            }
            // UCI score selalu dari sudut pandang sisi yang lagi jalan di FEN -
            // kalau yang jalan saat request BUKAN bot, harus dibalik dulu.
            int evalForBot = (moverAtRequest == botColor) ? evalForMover : -evalForMover;
            boolean botAccepts = evalForBot < 150;

            Platform.runLater(() -> {
                if (disposed || gameOver) return;
                if (botAccepts) {
                    gameOver = true;
                    state.setStatus(GameStatus.DRAW);
                    state.setDrawReason(DrawReason.AGREEMENT);
                    if (localClock != null) localClock.stop();
                    clearHint();
                    premoveQueue.clear();
                    redrawBoard();
                    refreshStatus();
                    updateActionButtons();
                    playEndingSound();
                    showGameOverDialog();
                } else {
                    refreshStatus();
                    showAlert(Alert.AlertType.INFORMATION, "Tawaran Seri",
                            "Stockfish menolak tawaran seri - merasa posisinya lebih unggul.");
                }
            });
        });
    }

    // =========================================================================
    // Hint (saran langkah dari engine)
    // =========================================================================

    private void onHintClicked() {
        if (gameOver || botThinking || hintThinking) return;
        if (state.getCurrentTurn() != myColor) return;
        hintThinking = true;
        updateActionButtons();
        statusLabel.setText("Meminta saran Stockfish...");

        String fen = FenConverter.toFen(state);
        // Lewat engineExec supaya berurutan; parse + validasi di FX thread
        // supaya memakai posisi TERKINI (bukan snapshot saat request).
        engineExec.submit(() -> {
            final String uciMove;
            try {
                uciMove = engine.getBestMove(fen, 700);
            } catch (Exception e) {
                Platform.runLater(() -> {
                    if (disposed) return;
                    hintThinking = false;
                    updateActionButtons();
                    refreshStatus();
                    showAlert(Alert.AlertType.ERROR, "Error Engine", "Gagal meminta saran: " + e.getMessage());
                });
                return;
            }
            Platform.runLater(() -> {
                if (disposed) return;
                hintThinking = false;
                updateActionButtons();
                if (gameOver || state.getCurrentTurn() != myColor) {
                    refreshStatus();
                    return;
                }
                Move raw;
                try {
                    raw = FenConverter.parseUciMove(uciMove, state);
                } catch (Exception e) {
                    refreshStatus();
                    showAlert(Alert.AlertType.INFORMATION, "Hint",
                            "Saran kedaluwarsa (posisi berubah), coba lagi.");
                    return;
                }
                Optional<Move> legal = MoveValidator.findLegalMove(state, raw);
                if (legal.isEmpty()) {
                    refreshStatus();
                    showAlert(Alert.AlertType.INFORMATION, "Hint",
                            "Saran kedaluwarsa (posisi berubah), coba lagi.");
                    return;
                }
                Move hint = legal.get();
                hintFromRow = hint.getFromRow();
                hintFromCol = hint.getFromCol();
                hintToRow = hint.getToRow();
                hintToCol = hint.getToCol();
                redrawBoard();
                statusLabel.setText("💡 Saran: " + squareName(hintFromRow, hintFromCol)
                        + " → " + squareName(hintToRow, hintToCol));
            });
        });
    }

    private void clearHint() {
        hintFromRow = null;
        hintFromCol = null;
        hintToRow = null;
        hintToCol = null;
        boardView.clearHintHighlight();
    }

    /** Nama kotak aljabar, mis. (6,4) -> "e2". */
    private static String squareName(int row, int col) {
        return "" + (char) ('a' + col) + (8 - row);
    }

    // =========================================================================
    // Eval bar (evaluasi posisi berkala saat engine menganggur)
    // =========================================================================

    /**
     * Minta evaluasi posisi SAAT INI untuk eval bar. Dilewati kalau game
     * over / engine sedang dipakai (bot berpikir, hint, atau eval lain
     * jalan) - pemanggil berikutnya (setelah bot jalan / undo) akan
     * meminta ulang dengan posisi yang lebih baru.
     */
    private void requestEvalUpdate() {
        if (disposed || gameOver || botThinking || hintThinking || evalRunning) return;
        evalRunning = true;
        String fen = FenConverter.toFen(state);
        PlayerColor moverAtRequest = state.getCurrentTurn();
        engineExec.submit(() -> {
            final int evalForMover;
            try {
                evalForMover = engine.evaluateCentipawns(fen, 400);
            } catch (Exception ignored) {
                Platform.runLater(() -> evalRunning = false);
                return;
            }
            int evalWhite = (moverAtRequest == PlayerColor.WHITE) ? evalForMover : -evalForMover;
            Platform.runLater(() -> {
                evalRunning = false;
                if (disposed || gameOver) return;
                double prob = EvalBar.winProbability(evalWhite);
                evalBar.update(prob, EvalBar.formatScore(evalWhite)
                        + " · " + EvalBar.formatWinChance(prob));
            });
        });
    }

    // =========================================================================
    // Undo/takeback (batalkan 1 langkah penuh: balasan bot + langkah pemain)
    // =========================================================================

    private void onUndoClicked() {
        if (botThinking || hintThinking || undoStack.isEmpty()) return;

        // Pop sampai giliran kembali ke pemain (normalnya 2 pop: balasan bot
        // lalu langkah pemain; 1 pop kalau game over tepat setelah langkah
        // pemain, mis. skakmat oleh pemain).
        int pops = 0;
        while (!undoStack.isEmpty() && pops < 2) {
            state.restoreSnapshot(undoStack.pop());
            pops++;
            if (state.getCurrentTurn() == myColor) break;
        }

        gameOver = false;
        state.setLoserColor(null);
        state.setDrawReason(null);
        clearHint();
        clearSelection();
        premoveQueue.clear();

        // Jam dimulai ulang dari sisa waktu hasil restore.
        if (localClock != null) localClock.stop();
        localClock = state.getTimeControl().isUnlimited()
                ? null
                : new GameClock(state, this::handleTimeout);
        if (localClock != null) localClock.startTurn();

        historyPanel.refresh(state.getMoveHistory());
        redrawBoard();
        refreshStatus();
        updateActionButtons();

        if (state.getCurrentTurn() == botColor) {
            // Kasus langka: history cuma 1 langkah (bot jalan duluan sebagai
            // putih) - bot jalan ulang dari posisi awal.
            requestBotMove();
        } else {
            requestEvalUpdate();
        }
    }

    // =========================================================================
    // Rematch (permainan baru, engine baru, pengaturan sama)
    // =========================================================================

    /** Dialog game-over dengan opsi main lagi. Pengganti showGameOverAlert(). */
    private void showGameOverDialog() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Permainan Selesai");
        alert.setHeaderText(null);
        alert.setContentText(describeEnding() + "\n\nMain lagi dengan pengaturan yang sama?");
        ButtonType rematchBtn = new ButtonType("Main Lagi", ButtonType.OK.getButtonData());
        ButtonType closeBtn = new ButtonType("Tutup", ButtonType.CANCEL.getButtonData());
        alert.getButtonTypes().setAll(rematchBtn, closeBtn);

        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent() && result.get() == rematchBtn) {
            startRematch();
        }
    }

    /**
     * Buang controller+engine lama, jalankan engine fresh (pola sama seperti
     * BotSetupController.startBotGame), lalu buka permainan baru dengan
     * difficulty/timer/warna yang sama. Engine fresh = tidak ada sisa
     * request UCI lama yang bisa mengacaukan game baru.
     */
    private void startRematch() {
        dispose();
        statusLabel.setText("Menyiapkan permainan baru...");
        Thread startThread = new Thread(() -> {
            ChessEngine freshEngine = new StockfishEngine();
            try {
                freshEngine.start(enginePath);
                freshEngine.setElo(difficulty.getEloRating());
                freshEngine.newGame();
                Platform.runLater(() -> new BotGameController(
                        stage, freshEngine, difficulty, timeControl, myColor, enginePath));
            } catch (Exception e) {
                freshEngine.quit();
                Platform.runLater(() -> {
                    showAlert(Alert.AlertType.ERROR, "Gagal Rematch",
                            "Tidak bisa menjalankan ulang engine: " + e.getMessage());
                    new MainMenuController(stage).show();
                });
            }
        }, "StockfishRematch");
        startThread.setDaemon(true);
        startThread.start();
    }

    private void showAlert(Alert.AlertType type, String title, String content) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }

    private void confirmAndReturnToMenu() {
        // Game sudah selesai -> kembali biasa tanpa dihitung resign.
        if (gameOver) {
            dispose();
            engine.quit();
            new MainMenuController(stage).show();
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Kembali ke Menu Utama");
        confirm.setHeaderText(null);
        confirm.setContentText("Permainan masih berlangsung. Kembali ke menu utama "
                + "akan dihitung sebagai resign (kalah). Lanjutkan?");
        Optional<javafx.scene.control.ButtonType> result = confirm.showAndWait();
        if (result.isPresent() && result.get() == javafx.scene.control.ButtonType.OK) {
            // Yang menekan dihitung resign: catat kekalahan sebelum keluar.
            gameOver = true;
            state.setLoserColor(myColor);
            state.setStatus(GameStatus.RESIGNATION);
            dispose();
            engine.quit();
            new MainMenuController(stage).show();
        }
    }
}
