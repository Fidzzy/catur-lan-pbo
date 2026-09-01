package com.lanchess.client;

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

        VBox topBox = new VBox(6, colorLabel, clockPanel, statusLabel);
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
    // Interaksi papan (klik pilih bidak -> klik tujuan -> kirim MOVE)
    // =========================================================================

    private void handleSquareClick(int row, int col) {
        if (gameOver) return;
        if (state.getCurrentTurn() != myColor) return; // bukan giliran kita, abaikan klik

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
        Optional<PieceType> result = dialog.showAndWait();
        return result.orElse(PieceType.QUEEN);
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
            case END -> {
                GameStatus finalStatus = message.getPayloadAs(GameStatus.class);
                Platform.runLater(() -> {
                    gameOver = true;
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
                PlayerColor winner = state.getCurrentTurn().opposite();
                yield "Skakmat! " + winner + " menang.";
            }
            case STALEMATE -> "Stalemate - permainan seri.";
            case DRAW -> "Permainan berakhir seri.";
            case TIMEOUT -> {
                PlayerColor winner = state.getCurrentTurn().opposite();
                yield "Waktu habis! " + winner + " menang.";
            }
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
    }

    private void refreshUiState() {
        colorLabel.setText("Kamu bermain sebagai: " + myColor);
        statusLabel.setText(describeStatus());
    }

    private String describeStatus() {
        if (gameOver) return "Permainan telah selesai.";
        String turnText = state.getCurrentTurn() == myColor ? "Giliranmu" : "Menunggu lawan";
        return switch (state.getStatus()) {
            case WAITING_FOR_PLAYER -> "Menunggu pemain kedua...";
            case CHECK -> turnText + " - SKAK!";
            case CHECKMATE -> "Skakmat!";
            case STALEMATE -> "Stalemate.";
            case DRAW -> "Seri.";
            case TIMEOUT -> "Waktu habis.";
            case DISCONNECTED -> "Lawan terputus.";
            case PLAYING -> turnText + " (" + state.getCurrentTurn() + ")";
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
