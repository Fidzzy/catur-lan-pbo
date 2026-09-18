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
import javafx.scene.layout.Priority;
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
  * PREMOVE (antrean tak terbatas): kalau pemain klik papan SAAT BUKAN
  * gilirannya, tiap langkah valid dikunci ke PremoveQueue (boleh banyak,
  * dipilih di atas posisi proyeksi). Setiap kali giliran tiba, entri
  * terdepan dicoba dikirim otomatis - kalau posisi nyata sudah berubah
  * (kotak asal tak berisi bidak sendiri / tujuan terhalang / raja akan
  * skak), SELURUH sisa antrean dibuang (berhenti). Klik kotak asal premove
  * terakhir = undo premove itu; klik-kanan = buang semua.
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

    // --- Premove: antrean tak terbatas (logika di PremoveQueue, bukan field satu-langkah lagi) ---
    private final PremoveQueue premoveQueue = new PremoveQueue();

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

        Button backButton = new Button("Kembali ke Menu Utama");
        backButton.getStyleClass().add("pill-button-secondary");
        backButton.setOnAction(e -> confirmAndReturnToMenu());

        Button muteButton = new Button(SoundManager.isMuted() ? "🔇" : "🔊");
        muteButton.getStyleClass().add("pill-button-secondary");
        muteButton.setOnAction(e -> {
            SoundManager.setMuted(!SoundManager.isMuted());
            muteButton.setText(SoundManager.isMuted() ? "🔇" : "🔊");
        });

        HBox actionRow = new HBox(8, resignButton, offerDrawButton, muteButton, backButton);
        actionRow.setAlignment(Pos.CENTER);

        VBox topBox = new VBox(6, colorLabel, clockPanel, statusLabel, actionRow);
        topBox.setAlignment(Pos.CENTER);
        root.setTop(topBox);
        BorderPane.setAlignment(topBox, Pos.CENTER);

        boardView.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.SECONDARY) {
                premoveQueue.clear();
                redrawBoard();
                return;
            }
            if (event.getButton() != MouseButton.PRIMARY) return;
            int row = boardView.pixelToRow(event.getY());
            int col = boardView.pixelToCol(event.getX());
            handleSquareClick(row, col);
        });

        historyPanel.refresh(state.getMoveHistory());

        // --- Layout responsif: BoardHolder memaksa papan selalu persegi & muat
        // (aman saat maximize/fullscreen), panel kanan lebar fleksibel ---
        BoardHolder boardHolder = new BoardHolder(boardView);
        HBox.setHgrow(boardHolder, Priority.ALWAYS);

        VBox chatPanel = buildChatPanel();
        VBox sidePanel = new VBox(16, historyPanel, chatPanel);
        sidePanel.setPrefWidth(250);
        sidePanel.setMinWidth(210);
        sidePanel.setMaxWidth(320);
        sidePanel.setMaxHeight(Double.MAX_VALUE);
        sidePanel.setFillWidth(true);
        VBox.setVgrow(historyPanel, Priority.ALWAYS);
        VBox.setVgrow(chatPanel, Priority.NEVER);

        HBox center = new HBox(20, boardHolder, sidePanel);
        center.setAlignment(Pos.CENTER);
        center.setFillHeight(true);
        HBox.setHgrow(boardHolder, Priority.ALWAYS);
        root.setCenter(center);
        BorderPane.setAlignment(center, Pos.CENTER);

        refreshUiState();
        redrawBoard();

        UiNav.show(stage, root, "LAN Chess Arena - " + myColor, 800, 600, 1080, 720);
        // UiNav mempertahankan ukuran window; BoardHolder mengatur ukuran papan saat layout.

        stage.setOnCloseRequest(e -> {
            if (clockTicker != null) clockTicker.stop();
            // Tutup window (X) saat permainan berlangsung juga dihitung resign,
            // supaya lawan tetap mendapat kemenangan, bukan sekadar disconnect.
            if (isGameActive()) {
                try {
                    client.sendMessage(new Message(MessageType.RESIGN, null, myColor.name()));
                } catch (Exception ignored) {
                }
            }
            client.setOnDisconnected(null);
            client.disconnect();
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
        chatArea.setPrefSize(240, 150);
        chatArea.setMinSize(150, 80);
        chatArea.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        VBox.setVgrow(chatArea, Priority.ALWAYS);

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
        inputRow.setFillHeight(true);
        HBox.setHgrow(chatInput, Priority.ALWAYS);
        chatInput.setPrefWidth(140);
        chatInput.setMinWidth(80);
        chatInput.setMaxWidth(Double.MAX_VALUE);

        Label chatTitle = new Label("Chat");
        chatTitle.getStyleClass().add("section-label");

        VBox chatBox = new VBox(8, chatTitle, chatArea, inputRow);
        chatBox.getStyleClass().add("info-panel");
        chatBox.setPrefWidth(250);
        chatBox.setMinWidth(210);
        chatBox.setMaxWidth(320);
        chatBox.setFillWidth(true);
        return chatBox;
    }

    // =========================================================================
    // Interaksi papan (klik pilih bidak -> klik tujuan -> kirim MOVE, atau antrikan sebagai premove)
    // =========================================================================

    private void handleSquareClick(int row, int col) {
        DebugLog.log("LAN-CLICK", "klik (%d,%d) | giliran=%s saya=%s status=%s gameOver=%s selected=%s".formatted(
                row, col, state.getCurrentTurn(), myColor, state.getStatus(), gameOver,
                selectedRow == null ? "-" : "(" + selectedRow + "," + selectedCol + ")"));
        if (gameOver) return;
        if (row < 0 || row >= 8 || col < 0 || col >= 8) return;

        // Belum ada lawan (host sendirian menunggu) -> belum bisa jalan
        if (state.getStatus() == GameStatus.WAITING_FOR_PLAYER) {
            DebugLog.log("LAN-CLICK", "-> DITOLAK: masih WAITING_FOR_PLAYER (lawan belum connect)");
            showAlert(Alert.AlertType.INFORMATION, "Menunggu lawan",
                    "Menunggu pemain kedua terhubung sebelum permainan dimulai.");
            return;
        }

        if (state.getCurrentTurn() != myColor) {
            DebugLog.log("LAN-CLICK", "-> bukan giliran saya, masuk premove");
            handlePremoveClick(row, col);
            return;
        }

        // Giliran kita: seleksi pending premove tidak relevan di sini
        // (antrean tetap tersimpan, hanya seleksi pending yang dibersihkan).
        premoveQueue.clearSelection();

        Piece clicked = state.getPieceAt(row, col);

        if (selectedRow == null) {
            DebugLog.log("LAN-CLICK", "-> seleksi: diklik=" + describePiece(clicked));
            trySelect(row, col, clicked);
            DebugLog.log("LAN-CLICK", "-> legal moves dari sini: " + currentLegalMoves.size());
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
            DebugLog.log("LAN-CLICK", "-> tujuan TIDAK ada di legal moves, coba seleksi ulang");
            trySelect(row, col, clicked);
            return;
        }

        Move move = chosen.get();
        if (move.isPromotion()) {
            PieceType picked = askPromotionChoice();
            move.setPromotionType(picked);
        }

        DebugLog.log("LAN-CLICK", "-> KIRIM MOVE ke server: " + move);
        client.sendMessage(new Message(MessageType.MOVE, move, myColor.name()));
        // Langkah manual membatalkan sisa antrean premove (rencana lama tak berlaku lagi).
        premoveQueue.clear();
        clearSelection();
        redrawBoard();
    }

    private static String describePiece(Piece piece) {
        if (piece == null) return "kosong";
        return piece.getColor() + " " + piece.getType() + " (internal " + piece.getRow() + "," + piece.getCol() + ")";
    }

    /**
     * Alur klik saat BUKAN giliran kita - diteruskan ke antrean premove tak
     * terbatas (pilih bidak -> kunci tujuan, boleh berulang; klik asal
     * premove terakhir = undo). Seleksi normal tidak disentuh di sini.
     */
    private void handlePremoveClick(int row, int col) {
        if (row < 0 || row >= 8 || col < 0 || col >= 8) return;
        clearSelection();
        PremoveQueue.ClickOutcome outcome =
                premoveQueue.handleClick(state, myColor, row, col, this::askPromotionChoice);
        DebugLog.log("LAN-PREMOVE", "klik (%d,%d) -> %s | antrean=%d".formatted(
                row, col, outcome, premoveQueue.size()));
        redrawBoard();
        refreshUiState();
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

    /**
     * Dipanggil setiap kali STATE_UPDATE baru diterima DAN sekarang giliran
     * kita. Entri terdepan antrean dicoba dikirim; kalau tidak legal di
     * posisi nyata (kotak terisi / raja akan skak), seluruh sisa antrean
     * berhenti (dibuang) diam-diam tanpa error.
     */
    private void trySubmitPremove() {
        if (gameOver || state.getCurrentTurn() != myColor) return;
        Optional<Move> next = premoveQueue.pollHead(state, myColor);
        if (next.isPresent()) {
            Move move = next.get();
            DebugLog.log("LAN-PREMOVE", "eksekusi premove antrean: " + move
                    + " | sisa antrean=" + premoveQueue.size());
            client.sendMessage(new Message(MessageType.MOVE, move, myColor.name()));
        }
        redrawBoard();
        refreshUiState();
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
                DebugLog.log("LAN-NET", "STATE_UPDATE diterima: giliran=%s status=%s".formatted(
                        newState.getCurrentTurn(), newState.getStatus()));
                Platform.runLater(() -> {
                    boolean moveMade = newState.getMoveHistory().size() > state.getMoveHistory().size();
                    boolean captured = moveMade && countPieces(newState) < countPieces(state);
                    this.state = newState;
                    this.lastStateReceivedAtMillis = System.currentTimeMillis();
                    clearSelection();
                    premoveQueue.refresh(state, myColor);
                    refreshUiState();
                    redrawBoard();
                    historyPanel.refresh(state.getMoveHistory());

                    if (moveMade) {
                        if (state.getStatus() == GameStatus.CHECK || state.getStatus() == GameStatus.CHECKMATE) {
                            SoundManager.playCheck();
                        } else if (captured) {
                            SoundManager.playCapture();
                        } else {
                            SoundManager.playMove();
                        }
                    }

                    if (state.getCurrentTurn() == myColor
                            && (state.getStatus() == GameStatus.PLAYING || state.getStatus() == GameStatus.CHECK)) {
                        trySubmitPremove();
                    }
                });
            }
            case MOVE_REJECTED -> {
                String reason = message.getPayloadAs(String.class);
                DebugLog.log("LAN-NET", "MOVE_REJECTED dari server: " + reason);
                Platform.runLater(() -> {
                    clearSelection();
                    redrawBoard();
                    showAlert(Alert.AlertType.WARNING, "Langkah ditolak", reason);
                });
            }
            case CHAT -> {
                String text = message.getPayloadAs(String.class);
                String from = message.getSender();
                Platform.runLater(() -> {
                    chatArea.appendText(from + ": " + text + "\n");
                    if (!myColor.name().equals(from)) SoundManager.playNotify();
                });
            }
            case DRAW_OFFER -> Platform.runLater(this::handleIncomingDrawOffer);
            case DRAW_DECLINE -> Platform.runLater(() ->
                    showAlert(Alert.AlertType.INFORMATION, "Tawaran Seri", "Lawan menolak tawaran seri."));
            case REMATCH_OFFER -> Platform.runLater(this::handleIncomingRematchOffer);
            case REMATCH_DECLINE -> Platform.runLater(() ->
                    showAlert(Alert.AlertType.INFORMATION, "Rematch", "Lawan menolak ajakan main lagi."));
            case REMATCH_START -> {
                GameState freshState = message.getPayloadAs(GameState.class);
                Platform.runLater(() -> {
                    shutdown();
                    new GameController(stage, client, myColor, freshState);
                });
            }
            case END -> {
                GameStatus finalStatus = message.getPayloadAs(GameStatus.class);
                Platform.runLater(() -> {
                    gameOver = true;
                    premoveQueue.clear();
                    refreshUiState();
                    redrawBoard();
                    playEndingSound();
                    showGameOverDialog(finalStatus);
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

    /** Hitung jumlah bidak di papan (untuk deteksi capture antar STATE_UPDATE). */
    private static int countPieces(GameState s) {
        int n = 0;
        for (int r = 0; r < 8; r++) {
            for (int c = 0; c < 8; c++) {
                if (s.getPieceAt(r, c) != null) n++;
            }
        }
        return n;
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

    private String describeEnding(GameStatus status) {
        return switch (status) {
            case CHECKMATE -> {
                if (state.getLoserColor() == null) yield "Skakmat! Permainan berakhir.";
                PlayerColor winner = state.getLoserColor().opposite();
                yield "Skakmat! " + winner + " menang.";
            }
            case STALEMATE -> "Stalemate - permainan seri.";
            case TIMEOUT -> {
                if (state.getLoserColor() == null) yield "Waktu habis! Permainan berakhir.";
                PlayerColor winner = state.getLoserColor().opposite();
                yield "Waktu habis! " + winner + " menang.";
            }
            case RESIGNATION -> {
                if (state.getLoserColor() == null) yield "Permainan selesai (salah satu pemain menyerah).";
                PlayerColor winner = state.getLoserColor().opposite();
                yield state.getLoserColor() == myColor
                        ? "Kamu mengundurkan diri. " + winner + " menang."
                        : winner + " menang (lawan mengundurkan diri).";
            }
            case DRAW -> {
                if (state.getDrawReason() == null) yield "Seri - permainan berakhir seri.";
                yield switch (state.getDrawReason()) {
                    case THREEFOLD_REPETITION -> "Seri - posisi berulang 3 kali (threefold repetition).";
                    case FIFTY_MOVE_RULE -> "Seri - 50 langkah tanpa capture/pion jalan (50-move rule).";
                    case AGREEMENT -> "Seri - kesepakatan bersama.";
                };
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
    }

    private void refreshUiState() {
        colorLabel.setText("Kamu bermain sebagai: " + myColor);
        statusLabel.setText(describeStatus());
    }

    private String describeStatus() {
        if (gameOver) return "Permainan telah selesai.";
        String turnText = state.getCurrentTurn() == myColor ? "Giliranmu" : "Menunggu lawan";
        String premoveNote = premoveQueue.isEmpty() ? ""
                : " (" + premoveQueue.size() + " premove diantrikan)";
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

    // =========================================================================
    // Rematch (main lagi di atas koneksi yang sama)
    // =========================================================================

    /** Dialog game-over dengan opsi rematch. Tulisan hasil tetap dari describeEnding(). */
    private void showGameOverDialog(GameStatus finalStatus) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Permainan Selesai");
        alert.setHeaderText(null);
        alert.setContentText(describeEnding(finalStatus) + "\n\nMain lagi?");
        ButtonType rematchBtn = new ButtonType("Main Lagi", ButtonType.OK.getButtonData());
        ButtonType closeBtn = new ButtonType("Tutup", ButtonType.CANCEL.getButtonData());
        alert.getButtonTypes().setAll(rematchBtn, closeBtn);

        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent() && result.get() == rematchBtn) {
            onRematchClicked();
        }
    }

    /** Kirim ajakan rematch ke lawan. Hanya valid setelah game over (dicek juga di server). */
    private void onRematchClicked() {
        if (!gameOver) return;
        client.sendMessage(new Message(MessageType.REMATCH_OFFER, null, myColor.name()));
        statusLabel.setText("Menunggu jawaban rematch dari lawan...");
    }

    private void handleIncomingRematchOffer() {
        // Tawaran basi (mis. game sudah rematch duluan) diabaikan.
        if (!gameOver) return;
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Ajakan Rematch");
        alert.setHeaderText(null);
        alert.setContentText("Lawan mengajak main lagi. Terima?");
        ButtonType acceptBtn = new ButtonType("Terima", ButtonType.OK.getButtonData());
        ButtonType declineBtn = new ButtonType("Tolak", ButtonType.CANCEL.getButtonData());
        alert.getButtonTypes().setAll(acceptBtn, declineBtn);

        Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent() && result.get() == acceptBtn) {
            client.sendMessage(new Message(MessageType.REMATCH_ACCEPT, null, myColor.name()));
        } else {
            client.sendMessage(new Message(MessageType.REMATCH_DECLINE, null, myColor.name()));
        }
    }

    /** Hentikan ticker jam sebelum controller ini diganti controller baru (rematch) atau menu. */
    private void shutdown() {
        gameOver = true;
        if (clockTicker != null) clockTicker.stop();
    }

    private void showAlertAndReturnToMenu(String title, String content) {
        showAlert(Alert.AlertType.ERROR, title, content);
        shutdown();
        new MainMenuController(stage).show();
    }

    /**
     * Opsi "Kembali ke Menu Utama" di dalam permainan. Kalau permainan masih
     * berlangsung (status PLAYING/CHECK dan belum game-over), yang menekan
     * dihitung RESIGN: kirim RESIGN ke server dulu supaya lawan dinyatakan
     * menang via broadcast END, baru putus koneksi & kembali ke menu.
     * Kalau game sudah selesai / masih menunggu lawan, langsung kembali
     * tanpa mengirim RESIGN.
     */
    private void confirmAndReturnToMenu() {
        if (isGameActive()) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("Kembali ke Menu Utama");
            confirm.setHeaderText(null);
            confirm.setContentText("Permainan masih berlangsung. Kembali ke menu utama "
                    + "akan dihitung sebagai resign (kalah). Lanjutkan?");
            Optional<ButtonType> result = confirm.showAndWait();
            if (result.isEmpty() || result.get() != ButtonType.OK) return;

            gameOver = true;
            try {
                client.sendMessage(new Message(MessageType.RESIGN, null, myColor.name()));
            } catch (Exception ignored) {
            }
            returnToMenu();
            return;
        }

        if (!gameOver) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("Kembali ke Menu Utama");
            confirm.setHeaderText(null);
            confirm.setContentText("Kembali ke menu utama? Permainan akan dihentikan.");
            Optional<ButtonType> result = confirm.showAndWait();
            if (result.isEmpty() || result.get() != ButtonType.OK) return;
        }
        returnToMenu();
    }

    /** Hentikan ticker, nonaktifkan callback disconnect, putus socket, tampilkan menu utama. */
    private void returnToMenu() {
        shutdown();
        // Cegah alert "Koneksi terputus" muncul saat disconnect yang disengaja ini.
        client.setOnDisconnected(() -> { });
        client.disconnect();
        new MainMenuController(stage).show();
    }

    /** True kalau permainan sedang berlangsung dan keluar = resign. */
    private boolean isGameActive() {
        if (gameOver || state == null) return false;
        return state.getStatus() == GameStatus.PLAYING || state.getStatus() == GameStatus.CHECK;
    }
}
