package com.lanchess.client;

import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * Helper navigasi antar layar: ukuran window dipertahankan saat pindah
 * halaman, yang responsif adalah ISI (papan, panel) di dalamnya.
 *
 * Masalah sebelumnya: tiap controller membuat {@code new Scene(root, W, H)}
 * dengan W/H beda-beda (menu 820x520, game 1080x720, ...), sehingga setiap
 * ganti halaman window ikut membesar/mengecil.
 *
 * Cara pakai: ganti blok
 * {@code new Scene(...); stage.setScene(...); stage.setResizable(...); ...; stage.show();}
 * menjadi {@code UiNav.show(stage, root, title, minW, minH, defaultW, defaultH);}.
 */
public final class UiNav {

    private UiNav() {
    }

    public static void show(Stage stage, Parent root, String title,
                            double minWidth, double minHeight,
                            double defaultWidth, double defaultHeight) {
        boolean hadScene = stage.getScene() != null;
        boolean wasShowing = stage.isShowing();
        boolean wasMaximized = stage.isMaximized();
        double oldW = wasShowing ? stage.getWidth() : 0;
        double oldH = wasShowing ? stage.getHeight() : 0;

        Scene scene;
        if (!hadScene && !wasShowing) {
            // Cold start (pertama kali aplikasi dibuka): pakai ukuran default.
            scene = new Scene(root, defaultWidth, defaultHeight);
        } else {
            // Pindah halaman: Scene mengikuti ukuran window saat ini (tidak memaksa resize).
            // Root yang responsif (boardHolder HGrow, papan square-fit) akan mengisi ruang yang ada.
            scene = new Scene(root);
        }
        Theme.apply(scene);
        stage.setScene(scene);
        stage.setTitle(title);
        stage.setResizable(true);
        stage.setMinWidth(minWidth);
        stage.setMinHeight(minHeight);

        if (hadScene && wasShowing && !wasMaximized && oldW > 0 && oldH > 0) {
            // Pertahankan ukuran window; hanya membesar kalau di bawah minimum layar baru.
            if (oldW < minWidth) oldW = minWidth;
            if (oldH < minHeight) oldH = minHeight;
            stage.setWidth(oldW);
            stage.setHeight(oldH);
        }
        stage.show();
    }
}
