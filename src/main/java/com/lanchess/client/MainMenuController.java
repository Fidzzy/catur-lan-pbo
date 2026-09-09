package com.lanchess.client;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Stage;

/**
 * Layar pertama yang dilihat pemain (frame 1 desain Figma): ilustrasi bidak
 * besar di kiri (memakai glyph unicode ♚ sebagai pengganti aset ilustrasi
 * asli, karena aset gambar tidak tersedia untuk di-embed), dan panel kartu
 * di kanan berisi dua pilihan mode utama.
 *
 * Alur selanjutnya:
 *   "Play With Friend" -> FriendModeController (frame 2: Host/Join)
 *   "Play VS Bot"       -> BotSetupController (frame 3: ELO + Timer + warna)
 */
public class MainMenuController {

    private final Stage stage;

    public MainMenuController(Stage stage) {
        this.stage = stage;
    }

    public void show() {
        Label bigKingIcon = new Label("\u265A");
        bigKingIcon.setFont(Font.font("Serif", FontWeight.BOLD, 220));
        bigKingIcon.setStyle("-fx-text-fill: linear-gradient(to bottom, #3a3a3a, #0a0a0a);");

        VBox leftSide = new VBox(bigKingIcon);
        leftSide.setAlignment(Pos.CENTER);
        HBox.setHgrow(leftSide, Priority.ALWAYS);

        // ---------- Panel kanan ----------
        Label smallIcon = new Label("\u265A\u2659\u2659");
        smallIcon.setFont(Font.font("Serif", FontWeight.BOLD, 34));
        smallIcon.setStyle("-fx-text-fill: #e6e6e6;");

        Button friendButton = new Button("Play With Friend");
        friendButton.getStyleClass().add("pill-button");
        friendButton.setMaxWidth(Double.MAX_VALUE);
        friendButton.setOnAction(e -> new FriendModeController(stage).show());

        HBox orDivider = orDivider();

        Button botButton = new Button("Play VS Bot");
        botButton.getStyleClass().add("pill-button");
        botButton.setMaxWidth(Double.MAX_VALUE);
        botButton.setOnAction(e -> new BotSetupController(stage).show());

        VBox card = new VBox(18, smallIcon, friendButton, orDivider, botButton);
        card.getStyleClass().add("card-panel");
        card.setAlignment(Pos.CENTER);
        card.setMaxWidth(260);
        card.setMinWidth(260);

        HBox root = new HBox(40, leftSide, card);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(40));

        BorderPane wrapper = new BorderPane(root);
        wrapper.getStyleClass().add("root");

        Scene scene = new Scene(wrapper);
        Theme.apply(scene);
        stage.setScene(scene);
        stage.setTitle("LAN Chess Arena");
        stage.setResizable(false);
        stage.sizeToScene();
        stage.show();
    }

    /** Divider "or" dengan garis horizontal di kedua sisi, meniru pemisah antar tombol di desain Figma. */
    static HBox orDivider() {
        Region leftLine = new Region();
        leftLine.setPrefHeight(1);
        leftLine.setStyle("-fx-background-color: #555555;");
        HBox.setHgrow(leftLine, Priority.ALWAYS);

        Region rightLine = new Region();
        rightLine.setPrefHeight(1);
        rightLine.setStyle("-fx-background-color: #555555;");
        HBox.setHgrow(rightLine, Priority.ALWAYS);

        Label or = new Label("or");
        or.getStyleClass().add("divider-text");

        HBox box = new HBox(10, leftLine, or, rightLine);
        box.setAlignment(Pos.CENTER);
        return box;
    }
}
