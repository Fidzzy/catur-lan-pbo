package com.lanchess.client;

import com.lanchess.bot.BotDifficulty;
import com.lanchess.bot.StockfishEngine;
import com.lanchess.bot.StockfishLocator;
import com.lanchess.model.PlayerColor;
import com.lanchess.model.TimeControl;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
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
        Label eloLabel = new Label("Select ELO");
        eloLabel.getStyleClass().add("section-label");

        eloChoice = new ChoiceBox<>();
        eloChoice.getStyleClass().add("pill-choice");
        eloChoice.getItems().addAll(BotDifficulty.values());
        eloChoice.setValue(BotDifficulty.MEDIUM);
        eloChoice.setMaxWidth(Double.MAX_VALUE);

        Label timerLabel = new Label("Timer");
        timerLabel.getStyleClass().add("section-label");

        timerChoice = new ChoiceBox<>();
        timerChoice.getStyleClass().add("pill-choice");
        timerChoice.getItems().addAll(TimeControl.values());
        timerChoice.setValue(TimeControl.UNLIMITED);
        timerChoice.setMaxWidth(Double.MAX_VALUE);

        Label playAsLabel = new Label("Play as");
        playAsLabel.getStyleClass().add("section-label");
        colorChoice = new PlayerColorChoice();

        enginePathField = new TextField();
        enginePathField.getStyleClass().add("pill-field");
        enginePathField.setPromptText("Path ke Stockfish");
        enginePathField.setMaxWidth(Double.MAX_VALUE);
        Optional<String> detected = StockfishLocator.autoDetect();
        enginePathField.setText(detected.orElse("stockfish"));

        Label detectionNote = new Label(detected.isPresent()
                ? "\u2713 Stockfish terdeteksi otomatis"
                : "Tidak terdeteksi otomatis - cek path di atas");
        detectionNote.setStyle("-fx-font-size: 10px; -fx-text-fill: "
                + (detected.isPresent() ? "#7fe07f" : "#e0a030") + ";");
        detectionNote.setWrapText(true);
        detectionNote.setMaxWidth(220);

        startButton = new Button("Start");
        startButton.getStyleClass().add("pill-button");
        startButton.setMaxWidth(Double.MAX_VALUE);
        startButton.setOnAction(e -> onStartClicked());

        Button backButton = new Button("< Kembali");
        backButton.getStyleClass().add("pill-button-secondary");
        backButton.setOnAction(e -> new MainMenuController(stage).show());

        statusLabel = new Label("");
        statusLabel.getStyleClass().add("status-text");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(220);
        statusLabel.setAlignment(Pos.CENTER);

        VBox card = new VBox(12,
                eloLabel, eloChoice,
                timerLabel, timerChoice,
                playAsLabel, colorChoice,
                enginePathField, detectionNote,
                startButton, statusLabel, backButton);
        card.getStyleClass().add("card-panel");
        card.setAlignment(Pos.CENTER);
        card.setMaxWidth(270);
        card.setMinWidth(270);

        HBox root = new HBox(40, Theme.smallBoardPreview(), card);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(40));

        BorderPane wrapper = new BorderPane(root);
        wrapper.getStyleClass().add("root");

        Scene scene = new Scene(wrapper);
        Theme.apply(scene);
        stage.setScene(scene);
        stage.setTitle("LAN Chess Arena - Setup vs Bot");
        stage.setResizable(false);
        stage.sizeToScene();
        stage.show();
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

        Thread startThread = new Thread(() -> startBotGame(difficulty, timeControl, myColor, enginePath), "StockfishStartup");
        startThread.setDaemon(true);
        startThread.start();
    }

    private void startBotGame(BotDifficulty difficulty, TimeControl timeControl, PlayerColor myColor, String enginePath) {
        StockfishEngine engine = new StockfishEngine();
        try {
            engine.start(enginePath);
            engine.setElo(difficulty.getEloRating());
            engine.newGame();

            Platform.runLater(() -> new BotGameController(stage, engine, difficulty, timeControl, myColor));

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
