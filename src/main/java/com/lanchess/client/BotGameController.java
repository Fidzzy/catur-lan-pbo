package com.lanchess.client;

import com.lanchess.bot.BotDifficulty;
import com.lanchess.bot.FenConverter;
import com.lanchess.bot.StockfishEngine;
import com.lanchess.model.GameState;
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
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.List;
import java.util.Optional;

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
    private final StockfishEngine engine;
    private final BotDifficulty difficulty;
    private final PlayerColor myColor;
    private final PlayerColor botColor;

    private final GameState state = new GameState();
    private GameClock localClock;

    private Integer selectedRow;
    private Integer selectedCol;
    private List<Move> currentLegalMoves = List.of();
    private boolean gameOver = false;
    private boolean botThinking = false;

    private final BoardView boardView = new BoardView();
    private final Label statusLabel = new Label();
    private final Label infoLabel = new Label();
    private final ProgressIndicator thinkingIndicator = new ProgressIndicator();
    private final ClockPanel clockPanel = new ClockPanel();
    private final MoveHistoryPanel historyPanel = new MoveHistoryPanel();
    private AnimationTimer clockTicker;

    public BotGameController(Stage stage, StockfishEngine engine, BotDifficulty difficulty,
                              TimeControl timeControl, PlayerColor myColor) {
        this.stage = stage;
        this.engine = engine;
        this.difficulty = difficulty;
        this.myColor = myColor;
        this.botColor = myColor.opposite();

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

        HBox statusRow = new HBox(8, statusLabel, thinkingIndicator);
        statusRow.setAlignment(Pos.CENTER);

        VBox topBox = new VBox(6, infoLabel, clockPanel, statusRow, backButton);
        topBox.setAlignment(Pos.CENTER);
        root.setTop(topBox);
        BorderPane.setAlignment(topBox, Pos.CENTER);

        boardView.setOnMouseClicked(event -> {
            if (event.getButton() != MouseButton.PRIMARY) return;
            int row = boardView.pixelToRow(event.getY());
            int col = boardView.pixelToCol(event.getX());
            handleSquareClick(row, col);
        });

        historyPanel.refresh(state.getMoveHistory());

        HBox center = new HBox(20, boardView, historyPanel);
        center.setAlignment(Pos.CENTER);
        root.setCenter(center);

        refreshStatus();
        redrawBoard();

        Scene scene = new Scene(root);
        Theme.apply(scene);
        stage.setScene(scene);
        stage.setTitle("LAN Chess Arena - vs Stockfish");
        stage.setResizable(false);
        stage.show();

        stage.setOnCloseRequest(e -> {
            if (clockTicker != null) clockTicker.stop();
            engine.quit();
        });
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
        if (gameOver || botThinking) return;
        if (state.getCurrentTurn() != myColor) return;

        Piece clicked = state.getPieceAt(row, col);

        if (selectedRow == null) {
            trySelect(row, col, clicked);
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
            trySelect(row, col, clicked);
            return;
        }

        Move move = chosen.get();
        if (move.isPromotion()) {
            move.setPromotionType(askPromotionChoice());
        }

        applyMove(move);
        clearSelection();
        redrawBoard();
        refreshStatus();
        historyPanel.refresh(state.getMoveHistory());

        if (!gameOver && state.getCurrentTurn() == botColor) {
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

    /** Eksekusi langkah yang SUDAH divalidasi (dipanggil untuk langkah pemain maupun bot). */
    private void applyMove(Move validatedMove) {
        PlayerColor mover = state.getCurrentTurn();
        MoveValidator.executeMove(state, validatedMove);
        if (localClock != null) {
            localClock.onMoveMade(mover);
        }
        if (state.getStatus() == GameStatus.CHECKMATE || state.getStatus() == GameStatus.STALEMATE) {
            gameOver = true;
            if (localClock != null) localClock.stop();
        }
    }

    /** Callback dari GameClock (thread Timer terpisah) ketika salah satu pemain kehabisan waktu. */
    private void handleTimeout(PlayerColor timedOutColor) {
        Platform.runLater(() -> {
            if (gameOver) return;
            gameOver = true;
            state.setStatus(GameStatus.TIMEOUT);
            redrawBoard();
            refreshStatus();
            showGameOverAlert();
        });
    }

    // =========================================================================
    // Giliran bot (Stockfish)
    // =========================================================================

    private void requestBotMove() {
        botThinking = true;
        setThinkingIndicator(true);

        Thread engineThread = new Thread(() -> {
            try {
                String fen = FenConverter.toFen(state);
                String uciMove = engine.getBestMove(fen, difficulty.getMoveTimeMs());
                Move raw = FenConverter.parseUciMove(uciMove, state);
                Optional<Move> legal = MoveValidator.findLegalMove(state, raw);

                if (legal.isEmpty()) {
                    Platform.runLater(() -> {
                        botThinking = false;
                        setThinkingIndicator(false);
                        showAlert(Alert.AlertType.ERROR, "Error Engine",
                                "Stockfish mengirim langkah yang tidak dikenali validator kita: " + uciMove);
                    });
                    return;
                }

                Platform.runLater(() -> {
                    applyMove(legal.get());
                    botThinking = false;
                    setThinkingIndicator(false);
                    redrawBoard();
                    refreshStatus();
                    historyPanel.refresh(state.getMoveHistory());

                    if (gameOver) {
                        showGameOverAlert();
                    }
                });

            } catch (Exception e) {
                Platform.runLater(() -> {
                    botThinking = false;
                    setThinkingIndicator(false);
                    showAlert(Alert.AlertType.ERROR, "Error Engine", "Gagal mendapat langkah dari Stockfish: " + e.getMessage());
                });
            }
        }, "StockfishThinking");
        engineThread.setDaemon(true);
        engineThread.start();
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
            var king = state.findKing(state.getCurrentTurn());
            checkRow = king.getRow();
            checkCol = king.getCol();
        }
        boardView.render(state, selectedRow, selectedCol, currentLegalMoves, checkRow, checkCol);
    }

    private void refreshStatus() {
        statusLabel.setText(describeStatus());
    }

    private String describeStatus() {
        if (gameOver) return describeEnding();
        String turnText = state.getCurrentTurn() == myColor ? "Giliranmu" : "Giliran Stockfish";
        return switch (state.getStatus()) {
            case CHECK -> turnText + " - SKAK!";
            case PLAYING -> turnText;
            default -> state.getStatus().toString();
        };
    }

    private String describeEnding() {
        return switch (state.getStatus()) {
            case CHECKMATE -> {
                PlayerColor winner = state.getCurrentTurn().opposite();
                yield winner == myColor ? "Skakmat! Kamu menang!" : "Skakmat! Stockfish menang.";
            }
            case STALEMATE -> "Stalemate - permainan seri.";
            case TIMEOUT -> {
                PlayerColor loser = state.getCurrentTurn();
                yield loser == myColor ? "Waktu habis! Stockfish menang." : "Waktu habis! Kamu menang!";
            }
            default -> "Permainan berakhir: " + state.getStatus();
        };
    }

    private void showGameOverAlert() {
        showAlert(Alert.AlertType.INFORMATION, "Permainan Selesai", describeEnding());
    }

    private void showAlert(Alert.AlertType type, String title, String content) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }

    private void confirmAndReturnToMenu() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Kembali ke Menu");
        confirm.setHeaderText(null);
        confirm.setContentText("Permainan yang sedang berjalan akan dihentikan. Lanjutkan?");
        Optional<javafx.scene.control.ButtonType> result = confirm.showAndWait();
        if (result.isPresent() && result.get() == javafx.scene.control.ButtonType.OK) {
            if (clockTicker != null) clockTicker.stop();
            if (localClock != null) localClock.stop();
            engine.quit();
            new MainMenuController(stage).show();
        }
    }
}
