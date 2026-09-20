package com.lanchess.client;

import com.lanchess.model.GameMode;
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

/**
 * Layar antara "Play With Friend" dan FriendModeController: pilih sub-mode
 * CLASSIC (catur murni) atau QUIZ (catur + kuis tiap 10 langkah).
 *
 * Meneruskan GameMode ke FriendModeController(stage, mode).
 */
public class ModeSelectionView {

    private final Stage stage;

    public ModeSelectionView(Stage stage) {
        this.stage = stage;
    }

    public void show() {
        Label icon = new Label("\u265A");
        icon.setFont(Font.font("Serif", FontWeight.BOLD, 36));
        icon.getStyleClass().add("setup-icon");

        Label title = new Label("Pilih Mode");
        title.getStyleClass().add("setup-card-title");

        Label subtitle = new Label("Main dengan teman");
        subtitle.getStyleClass().add("setup-card-subtitle");

        VBox header = new VBox(2, title, subtitle);
        header.setAlignment(Pos.CENTER);

        // ===== CLASSIC =====
        Button classicButton = new Button("Classic");
        classicButton.getStyleClass().add("pill-button");
        classicButton.setMaxWidth(Double.MAX_VALUE);
        classicButton.setOnAction(e -> new FriendModeController(stage, GameMode.CLASSIC).show());

        Label classicDesc = new Label("Catur standar tanpa kuis.");
        classicDesc.getStyleClass().add("setup-card-subtitle");
        classicDesc.setWrapText(true);

        // ===== QUIZ =====
        Button quizButton = new Button("Quiz");
        quizButton.getStyleClass().add("pill-button");
        quizButton.setMaxWidth(Double.MAX_VALUE);
        quizButton.setOnAction(e -> new FriendModeController(stage, GameMode.QUIZ).show());

        Label quizDesc = new Label("Kuis setiap 10 langkah penuh. Jawab benar = +30 detik.");
        quizDesc.getStyleClass().add("setup-card-subtitle");
        quizDesc.setWrapText(true);

        VBox sections = new VBox(10,
                classicButton, classicDesc,
                makeDivider(),
                quizButton, quizDesc);
        sections.setFillWidth(true);

        Button backButton = new Button("< Kembali");
        backButton.getStyleClass().add("pill-button-secondary");
        backButton.setMaxWidth(Double.MAX_VALUE);
        backButton.setOnAction(e -> new MainMenuController(stage).show());

        VBox card = new VBox(14,
                icon, header, makeDivider(),
                sections,
                makeDivider(), backButton);
        card.getStyleClass().add("card-panel");
        card.setAlignment(Pos.CENTER);
        card.setPrefWidth(320);
        card.setMinWidth(300);
        card.setMaxWidth(340);
        card.setMaxHeight(Double.MAX_VALUE);

        BoardHolder preview = Theme.responsivePreview();
        HBox.setHgrow(preview, Priority.ALWAYS);

        HBox root = new HBox(20, preview, card);
        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(20));
        root.setFillHeight(true);

        BorderPane wrapper = new BorderPane(root);
        wrapper.getStyleClass().add("root");

        UiNav.show(stage, wrapper, "LAN Chess Arena - Pilih Mode", 800, 600, 1080, 720);
    }

    private Region makeDivider() {
        Region sep = new Region();
        sep.getStyleClass().add("setup-divider");
        return sep;
    }
}