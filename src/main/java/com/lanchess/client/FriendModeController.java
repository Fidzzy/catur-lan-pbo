package com.lanchess.client;

import com.lanchess.model.GameState;
import com.lanchess.model.Message;
import com.lanchess.model.PlayerColor;
import com.lanchess.server.GameServer;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
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

/**
 * Frame 2 desain Figma: setelah pemain klik "Play With Friend" di menu
 * utama, layar ini menawarkan dua jalur:
 *   - "Play As Host" -> HostSetupController
 *   - Isi IP + "Join Game" -> connect langsung
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
        // ============ HEADER ============
        Label cardIcon = new Label("\u265A");
        cardIcon.setFont(Font.font("Serif", FontWeight.BOLD, 36));
        cardIcon.getStyleClass().add("setup-icon");

        Label title = new Label("Main dengan Teman");
        title.getStyleClass().add("setup-card-title");

        Label subtitle = new Label("Host atau gabung ke permainan");
        subtitle.getStyleClass().add("setup-card-subtitle");

        VBox header = new VBox(2, title, subtitle);
        header.setAlignment(Pos.CENTER);

        // ============ SECTIONS (scrollable) ============
        hostButton = new Button("Play As Host");
        hostButton.getStyleClass().add("pill-button");
        hostButton.setMaxWidth(Double.MAX_VALUE);
        hostButton.setOnAction(e -> new HostSetupController(stage).show());

        HBox orDivider = MainMenuController.orDivider();

        Label ipLabel = new Label("IP HOST");
        ipLabel.getStyleClass().add("setup-field-label");

        ipField = new TextField("localhost");
        ipField.getStyleClass().add("pill-field");
        ipField.setMaxWidth(Double.MAX_VALUE);

        VBox ipSection = new VBox(4, ipLabel, ipField);
        ipSection.setFillWidth(true);

        joinButton = new Button("Join Game");
        joinButton.getStyleClass().add("pill-button");
        joinButton.setMaxWidth(Double.MAX_VALUE);
        joinButton.setOnAction(e -> onJoinClicked());

        VBox sections = new VBox(12, hostButton, orDivider, ipSection, joinButton);
        sections.setFillWidth(true);
        sections.setPadding(new Insets(0, 6, 0, 0));

        ScrollPane sectionsScroll = new ScrollPane(sections);
        sectionsScroll.setFitToWidth(true);
        sectionsScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        sectionsScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        sectionsScroll.getStyleClass().add("card-scroll");
        sectionsScroll.setMinHeight(80);
        VBox.setVgrow(sectionsScroll, Priority.ALWAYS);

        // ============ FOOTER (fixed) ============
        statusLabel = new Label("");
        statusLabel.getStyleClass().add("status-text");
        statusLabel.setWrapText(true);
        statusLabel.setAlignment(Pos.CENTER);

        progressIndicator = new ProgressIndicator();
        progressIndicator.setPrefSize(22, 22);
        progressIndicator.setVisible(false);

        Button backButton = new Button("< Kembali");
        backButton.getStyleClass().add("pill-button-secondary");
        backButton.setOnAction(e -> new MainMenuController(stage).show());

        VBox footer = new VBox(8, progressIndicator, statusLabel, backButton);
        footer.setAlignment(Pos.CENTER);

        // ============ CARD ============
        VBox card = new VBox(10,
                cardIcon, header, makeDivider(),
                sectionsScroll,
                makeDivider(), footer);
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

        // *** INI YANG HILANG SEBELUMNYA ***
        UiNav.show(stage, wrapper, "LAN Chess Arena - Main dengan Teman", 800, 600, 1080, 720);
    }

    /** Garis pemisah tipis. */
    private Region makeDivider() {
        Region sep = new Region();
        sep.getStyleClass().add("setup-divider");
        return sep;
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
                showAlert("Gagal terhubung",
                        "Tidak bisa connect ke " + host + ":" + GameServer.PORT + "\n" + e.getMessage());
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
            default -> { /* abaikan */ }
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