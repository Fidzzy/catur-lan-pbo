package com.lanchess.client;

import javafx.animation.FadeTransition;
import javafx.animation.TranslateTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
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
import javafx.util.Duration;

public class MainMenuController {

    private final Stage stage;

    public MainMenuController(Stage stage) {
        this.stage = stage;
    }

    public void show() {
        // --- Bagian Kiri: Logo / Ikon Besar ---
        Label bigKingIcon = new Label("\u265A");
        bigKingIcon.setFont(Font.font("Serif", FontWeight.BOLD, 240));
        // Memberikan efek gradasi dan glow pada ikon
        bigKingIcon.setStyle("-fx-text-fill: linear-gradient(to bottom, #4A90E2, #1E1E2E); "
                + "-fx-effect: dropshadow(three-pass-box, rgba(74,144,226,0.3), 30, 0, 0, 0);");

        VBox leftSide = new VBox(bigKingIcon);
        leftSide.setAlignment(Pos.CENTER);
        HBox.setHgrow(leftSide, Priority.ALWAYS);

        // --- Bagian Kanan: Panel Menu ---
        Label smallIcon = new Label("\u265A\u2659\u2659");
        smallIcon.setFont(Font.font("Serif", FontWeight.BOLD, 34));
        smallIcon.setStyle("-fx-text-fill: #4A90E2;");

        Button friendButton = new Button("Play With Friend");
        friendButton.getStyleClass().add("pill-button");
        friendButton.setMaxWidth(Double.MAX_VALUE);
        friendButton.setOnAction(e -> new ModeSelectionView(stage).show());

        HBox orDivider = orDivider();

        Button botButton = new Button("Play VS Bot");
        botButton.getStyleClass().add("pill-button");
        botButton.setMaxWidth(Double.MAX_VALUE);
        botButton.setOnAction(e -> new BotSetupController(stage).show());

        VBox card = new VBox(20, smallIcon, friendButton, orDivider, botButton);
        card.getStyleClass().add("card-panel");
        card.setAlignment(Pos.CENTER);
        card.setMaxWidth(280);
        card.setMinWidth(280);

        // --- Root Layout ---
        HBox root = new HBox(50, leftSide, card);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(40));

        BorderPane wrapper = new BorderPane(root);
        wrapper.getStyleClass().add("root");

        // --- Animasi Masuk (Fade In & Slide Up) ---
        wrapper.setOpacity(0);
        FadeTransition fadeIn = new FadeTransition(Duration.millis(800), wrapper);
        fadeIn.setFromValue(0);
        fadeIn.setToValue(1);

        TranslateTransition slideUp = new TranslateTransition(Duration.millis(800), card);
        slideUp.setFromY(30);
        slideUp.setToY(0);

        fadeIn.play();
        slideUp.play();

        UiNav.show(stage, wrapper, "LAN Chess Arena", 800, 500, 900, 600);
    }

    static HBox orDivider() {
        Label or = new Label("or");
        or.getStyleClass().add("divider-text");

        HBox box = new HBox(or);
        box.setAlignment(Pos.CENTER);
        return box;
    }
}