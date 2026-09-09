package com.lanchess.client;

/**
 * Main class untuk fat JAR (dikonfigurasi di maven-shade-plugin, pom.xml).
 *
 * WORKAROUND WAJIB: class ini SENGAJA TIDAK extends javafx.application.Application.
 * Kalau Main-Class di manifest fat JAR adalah class yang extends Application
 * secara langsung, java -jar akan melempar:
 *   "Error: JavaFX runtime components are missing, and are required to run this application"
 * meskipun javafx-controls/graphics/base sudah ikut di-shade ke dalam JAR.
 * Ini karena java launcher mendeteksi Main-Class sebagai subclass Application
 * SEBELUM classpath/module sepenuhnya siap, lalu menganggap JavaFX harus
 * dimuat sebagai module (padahal ini classpath biasa, bukan module-path).
 *
 * Solusinya: taruh main() di class TERPISAH yang tidak extends Application,
 * yang tugasnya cuma memanggil MainApp.main(args) -> di dalam situ barulah
 * Application.launch() dipanggil secara normal.
 */
public class AppLauncher {

    public static void main(String[] args) {
        MainApp.main(args);
    }
}
