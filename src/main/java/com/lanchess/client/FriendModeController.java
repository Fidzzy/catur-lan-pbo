package com.lanchess.client;

import com.lanchess.model.GameState;
import com.lanchess.model.Message;
import com.lanchess.model.PlayerColor;
import com.lanchess.server.GameServer;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;

import java.io.IOException;

/**
 * Frame 2 desain Figma: setelah pemain klik "Play With Friend" di menu
 * utama, layar ini menawarkan dua jalur:
 *   - "Play As Host" -> HostSetupController (frame 4: Timer + warna + Start)
 *   - Isi IP + "Join Game" -> connect langsung (host sudah menentukan
 *     time control & warna di layarnya sendiri, joiner tinggal terima)
 */
public class FriendModeController {

    private final Stage stage;
    private final NetworkClient networkClient = new NetworkClient();
    private PlayerColor assignedColor;

    private Button hostButton;
    private TextField ipField;
    private Button joinButton;
    private Label statusLabel;
    private ProgressIndicator progressIndicator;

    public FriendModeController(Stage stage) {
        this.stage = stage;
    }

    public void show() {
        Label icon = new Label("\u265A");
        icon.setFont(Font.font("Serif", FontWeight.BOLD, 40));
        icon.setStyle("-fx-text-fill: #e6e6e6;");

        hostButton = new Button("Play As Host");
        hostButton.getStyleClass().add("pill-button");
        hostButton.setMaxWidth(Double.MAX_VALUE);
        hostButton.setOnAction(e -> new HostSetupController(stage).show());

        HBox orDivider = MainMenuController.orDivider();

        ipField = new TextField("localhost");
        ipField.getStyleClass().add("pill-field");
        ipField.setPromptText("Insert IP Host");
        ipField.setMaxWidth(Double.MAX_VALUE);

        joinButton = new Button("Join Game");
        joinButton.getStyleClass().add("pill-button");
        joinButton.setMaxWidth(Double.MAX_VALUE);
        joinButton.setOnAction(e -> onJoinClicked());

        statusLabel = new Label("");
        statusLabel.getStyleClass().add("status-text");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(220);
        statusLabel.setAlignment(Pos.CENTER);

        progressIndicator = new ProgressIndicator();
        progressIndicator.setPrefSize(22, 22);
        progressIndicator.setVisible(false);

        Button backButton = new Button("< Kembali");
        backButton.getStyleClass().add("pill-button-secondary");
        backButton.setOnAction(e -> new MainMenuController(stage).show());

        VBox card = new VBox(16, icon, hostButton, orDivider, ipField, joinButton, progressIndicator, statusLabel, backButton);
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
        stage.setTitle("LAN Chess Arena - Main dengan Teman");
        stage.setResizable(false);
        stage.sizeToScene();
        stage.show();
    }

    private void onJoinClicked() {
        String host = ipField.getText().trim();
        if (host.isEmpty()) {
            statusLabel.setText("Masukkan IP server terlebih dahulu.");
            return;
        }
        setBusy(true, "Menghubungkan ke " + host + " ...");
        Thread connectThread = new Thread(() -> connect(host), "JoinConnect");
        connectThread.setDaemon(true);
        connectThread.start();
    }

    private void connect(String host) {
        try {
            networkClient.connect(host, GameServer.PORT, this::onMessageReceived);
            Platform.runLater(() -> statusLabel.setText("Terhubung! Menunggu host memulai..."));
        } catch (IOException e) {
            Platform.runLater(() -> {
                setBusy(false, null);
                showAlert("Gagal terhubung", "Tidak bisa connect ke " + host + ":" + GameServer.PORT + "\n" + e.getMessage());
            });
        }
    }

    private void onMessageReceived(Message message) {
        switch (message.getType()) {
            case ASSIGN_COLOR -> assignedColor = message.getPayloadAs(PlayerColor.class);
            case STATE_UPDATE -> {
                GameState initialState = message.getPayloadAs(GameState.class);
                Platform.runLater(() -> {
                    if (assignedColor == null) {
                        showAlert("Error", "Belum menerima warna dari server.");
                        return;
                    }
                    new GameController(stage, networkClient, assignedColor, initialState);
                });
            }
            case ERROR -> {
                String err = message.getPayloadAs(String.class);
                Platform.runLater(() -> {
                    setBusy(false, null);
                    showAlert("Server Error", err);
                });
            }
            default -> { /* abaikan tipe lain di fase ini */ }
        }
    }

    private void setBusy(boolean busy, String statusText) {
        Platform.runLater(() -> {
            hostButton.setDisable(busy);
            joinButton.setDisable(busy);
            ipField.setDisable(busy);
            progressIndicator.setVisible(busy);
            if (statusText != null) statusLabel.setText(statusText);
        });
    }

    private void showAlert(String title, String content) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content);
        alert.showAndWait();
    }
}
