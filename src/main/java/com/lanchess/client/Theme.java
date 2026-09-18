package com.lanchess.client;

import com.lanchess.model.GameState;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;

import java.util.List;

/** Memuat stylesheet tema (theme.css) ke sebuah Scene. Dipanggil di setiap Controller layar. */
public final class Theme {

    private Theme() {
    }

    public static void apply(Scene scene) {
        var url = Theme.class.getResource("/com/lanchess/client/theme.css");
        if (url != null) {
            scene.getStylesheets().add(url.toExternalForm());
        }
    }

    /**
     * Preview papan kosong berukuran kecil untuk dekorasi di layar-layar
     * setup (Host/Join/Bot). Memakai BoardView(squareSize) dengan kotak
     * lebih kecil daripada ukuran default gameplay (76px), supaya ruang
     * layout yang dibutuhkan benar-benar kecil (bukan cuma di-scale visual)
     * dan Scene auto-size menghitung ukuran window dengan akurat.
     */
    public static BoardView smallBoardPreview() {
        BoardView preview = new BoardView(38); // setengah dari ukuran default gameplay
        preview.render(new GameState(), null, null, List.of(), null, null);
        return preview;
    }

    /**
     * Preview papan yang RESPONSIF untuk layar setup (Friend/Host/Bot):
     * papan mengisi ruang sisa dan ikut membesar/mengecil mengikuti window
     * (square-fit, terpusat). Pakai ini sebagai child HBox root, bukan
     * {@link #smallBoardPreview()} yang ukurannya fix.
     */
    public static StackPane responsivePreview() {
        BoardView preview = smallBoardPreview();
        StackPane holder = new StackPane(preview);
        holder.setAlignment(Pos.CENTER);
        holder.setMinSize(180, 180);
        holder.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        HBox.setHgrow(holder, Priority.ALWAYS);
        StackPane.setAlignment(preview, Pos.CENTER);
        Runnable fit = () -> {
            double s = Math.min(holder.getWidth(), holder.getHeight());
            if (s >= 120) {
                preview.resize(s, s);
            }
        };
        holder.widthProperty().addListener((o, a, b) -> fit.run());
        holder.heightProperty().addListener((o, a, b) -> fit.run());
        return holder;
    }
}
