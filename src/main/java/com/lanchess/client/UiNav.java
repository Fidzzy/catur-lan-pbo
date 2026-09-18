package com.lanchess.client;

import javafx.geometry.Rectangle2D;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Screen;
import javafx.stage.Stage;

/**
 * Helper navigasi antar layar: ukuran window dipertahankan saat pindah
 * halaman, yang responsif adalah ISI (papan, panel) di dalamnya.
 *
 * Masalah sebelumnya: tiap controller membuat {@code new Scene(root, W, H)}
 * dengan W/H beda-beda (menu 820x520, game 1080x720, ...), sehingga setiap
 * ganti halaman window ikut membesar/mengecil.
 *
 * Jaminan tambahan: window TIDAK PERNAH lebih besar dari layar yang terlihat
 * (visual bounds = layar dikurangi taskbar) dan TIDAK PERNAH nongol keluar
 * layar. Tanpa ini, di laptop 768p/864p window game bisa lebih tinggi dari
 * layar sehingga baris papan paling bawah (bidak sendiri) ketutup tepi
 * layar/taskbar dan dikira "papan terpotong".
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

        Rectangle2D screen = Screen.getPrimary().getVisualBounds();

        // Minimum layar baru tidak boleh memaksa window lebih besar dari layar.
        double minW = Math.min(minWidth, screen.getWidth());
        double minH = Math.min(minHeight, screen.getHeight());

        Scene scene;
        if (!hadScene && !wasShowing) {
            // Cold start (pertama kali aplikasi dibuka): pakai ukuran default,
            // tapi dijepit ke layar supaya tidak nongol keluar layar kecil.
            scene = new Scene(root,
                    Math.min(defaultWidth, screen.getWidth()),
                    Math.min(defaultHeight, screen.getHeight()));
        } else {
            // Pindah halaman: Scene mengikuti ukuran window saat ini (tidak memaksa resize).
            // Root yang responsif (boardHolder HGrow, papan square-fit) akan mengisi ruang yang ada.
            scene = new Scene(root);
        }
        Theme.apply(scene);
        stage.setScene(scene);
        stage.setTitle(title);
        stage.setResizable(true);
        stage.setMinWidth(minW);
        stage.setMinHeight(minH);

        if (hadScene && wasShowing && !wasMaximized && oldW > 0 && oldH > 0) {
            // Pertahankan ukuran window; hanya membesar kalau di bawah minimum layar baru,
            // dan tidak pernah melebihi layar.
            stage.setWidth(clamp(oldW, minW, screen.getWidth()));
            stage.setHeight(clamp(oldH, minH, screen.getHeight()));
        }
        stage.show();

        if (!stage.isMaximized() && !stage.isFullScreen()) {
            // Jepit window ke dalam layar yang terlihat (posisi + ukuran).
            double w = Math.min(stage.getWidth(), screen.getWidth());
            double h = Math.min(stage.getHeight(), screen.getHeight());
            stage.setWidth(w);
            stage.setHeight(h);
            double x = stage.getX();
            double y = stage.getY();
            if (x < screen.getMinX()) x = screen.getMinX();
            if (y < screen.getMinY()) y = screen.getMinY();
            if (x + w > screen.getMaxX()) x = Math.max(screen.getMinX(), screen.getMaxX() - w);
            if (y + h > screen.getMaxY()) y = Math.max(screen.getMinY(), screen.getMaxY() - h);
            stage.setX(x);
            stage.setY(y);
        }
    }

    private static double clamp(double value, double min, double max) {
        if (value < min) return min;
        return Math.min(value, max);
    }
}
