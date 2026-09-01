package com.lanchess.client;

import com.lanchess.model.Message;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.function.Consumer;

/**
 * Menangani koneksi TCP dari client ke GameServer. Membuka listener thread
 * terpisah yang terus membaca Message dari server dan meneruskannya ke
 * callback (biasanya GameController.onMessageReceived).
 *
 * PENTING: callback dipanggil dari LISTENER THREAD (bukan JavaFX Application
 * Thread). Kalau callback ingin update UI (ubah node JavaFX), pemanggil
 * WAJIB membungkusnya dengan Platform.runLater() - lihat GameController
 * untuk contoh pemakaiannya. NetworkClient sendiri tidak melakukan itu di
 * sini karena tidak semua pemakai callback butuh sentuh UI (mis. logging).
 */
public class NetworkClient {

    private Socket socket;
    private ObjectOutputStream out;
    private ObjectInputStream in;
    private Thread listenerThread;

    private volatile boolean connected = false;
    private Consumer<Message> onMessageReceived;
    private Runnable onDisconnected;

    /**
     * Membuka koneksi ke server dan langsung memulai listener thread.
     * Method ini BLOCKING sampai handshake stream selesai (biasanya instan
     * di LAN), jadi sebaiknya dipanggil dari background thread kalau dipakai
     * langsung dari alur UI supaya tidak membekukan JavaFX Application Thread.
     */
    public void connect(String host, int port, Consumer<Message> onMessageReceived) throws IOException {
        this.onMessageReceived = onMessageReceived;

        socket = new Socket(host, port);

        // Output SEBELUM input - HARUS sama urutannya dengan ClientHandler di
        // sisi server, kalau tidak keduanya saling menunggu (deadlock).
        out = new ObjectOutputStream(socket.getOutputStream());
        out.flush();
        in = new ObjectInputStream(socket.getInputStream());

        connected = true;

        listenerThread = new Thread(this::listenLoop, "NetworkClient-Listener");
        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    private void listenLoop() {
        try {
            while (connected) {
                Message message = (Message) in.readObject();
                if (onMessageReceived != null) {
                    onMessageReceived.accept(message);
                }
            }
        } catch (IOException | ClassNotFoundException e) {
            // Koneksi putus (server mati / network drop / socket ditutup lokal)
            connected = false;
            if (onDisconnected != null) {
                onDisconnected.run();
            }
        }
    }

    /**
     * Kirim Message ke server. synchronized untuk mencegah dua thread UI
     * (mis. klik cepat berturut-turut) menulis bersamaan ke stream yang sama.
     * out.reset() dipanggil untuk konsistensi dengan sisi server (lihat
     * ClientHandler.sendMessage untuk penjelasan lengkap kenapa perlu reset()).
     */
    public synchronized void sendMessage(Message message) {
        if (!connected) return;
        try {
            out.writeObject(message);
            out.flush();
            out.reset();
        } catch (IOException e) {
            connected = false;
            if (onDisconnected != null) {
                onDisconnected.run();
            }
        }
    }

    public void setOnDisconnected(Runnable onDisconnected) {
        this.onDisconnected = onDisconnected;
    }

    /**
     * Ganti callback penerima pesan setelah koneksi sudah terbentuk.
     * Dipakai saat berpindah fase LOBBY -> GAMEPLAY: FriendModeController/
     * menangani ASSIGN_COLOR & STATE_UPDATE awal, lalu setelah scene
     * berpindah ke GameController, routing pesan berikutnya diambil alih
     * lewat method ini (listenLoop() membaca field ini di setiap iterasi,
     * jadi aman diganti kapan saja tanpa menghentikan listener thread).
     */
    public void setOnMessageReceived(Consumer<Message> onMessageReceived) {
        this.onMessageReceived = onMessageReceived;
    }

    public boolean isConnected() {
        return connected;
    }

    public void disconnect() {
        connected = false;
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {
        }
    }
}
