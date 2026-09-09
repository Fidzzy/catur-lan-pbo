package com.lanchess.client;

import com.lanchess.model.DrawReason;
import com.lanchess.model.GameState;
import com.lanchess.model.GameStatus;
import com.lanchess.model.Message;
import com.lanchess.model.MessageType;
import com.lanchess.model.Move;
import com.lanchess.model.PieceType;
import com.lanchess.model.PlayerColor;
import com.lanchess.model.pieces.Piece;
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
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.util.List;
import java.util.Optional;

/**
 * Menghubungkan interaksi klik pada BoardView dengan NetworkClient, dan
 * menangani semua Message yang datang dari server saat fase GAMEPLAY.
 *
 * CATATAN PENTING: NetworkClient.listenLoop() berjalan di thread terpisah
 * (bukan JavaFX Application Thread). Karena itu SETIAP callback onMessage()
 * di sini yang menyentuh node JavaFX (label, canvas, alert, dsb.) WAJIB
 * dibungkus Platform.runLater(), kalau tidak JavaFX akan melempar
 * IllegalStateException "Not on FX application thread".
 *
 * Client TIDAK menentukan legal/tidaknya sebuah langkah secara otoritatif -
 * MoveValidator dipakai di sini HANYA untuk PREVIEW/HIGHLIGHT (UX semata).
 * Setiap langkah yang dikirim tetap divalidasi ulang secara independen oleh
 * server (lihat ClientHandler.handleMove); kalau ternyata server menolaknya
 * (mis. state sempat berubah), client akan menerima MOVE_REJECTED.
 *
 * JAM CATUR: server adalah satu-satunya sumber kebenaran (lihat GameClock
 * di server package) - client di sini HANYA menampilkan interpolasi visual
 * antar STATE_UPDATE (supaya countdown terlihat mulus tanpa perlu broadcast
 * tiap detik). Baseline interpolasi (lastStateReceivedAtMillis) di-reset
 * setiap kali STATE_UPDATE baru diterima.
 *
 * PREMOVE: kalau pemain klik papan SAAT BUKAN gilirannya, langkah itu
 * diantrikan (bukan langsung ditolak/diabaikan). Begitu STATE_UPDATE
 * berikutnya menunjukkan giliran sudah berpindah ke pemain ini, premove
 * dicoba dikirim otomatis - kalau ternyata sudah tidak legal lagi (posisi
 * berubah gara-gara langkah lawan), premove dibuang diam-diam tanpa error.
 * Ini murni fitur UX client-side; server tetap validasi penuh seperti biasa.
 */
public class GameController {

    private final Stage stage;
    private final NetworkClient client;
    private final PlayerColor myColor;

    private GameState state;
    private Integer selectedRow;
    private Integer selectedCol;
    private List<Move> currentLegalMoves = List.of();
    private boolean gameOver = false;
    private long lastStateReceivedAtMillis = System.currentTimeMillis();

    // --- Premove ---
    private Integer premoveFromRow;
    private Integer premoveFromCol;
    private Integer premoveToRow;
    private Integer premoveToCol;
    private PieceType premovePromotionType;

    private final BoardView boardView = new BoardView();
    private final Label statusLabel = new Label();
    private final Label colorLabel = new Label();
    private final TextArea chatArea = new TextArea();
    private final TextField chatInput = new TextField();
    private final ClockPanel clockPanel = new ClockPanel();
    private final MoveHistoryPanel historyPanel = new MoveHistoryPanel();
    private AnimationTimer clockTicker;

    public GameController(Stage stage, NetworkClient client, PlayerColor myColor, GameState initialState) {
        this.stage = stage;
        this.client = client;
        this.myColor = myColor;
        this.state = initialState;

        boardView.setFlipped(myColor == PlayerColor.BLACK);

        // Ambil alih routing pesan dari NetworkClient (sebelumnya di-handle FriendModeController/HostSetupController)
        client.setOnMessageReceived(this::onMessageReceived);
        client.setOnDisconnected(() -> Platform.runLater(() ->
                showAlertAndReturnToMenu("Koneksi terputus", "Koneksi ke server terputus.")));

        show();
        startClockTicker();
    }

