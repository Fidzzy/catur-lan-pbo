package com.lanchess.client;

import javafx.application.Application;
import javafx.stage.Stage;

/**
 * Entry point JavaFX standar (extends Application). Dipakai untuk
 * development lewat 'mvn javafx:run' (lihat javafx.mainClass di pom.xml).
 *
 * TIDAK dipakai sebagai Main-Class fat JAR - untuk itu pakai AppLauncher,
 * karena fat JAR + class yang extends Application sebagai entry point akan
 * gagal dengan error "JavaFX runtime components are missing" (module-path
 * JavaFX tidak ter-setup otomatis oleh java -jar biasa).
 */
public class MainApp extends Application {

    @Override
    public void start(Stage primaryStage) {
        new MainMenuController(primaryStage).show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
