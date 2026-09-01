package com.lanchess.client;

import com.lanchess.model.PlayerColor;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/**
 * Menampilkan dua jam (Putih & Hitam) format mm:ss. Class ini murni visual,
 * tidak menyimpan logika waktu apapun - pemanggil (GameController /
 * BotGameController) yang menghitung nilai terkini dan memanggil update()
 * secara berkala lewat AnimationTimer masing-masing.
 */
public class ClockPanel extends HBox {

    private final Label whiteLabel = new Label("--:--");
    private final Label blackLabel = new Label("--:--");

    public ClockPanel() {
        super(16);
        setAlignment(Pos.CENTER);
        whiteLabel.getStyleClass().add("clock-label");
        blackLabel.getStyleClass().add("clock-label");

        VBox whiteBox = labeledBox("Putih", whiteLabel);
        VBox blackBox = labeledBox("Hitam", blackLabel);
        getChildren().addAll(whiteBox, blackBox);
    }

    private VBox labeledBox(String caption, Label clockLabel) {
        Label captionLabel = new Label(caption);
        captionLabel.getStyleClass().add("divider-text");
        VBox box = new VBox(2, captionLabel, clockLabel);
        box.setAlignment(Pos.CENTER);
        return box;
    }

    public void update(long whiteMillis, long blackMillis, PlayerColor activeColor, boolean unlimited) {
        if (unlimited) {
            whiteLabel.setText("\u221E");
            blackLabel.setText("\u221E");
            whiteLabel.getStyleClass().removeAll("clock-active", "clock-low");
            blackLabel.getStyleClass().removeAll("clock-active", "clock-low");
            return;
        }
        whiteLabel.setText(format(whiteMillis));
        blackLabel.setText(format(blackMillis));
        applyState(whiteLabel, whiteMillis, activeColor == PlayerColor.WHITE);
        applyState(blackLabel, blackMillis, activeColor == PlayerColor.BLACK);
    }

    private void applyState(Label label, long millis, boolean active) {
        label.getStyleClass().removeAll("clock-active", "clock-low");
        if (millis <= 10_000) {
            label.getStyleClass().add("clock-low");
        } else if (active) {
            label.getStyleClass().add("clock-active");
        }
    }

    private static String format(long millis) {
        long totalSeconds = Math.max(0, millis) / 1000;
        long m = totalSeconds / 60;
        long s = totalSeconds % 60;
        return String.format("%02d:%02d", m, s);
    }
}