    private void show() {
        BorderPane root = new BorderPane();
        root.setPadding(new Insets(16));
        root.getStyleClass().add("root");

        colorLabel.getStyleClass().add("title-text");
        colorLabel.setStyle(colorLabel.getStyle() + "-fx-font-size: 15px;");
        statusLabel.getStyleClass().add("status-text");

        Button resignButton = new Button("Resign");
        resignButton.getStyleClass().add("pill-button-secondary");
        resignButton.setOnAction(e -> onResignClicked());

        Button offerDrawButton = new Button("Tawarkan Seri");
        offerDrawButton.getStyleClass().add("pill-button-secondary");
        offerDrawButton.setOnAction(e -> onOfferDrawClicked());

        HBox actionRow = new HBox(8, resignButton, offerDrawButton);
        actionRow.setAlignment(Pos.CENTER);

        VBox topBox = new VBox(6, colorLabel, clockPanel, statusLabel, actionRow);
        topBox.setAlignment(Pos.CENTER);
        root.setTop(topBox);
        BorderPane.setAlignment(topBox, Pos.CENTER);

        boardView.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.SECONDARY) {
                clearPremove();
                redrawBoard();
                return;
            }
            if (event.getButton() != MouseButton.PRIMARY) return;
            int row = boardView.pixelToRow(event.getY());
            int col = boardView.pixelToCol(event.getX());
            handleSquareClick(row, col);
        });

        historyPanel.refresh(state.getMoveHistory());

        VBox sidePanel = new VBox(16, historyPanel, buildChatPanel());
        HBox center = new HBox(20, boardView, sidePanel);
        center.setAlignment(Pos.CENTER);
        root.setCenter(center);

        refreshUiState();
        redrawBoard();

        Scene scene = new Scene(root);
        Theme.apply(scene);
        stage.setScene(scene);
        stage.setTitle("LAN Chess Arena - " + myColor);
        stage.setResizable(false);
        stage.show();

        stage.setOnCloseRequest(e -> {
            if (clockTicker != null) clockTicker.stop();
        });
    }

    private void startClockTicker() {
        clockTicker = new AnimationTimer() {
            @Override
            public void handle(long now) {
                if (state.getTimeControl().isUnlimited()) {
                    clockPanel.update(0, 0, null, true);
                    return;
                }
                long whiteDisplay = state.getRemainingMillis(PlayerColor.WHITE);
                long blackDisplay = state.getRemainingMillis(PlayerColor.BLACK);

                if (!gameOver) {
                    long elapsedSinceUpdate = System.currentTimeMillis() - lastStateReceivedAtMillis;
                    if (state.getCurrentTurn() == PlayerColor.WHITE) whiteDisplay -= elapsedSinceUpdate;
                    else blackDisplay -= elapsedSinceUpdate;
                }
                clockPanel.update(Math.max(0, whiteDisplay), Math.max(0, blackDisplay),
                        state.getCurrentTurn(), false);
            }
        };
        clockTicker.start();
    }

    private VBox buildChatPanel() {
        chatArea.setEditable(false);
        chatArea.setWrapText(true);
        chatArea.getStyleClass().add("chat-area");
        chatArea.setPrefSize(220, 150);

        chatInput.setPromptText("Ketik pesan...");
        chatInput.getStyleClass().add("pill-field");
        Button sendButton = new Button("Kirim");
        sendButton.getStyleClass().add("pill-button");
        Runnable sendChat = () -> {
            String text = chatInput.getText().trim();
            if (text.isEmpty()) return;
            client.sendMessage(new Message(MessageType.CHAT, text, myColor.name()));
            chatInput.clear();
        };
        sendButton.setOnAction(e -> sendChat.run());
        chatInput.setOnAction(e -> sendChat.run());

        HBox inputRow = new HBox(6, chatInput, sendButton);
        chatInput.setPrefWidth(140);

        Label chatTitle = new Label("Chat");
        chatTitle.getStyleClass().add("section-label");

        VBox chatBox = new VBox(8, chatTitle, chatArea, inputRow);
        chatBox.getStyleClass().add("info-panel");
        chatBox.setPrefWidth(220);
        return chatBox;
    }

    // =========================================================================
    // Interaksi papan (klik pilih bidak -> klik tujuan -> kirim MOVE, atau antrikan sebagai premove)
    // =========================================================================

    private void handleSquareClick(int row, int col) {
        if (gameOver) return;

        if (state.getCurrentTurn() != myColor) {
            handlePremoveClick(row, col);
            return;
        }

        // Kalau ada premove tersisa dari sebelumnya (jarang terjadi, tapi jaga-jaga) dan sekarang
        // ternyata giliran kita, batalkan dulu supaya tidak membingungkan alur klik normal.
        clearPremove();

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
            // Klik di kotak lain milik sendiri -> pindah seleksi ke situ, bukan error
            trySelect(row, col, clicked);
            return;
        }

        Move move = chosen.get();
        if (move.isPromotion()) {
            PieceType picked = askPromotionChoice();
            move.setPromotionType(picked);
        }

        client.sendMessage(new Message(MessageType.MOVE, move, myColor.name()));
        clearSelection();
        redrawBoard();
    }

    /** Alur klik saat BUKAN giliran kita - pilih bidak sendiri, lalu klik tujuan untuk mengantrikan premove. */
    private void handlePremoveClick(int row, int col) {
        Piece clicked = state.getPieceAt(row, col);

        if (premoveFromRow == null) {
            if (clicked != null && clicked.getColor() == myColor) {
                selectedRow = row;
                selectedCol = col;
                // Preview legal move SEKARANG (posisi saat ini) - hanya perkiraan, posisi
                // bisa berubah begitu lawan jalan sebelum giliran kita benar-benar tiba.
                currentLegalMoves = MoveValidator.getLegalMoves(state, row, col);
                redrawBoard();
            }
            return;
        }

        if (row == premoveFromRow && col == premoveFromCol) {
            clearPremove();
            redrawBoard();
            return;
        }

        boolean isValidTarget = currentLegalMoves.stream()
                .anyMatch(m -> m.getToRow() == row && m.getToCol() == col);

        if (!isValidTarget) {
            // Klik kotak lain milik sendiri -> pindah seleksi premove ke situ
            if (clicked != null && clicked.getColor() == myColor) {
                selectedRow = row;
                selectedCol = col;
                currentLegalMoves = MoveValidator.getLegalMoves(state, row, col);
                clearPremoveTargetOnly();
                redrawBoard();
            }
            return;
        }

        premoveFromRow = selectedRow;
        premoveFromCol = selectedCol;
        premoveToRow = row;
        premoveToCol = col;

        Piece movingPiece = state.getPieceAt(premoveFromRow, premoveFromCol);
        boolean isPromotionCandidate = movingPiece != null && movingPiece.getType() == PieceType.PAWN
                && (row == 0 || row == 7);
        premovePromotionType = isPromotionCandidate ? askPromotionChoice() : null;

        clearSelection();
        redrawBoard();
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

    private void clearPremove() {
        premoveFromRow = null;
        premoveFromCol = null;
        premoveToRow = null;
        premoveToCol = null;
        premovePromotionType = null;
        clearSelection();
    }

    private void clearPremoveTargetOnly() {
        premoveFromRow = null;
        premoveFromCol = null;
        premoveToRow = null;
        premoveToCol = null;
        premovePromotionType = null;
    }

    /**
     * Dipanggil setiap kali STATE_UPDATE baru diterima DAN sekarang giliran
     * kita. Coba kirim premove yang sedang diantrikan (kalau ada); kalau
     * sudah tidak legal lagi di posisi terbaru, buang diam-diam.
     */
    private void trySubmitPremove() {
        if (premoveFromRow == null) return;

        List<Move> nowLegal = MoveValidator.getLegalMoves(state, premoveFromRow, premoveFromCol);
        Optional<Move> stillValid = nowLegal.stream()
                .filter(m -> m.getToRow() == premoveToRow && m.getToCol() == premoveToCol)
                .findFirst();

        if (stillValid.isPresent()) {
            Move move = stillValid.get();
            if (move.isPromotion()) {
                move.setPromotionType(premovePromotionType != null ? premovePromotionType : PieceType.QUEEN);
            }
            client.sendMessage(new Message(MessageType.MOVE, move, myColor.name()));
        }
        clearPremove();
    }

    private PieceType askPromotionChoice() {
        ChoiceDialog<PieceType> dialog = new ChoiceDialog<>(PieceType.QUEEN,
                PieceType.QUEEN, PieceType.ROOK, PieceType.BISHOP, PieceType.KNIGHT);
        dialog.setTitle("Promosi Pion");
        dialog.setHeaderText("Pion mencapai baris terakhir!");
        dialog.setContentText("Promosikan menjadi:");
        Optional<PieceType> result = dialog.showAndWait();
        return result.orElse(PieceType.QUEEN);
    }

    // =========================================================================
    // Resign & Draw Offer
    // =========================================================================

    private void onResignClicked() {
        if (gameOver) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Resign");
        confirm.setHeaderText(null);
        confirm.setContentText("Yakin mau mengundurkan diri?");
        Optional<ButtonType> result = confirm.showAndWait();
        if (result.isPresent() && result.get() == ButtonType.OK) {
            client.sendMessage(new Message(MessageType.RESIGN, null, myColor.name()));
        }
    }

    private void onOfferDrawClicked() {
        if (gameOver) return;
        client.sendMessage(new Message(MessageType.DRAW_OFFER, null, myColor.name()));
        showAlert(Alert.AlertType.INFORMATION, "Tawaran Seri", "Tawaran seri terkirim, menunggu respons lawan.");
    }

    private void handleIncomingDrawOffer() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Tawaran Seri");
        alert.setHeaderText(null);
        alert.setContentText("Lawan menawarkan seri. Terima?");
        ButtonType acceptBtn = new ButtonType("Terima", ButtonType.OK.getButtonData());
        ButtonType declineBtn = new ButtonType("Tolak", ButtonType.CANCEL.getButtonData());
        alert.getButtonTypes().setAll(acceptBtn, declineBtn);

        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent() && result.get() == acceptBtn) {
            client.sendMessage(new Message(MessageType.DRAW_ACCEPT, null, myColor.name()));
        } else {
            client.sendMessage(new Message(MessageType.DRAW_DECLINE, null, myColor.name()));
        }
    }

    // =========================================================================
    // Pesan masuk dari server (dipanggil dari listener thread NetworkClient!)
    // =========================================================================

    private void onMessageReceived(Message message) {
        switch (message.getType()) {
            case STATE_UPDATE -> {
                GameState newState = message.getPayloadAs(GameState.class);
                Platform.runLater(() -> {
                    this.state = newState;
                    this.lastStateReceivedAtMillis = System.currentTimeMillis();
                    clearSelection();
                    refreshUiState();
                    redrawBoard();
                    historyPanel.refresh(state.getMoveHistory());

                    if (state.getCurrentTurn() == myColor
                            && (state.getStatus() == GameStatus.PLAYING || state.getStatus() == GameStatus.CHECK)) {
                        trySubmitPremove();
                    }
                });
            }
            case MOVE_REJECTED -> {
                String reason = message.getPayloadAs(String.class);
                Platform.runLater(() -> {
                    clearSelection();
                    redrawBoard();
                    showAlert(Alert.AlertType.WARNING, "Langkah ditolak", reason);
                });
            }
            case CHAT -> {
                String text = message.getPayloadAs(String.class);
                String from = message.getSender();
                Platform.runLater(() -> chatArea.appendText(from + ": " + text + "\n"));
            }
            case DRAW_OFFER -> Platform.runLater(this::handleIncomingDrawOffer);
            case DRAW_DECLINE -> Platform.runLater(() ->
                    showAlert(Alert.AlertType.INFORMATION, "Tawaran Seri", "Lawan menolak tawaran seri."));
            case END -> {
                GameStatus finalStatus = message.getPayloadAs(GameStatus.class);
                Platform.runLater(() -> {
                    gameOver = true;
                    clearPremove();
                    showAlert(Alert.AlertType.INFORMATION, "Permainan Selesai", describeEnding(finalStatus));
                });
            }
            case ERROR -> {
                String errorMsg = message.getPayloadAs(String.class);
                Platform.runLater(() -> {
                    gameOver = true;
                    showAlert(Alert.AlertType.ERROR, "Error", errorMsg);
                });
            }
            default -> { /* JOIN/ASSIGN_COLOR/DISCONNECT tidak relevan lagi di fase gameplay */ }
        }
    }

    private String describeEnding(GameStatus status) {
        return switch (status) {
            case CHECKMATE -> {
                PlayerColor winner = state.getLoserColor().opposite();
                yield "Skakmat! " + winner + " menang.";
            }
            case STALEMATE -> "Stalemate - permainan seri.";
            case TIMEOUT -> {
                PlayerColor winner = state.getLoserColor().opposite();
                yield "Waktu habis! " + winner + " menang.";
            }
            case RESIGNATION -> {
                PlayerColor winner = state.getLoserColor().opposite();
                yield state.getLoserColor() == myColor
                        ? "Kamu mengundurkan diri. " + winner + " menang."
                        : winner + " menang (lawan mengundurkan diri).";
            }
            case DRAW -> switch (state.getDrawReason()) {
                case THREEFOLD_REPETITION -> "Seri - posisi berulang 3 kali (threefold repetition).";
                case FIFTY_MOVE_RULE -> "Seri - 50 langkah tanpa capture/pion jalan (50-move rule).";
                case AGREEMENT -> "Seri - kesepakatan bersama.";
            };
            default -> "Permainan berakhir: " + status;
        };
    }

    // =========================================================================
    // Render helper
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
        if (premoveFromRow != null) {
            boardView.drawPremoveHighlight(premoveFromRow, premoveFromCol, premoveToRow, premoveToCol);
        }
    }

    private void refreshUiState() {
        colorLabel.setText("Kamu bermain sebagai: " + myColor);
        statusLabel.setText(describeStatus());
    }

    private String describeStatus() {
        if (gameOver) return "Permainan telah selesai.";
        String turnText = state.getCurrentTurn() == myColor ? "Giliranmu" : "Menunggu lawan";
        String premoveNote = (premoveFromRow != null) ? " (premove diantrikan)" : "";
        return switch (state.getStatus()) {
            case WAITING_FOR_PLAYER -> "Menunggu pemain kedua...";
            case CHECK -> turnText + " - SKAK!";
            case CHECKMATE -> "Skakmat!";
            case STALEMATE -> "Stalemate.";
            case DRAW -> "Seri.";
            case TIMEOUT -> "Waktu habis.";
            case RESIGNATION -> "Permainan selesai (resign).";
            case DISCONNECTED -> "Lawan terputus.";
            case PLAYING -> turnText + " (" + state.getCurrentTurn() + ")" + premoveNote;
        };
    }

    private void showAlert(Alert.AlertType type, String title, String content) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }

    private void showAlertAndReturnToMenu(String title, String content) {
        showAlert(Alert.AlertType.ERROR, title, content);
        if (clockTicker != null) clockTicker.stop();
        new MainMenuController(stage).show();
    }
}
