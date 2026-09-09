package com.lanchess.client;

import com.lanchess.model.GameState;
import com.lanchess.model.PlayerColor;
import com.lanchess.model.TimeControl;
import com.lanchess.server.GameServer;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.io.IOException;

/**
 * Frame 4 desain Figma: layar setup untuk host SEBELUM menyalakan
 * GameServer - pilih kontrol waktu (Timer) dan warna (Play as), lalu Start.
 * Host otomatis self-connect ke server-nya sendiri sebagai NetworkClient
 * biasa (persis seperti pemain lain), supaya seluruh alur gameplay
 * (MoveValidator, broadcast, dsb.) tetap satu jalur yang sama untuk semua
 * pemain, host maupun bukan.
 */
public class HostSetupController {

    private final Stage stage;

    private ChoiceBox<TimeControl> timerChoice;
    private PlayerColorChoice colorChoice;
    private Button startButton;
    private Label statusLabel;

    public HostSetupController(Stage stage) {
        this.stage = stage;
    }

    public void show() {
        Label timerLabel = new Label("Timer");
        timerLabel.getStyleClass().add("section-label");

        timerChoice = new ChoiceBox<>();
        timerChoice.getStyleClass().add("pill-choice");
        timerChoice.getItems().addAll(TimeControl.values());
        timerChoice.setValue(TimeControl.RAPID_10);
        timerChoice.setMaxWidth(Double.MAX_VALUE);

        Label playAsLabel = new Label("Play as");
        playAsLabel.getStyleClass().add("section-label");
        colorChoice = new PlayerColorChoice();

        startButton = new Button("Start");
        startButton.getStyleClass().add("pill-button");
        startButton.setMaxWidth(Double.MAX_VALUE);
        startButton.setOnAction(e -> onStartClicked());

        Button backButton = new Button("< Kembali");
        backButton.getStyleClass().add("pill-button-secondary");
        backButton.setOnAction(e -> new FriendModeController(stage).show());

        statusLabel = new Label("");
        statusLabel.getStyleClass().add("status-text");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(220);
        statusLabel.setAlignment(Pos.CENTER);

        VBox card = new VBox(14,
                timerLabel, timerChoice,
                playAsLabel, colorChoice,
                startButton, statusLabel, backButton);
        card.getStyleClass().add("card-panel");
        card.setAlignment(Pos.CENTER);
        card.setMaxWidth(260);
        card.setMinWidth(260);

        HBox root = new HBox(40, Theme.smallBoardPreview(), card);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(40));

        BorderPane wrapper = new BorderPane(root);
        wrapper.getStyleClass().add("root");

        Scene scene = new Scene(wrapper);
        Theme.apply(scene);
        stage.setScene(scene);
        stage.setTitle("LAN Chess Arena - Setup Host");
        stage.setResizable(false);
        stage.sizeToScene();
        stage.show();
    }

    private void onStartClicked() {
        startButton.setDisable(true);
        statusLabel.setText("Menjalankan server...");

        TimeControl timeControl = timerChoice.getValue();
        PlayerColor hostColor = colorChoice.getValue(); // null = random, diundi di GameServer.configure()

        Thread serverThread = new Thread(() -> {
            GameServer server = new GameServer();
            server.configure(timeControl, hostColor);
            server.start(GameServer.PORT);
        }, "GameServer-Thread");
        serverThread.setDaemon(true);
        serverThread.start();

        Thread connectThread = new Thread(() -> {
            try {
                Thread.sleep(300); // beri ServerSocket waktu mulai listen sebelum kita self-connect
                connectAsHost();
            } catch (InterruptedException ignored) {
            }
        }, "HostAutoConnect");
        connectThread.setDaemon(true);
        connectThread.start();
    }

    private void connectAsHost() {
        NetworkClient networkClient = new NetworkClient();
        PlayerColor[] assignedColorHolder = new PlayerColor[1];

        try {
            networkClient.connect("localhost", GameServer.PORT, message -> {
                switch (message.getType()) {
                    case ASSIGN_COLOR -> assignedColorHolder[0] = message.getPayloadAs(PlayerColor.class);
                    case STATE_UPDATE -> {
                        GameState state = message.getPayloadAs(GameState.class);
                        Platform.runLater(() -> {
                            if (assignedColorHolder[0] != null) {
                                new GameController(stage, networkClient, assignedColorHolder[0], state);
                            }
                        });
                    }
                    default -> { /* abaikan */ }
                }
            });
            Platform.runLater(() -> statusLabel.setText("Server aktif. Menunggu lawan LAN connect..."));
        } catch (IOException e) {
            Platform.runLater(() -> {
                startButton.setDisable(false);
                showAlert("Gagal menjalankan server", e.getMessage());
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
