package com.lanchess.client;

import com.lanchess.bot.BotDifficulty;
import com.lanchess.bot.ChessEngine;
import com.lanchess.bot.StockfishEngine;
import com.lanchess.bot.StockfishLocator;
import com.lanchess.model.PlayerColor;
import com.lanchess.model.TimeControl;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;

import java.io.IOException;
import java.util.Optional;

/**
 * Frame 3 desain Figma ("Select ELO"), diperluas dengan field Timer sesuai
 * requirement: setup lengkap sebelum main lawan Stockfish - pilih rating
 * ELO (kekuatan bot), kontrol waktu, dan warna sendiri.
 */
public class BotSetupController {

    private final Stage stage;

    private ChoiceBox<BotDifficulty> eloChoice;
    private ChoiceBox<TimeControl> timerChoice;
    private PlayerColorChoice colorChoice;
    private TextField enginePathField;
    private Button startButton;
    private Label statusLabel;

    public BotSetupController(Stage stage) {
        this.stage = stage;
    }

    public void show() {
        // ============ HEADER ============
        Label cardIcon = new Label("\u265A");
        cardIcon.setFont(Font.font("Serif", FontWeight.BOLD, 32));
        cardIcon.getStyleClass().add("setup-icon");

        Label title = new Label("Setup Permainan");
        title.getStyleClass().add("setup-card-title");

        Label subtitle = new Label("Atur preferensi sebelum mulai");
        subtitle.getStyleClass().add("setup-card-subtitle");

        VBox header = new VBox(2, title, subtitle);
        header.setAlignment(Pos.CENTER);

        // ============ SECTIONS (scrollable) ============
        // --- ELO ---
        Label eloLabel = new Label("KESULITAN BOT");
        eloLabel.getStyleClass().add("setup-field-label");
        eloChoice = new ChoiceBox<>();
        eloChoice.getStyleClass().add("pill-choice");
        eloChoice.getItems().addAll(BotDifficulty.values());
        eloChoice.setValue(BotDifficulty.MEDIUM);
        eloChoice.setMaxWidth(Double.MAX_VALUE);
        VBox eloSection = new VBox(4, eloLabel, eloChoice);
        eloSection.setFillWidth(true);

        // --- Timer ---
        Label timerLabel = new Label("KONTROL WAKTU");
        timerLabel.getStyleClass().add("setup-field-label");
        timerChoice = new ChoiceBox<>();
        timerChoice.getStyleClass().add("pill-choice");
        timerChoice.getItems().addAll(TimeControl.values());
        timerChoice.setValue(TimeControl.UNLIMITED);
        timerChoice.setMaxWidth(Double.MAX_VALUE);
        VBox timerSection = new VBox(4, timerLabel, timerChoice);
        timerSection.setFillWidth(true);

        // --- Warna ---
        Label colorLabel = new Label("MAIN SEBAGAI");
        colorLabel.getStyleClass().add("setup-field-label");
        colorChoice = new PlayerColorChoice();
        VBox colorSection = new VBox(4, colorLabel, colorChoice);
        colorSection.setFillWidth(true);

        // --- Engine Path (TANPA note di sini) ---
        Label engineLabel = new Label("PATH STOCKFISH");
        engineLabel.getStyleClass().add("setup-field-label");
        enginePathField = new TextField();
        enginePathField.getStyleClass().add("pill-field");
        enginePathField.setPromptText("Path ke Stockfish");
        enginePathField.setMaxWidth(Double.MAX_VALUE);
        Optional<String> detected = StockfishLocator.autoDetect();
        enginePathField.setText(detected.orElse("stockfish"));
        VBox engineSection = new VBox(4, engineLabel, enginePathField);
        engineSection.setFillWidth(true);

        VBox sections = new VBox(8, eloSection, timerSection, colorSection, engineSection);
        sections.setFillWidth(true);
        sections.setPadding(new Insets(0, 6, 0, 0)); // ruang untuk scrollbar

        ScrollPane sectionsScroll = new ScrollPane(sections);
        sectionsScroll.setFitToWidth(true);
        sectionsScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sectionsScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        sectionsScroll.getStyleClass().add("card-scroll");
        sectionsScroll.setMinHeight(80); // agar tidak kolaps jadi 0
        VBox.setVgrow(sectionsScroll, Priority.ALWAYS);

        // ============ FOOTER (fixed) ============
        // NOTE deteksi dipindah ke sini — selalu kelihatan
        Label detectionNote = new Label(detected.isPresent()
                ? "\u2713 Terdeteksi otomatis"
                : "\u26A0 Tidak terdeteksi - cek path di atas");
        detectionNote.setStyle("-fx-font-size: 10px; -fx-text-fill: "
                + (detected.isPresent() ? "#7fe07f" : "#e0a030") + ";");
        detectionNote.setWrapText(true);
        detectionNote.setAlignment(Pos.CENTER);
        detectionNote.setMaxWidth(Double.MAX_VALUE);

        startButton = new Button("Mulai Bermain");
        startButton.getStyleClass().add("pill-button");
        startButton.setMaxWidth(Double.MAX_VALUE);
        startButton.setOnAction(e -> onStartClicked());

        statusLabel = new Label("");
        statusLabel.getStyleClass().add("status-text");
        statusLabel.setWrapText(true);
        statusLabel.setAlignment(Pos.CENTER);

        Button backButton = new Button("< Kembali");
        backButton.getStyleClass().add("pill-button-secondary");
        backButton.setOnAction(e -> new MainMenuController(stage).show());

        VBox footer = new VBox(6, detectionNote, startButton, statusLabel, backButton);
        footer.setAlignment(Pos.CENTER);

        // ============ CARD ============
        VBox card = new VBox(10,
                cardIcon,
                header,
                makeDivider(),
                sectionsScroll,   // scrollable
                makeDivider(),
                footer);          // fixed
        card.getStyleClass().add("card-panel");
        card.setAlignment(Pos.CENTER);
        card.setPrefWidth(320);
        card.setMinWidth(300);
        card.setMaxWidth(340);
        card.setMaxHeight(Double.MAX_VALUE);

        // ============ LAYOUT ============
        BoardHolder preview = Theme.responsivePreview();
        HBox.setHgrow(preview, Priority.ALWAYS);

        HBox root = new HBox(20, preview, card);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(20));
        root.setFillHeight(true);

