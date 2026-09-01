package com.lanchess.client;

import com.lanchess.model.PlayerColor;
import javafx.geometry.Pos;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.control.Tooltip;

/**
 * Widget 3 lingkaran pilihan warna (Putih / Acak / Hitam), meniru bagian
 * "Play as" di desain Figma. getValue() mengembalikan null kalau "Random"
 * dipilih - pemanggil (HostSetupController/BotSetupController) yang
 * bertanggung jawab mengundi warna sesungguhnya saat tombol Start ditekan.
 */
public class PlayerColorChoice extends HBox {

    private final Region whiteCircle;
    private final Region randomCircle;
    private final Region blackCircle;
    private PlayerColor selected = null; // default: Random

    public PlayerColorChoice() {
        super(14);
        setAlignment(Pos.CENTER);

        whiteCircle = makeCircle("color-circle-white", "Main sebagai Putih");
        randomCircle = makeCircle("color-circle-random", "Acak");
        blackCircle = makeCircle("color-circle-black", "Main sebagai Hitam");

        whiteCircle.setOnMouseClicked(e -> select(PlayerColor.WHITE));
        randomCircle.setOnMouseClicked(e -> select(null));
        blackCircle.setOnMouseClicked(e -> select(PlayerColor.BLACK));

        getChildren().addAll(whiteCircle, randomCircle, blackCircle);
        select(null); // default: Random terpilih
    }

    private Region makeCircle(String colorStyleClass, String tooltipText) {
        Region r = new Region();
        r.getStyleClass().addAll("color-circle", colorStyleClass);
        Tooltip.install(r, new Tooltip(tooltipText));
        return r;
    }

    private void select(PlayerColor color) {
        selected = color;
        whiteCircle.getStyleClass().remove("color-circle-selected");
        randomCircle.getStyleClass().remove("color-circle-selected");
        blackCircle.getStyleClass().remove("color-circle-selected");

        Region target = (color == PlayerColor.WHITE) ? whiteCircle
                : (color == PlayerColor.BLACK) ? blackCircle
                : randomCircle;
        target.getStyleClass().add("color-circle-selected");
    }

    /** @return warna terpilih, atau null kalau "Random" (pemanggil harus resolve sendiri). */
    public PlayerColor getValue() {
        return selected;
    }
}