        BorderPane wrapper = new BorderPane(root);
        wrapper.getStyleClass().add("root");

        UiNav.show(stage, wrapper, "LAN Chess Arena - Setup vs Bot", 800, 600, 1080, 720);
    }

    /** Garis pemisah tipis dengan lebar terbatas. */
    private Region makeDivider() {
        Region sep = new Region();
        sep.getStyleClass().add("setup-divider");
        return sep;
    }

    private void onStartClicked() {
        startButton.setDisable(true);
        statusLabel.setText("Menjalankan Stockfish...");

        BotDifficulty difficulty = eloChoice.getValue();
        TimeControl timeControl = timerChoice.getValue();
        PlayerColor chosenColor = colorChoice.getValue();
        PlayerColor myColor = (chosenColor != null) ? chosenColor
                : (Math.random() < 0.5 ? PlayerColor.WHITE : PlayerColor.BLACK);
        String enginePath = enginePathField.getText().trim();

        Thread startThread = new Thread(
                () -> startBotGame(difficulty, timeControl, myColor, enginePath),
                "StockfishStartup");
        startThread.setDaemon(true);
        startThread.start();
    }

    private void startBotGame(BotDifficulty difficulty, TimeControl timeControl,
                              PlayerColor myColor, String enginePath) {
        ChessEngine engine = new StockfishEngine();
        try {
            engine.start(enginePath);
            engine.setElo(difficulty.getEloRating());
            engine.newGame();

            Platform.runLater(() ->
                    new BotGameController(stage, engine, difficulty, timeControl, myColor, enginePath));

        } catch (IOException e) {
            Platform.runLater(() -> {
                startButton.setDisable(false);
                showAlert("Gagal menjalankan Stockfish",
                        "Tidak bisa menjalankan engine di path: \"" + enginePath + "\"\n\n"
                                + "Pastikan Stockfish sudah terinstall, contoh:\n"
                                + "  - Ubuntu/Debian: sudo apt install stockfish\n"
                                + "  - macOS: brew install stockfish\n"
                                + "  - Windows: unduh dari stockfishchess.org lalu isi path .exe-nya\n\n"
                                + "Detail error: " + e.getMessage());
            });
        }
    }

    private void showAlert(String title, String content) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }
}