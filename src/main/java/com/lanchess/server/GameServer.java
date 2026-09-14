package com.lanchess.server;

import com.lanchess.model.DrawReason;
import com.lanchess.model.GameState;
import com.lanchess.model.GameStatus;
import com.lanchess.model.Message;
import com.lanchess.model.MessageType;
import com.lanchess.model.PlayerColor;
import com.lanchess.model.TimeControl;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Entry point server. Buka ServerSocket di port 5555, terima TEPAT 2 client,
 * pegang satu GameState bersama, dan berperan sebagai "subject" dalam
 * Observer Pattern: setiap kali state berubah, broadcastState() memberitahu
 * semua ClientHandler (observer) yang lalu meneruskan ke client masing-masing.
 *
 * Jalankan berdiri sendiri (headless, tanpa JavaFX) di laptop host:
 *   mvn compile exec:java -Dexec.mainClass=com.lanchess.server.GameServer
 * atau langsung: java -cp target/classes com.lanchess.server.GameServer
 *
 * KONFIGURASI SEBELUM start(): panggil configure(timeControl, hostColor)
 * kalau host ingin memilih kontrol waktu dan/atau warnanya sendiri (lihat
 * HostSetupController). Kalau tidak dipanggil sama sekali, default-nya
 * TimeControl.UNLIMITED dan host otomatis WHITE (perilaku lama, tetap
 * backward-compatible).
 */
public class GameServer {

    public static final int PORT = 5555;
    private static final int MAX_PLAYERS = 2;

    private final GameState gameState = new GameState();
    /**
     * CopyOnWriteArrayList (bukan ArrayList biasa): karena sendMessage() yang
     * gagal bisa memicu closeConnection() -> handleDisconnect() secara
     * REENTRANT di tengah loop broadcast (thread yang sama, lock sama boleh
     * masuk lagi karena synchronized itu reentrant) - itu artinya `clients`
     * bisa termodifikasi SAAT sedang di-iterasi oleh for-each yang sama.
     * CopyOnWriteArrayList aman untuk pola ini (iterator snapshot-based),
     * ArrayList biasa akan melempar ConcurrentModificationException.
     */
    private final List<ClientHandler> clients = new CopyOnWriteArrayList<>();

    private ServerSocket serverSocket;
    private volatile boolean running = false;

    /** Warna yang di-assign ke client PERTAMA yang connect (selalu host itu sendiri, self-connect). */
    private PlayerColor hostColor = PlayerColor.WHITE;

    private GameClock gameClock;

    /** Warna pemain yang sedang menawarkan seri, null kalau tidak ada tawaran pending. */
    private PlayerColor pendingDrawOfferFrom;

    public static void main(String[] args) {
        int port = PORT;
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Port tidak valid, memakai default " + PORT);
            }
        }
        new GameServer().start(port);
    }

    /**
     * Atur kontrol waktu & warna host SEBELUM memanggil start(). Kalau
     * hostColor null, warna diundi sekali secara acak di sini (deterministik
     * setelah dipanggil, tidak diundi ulang tiap connection).
     */
    public void configure(TimeControl timeControl, PlayerColor hostColor) {
        gameState.setTimeControl(timeControl);
        this.hostColor = (hostColor != null) ? hostColor
                : (Math.random() < 0.5 ? PlayerColor.WHITE : PlayerColor.BLACK);
    }

    public void start(int port) {
        running = true;
        try {
            serverSocket = new ServerSocket(port);
            log("Server aktif di port " + port + ". Menunggu " + MAX_PLAYERS + " pemain...");

            while (running && clients.size() < MAX_PLAYERS) {
                Socket socket = serverSocket.accept();
                // Client PERTAMA yang connect = host itu sendiri (self-connect segera
                // setelah server dinyalakan) -> dapat hostColor. Client kedua = lawan LAN.
                PlayerColor assignedColor = clients.isEmpty() ? hostColor : hostColor.opposite();

                log("Client baru terhubung dari " + socket.getInetAddress().getHostAddress()
                        + " -> di-assign warna " + assignedColor);

                ClientHandler handler = new ClientHandler(socket, this, assignedColor);
                clients.add(handler);
                new Thread(handler, "ClientHandler-" + assignedColor).start();

                // Tunggu stream handler ini benar-benar siap sebelum lanjut (accept
                // client berikutnya / broadcastState() di bawah) - mencegah race
                // dimana broadcastState() menulis ke ClientHandler yang field
                // `out`-nya belum sempat diinisialisasi (NullPointerException).
                try {
                    handler.awaitReady();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log("Terinterupsi saat menunggu handler siap.");
                }
            }

            if (clients.size() == MAX_PLAYERS) {
                log("Kedua pemain sudah terhubung. Permainan dimulai! TimeControl="
                        + gameState.getTimeControl());
                gameState.setStatus(GameStatus.PLAYING);

                if (!gameState.getTimeControl().isUnlimited()) {
                    gameClock = new GameClock(gameState, this::handleTimeout);
                    gameClock.startTurn();
                }

                broadcastState();
            }

        } catch (IOException e) {
            log("Server error: " + e.getMessage());
        }
    }

    public void stop() {
        running = false;
        if (gameClock != null) gameClock.stop();
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException e) {
            log("Gagal menutup server socket: " + e.getMessage());
        }
    }

    // =========================================================================
    // Dipanggil oleh ClientHandler
    // =========================================================================

    public synchronized GameState getGameState() {
        return gameState;
    }

    /**
     * Dipanggil ClientHandler TEPAT SETELAH sebuah move berhasil divalidasi
     * & dieksekusi (giliran sudah berpindah). Memberi tahu GameClock supaya
     * jam yang baru saja jalan dikurangi, dan jam pemain berikutnya mulai
     * berjalan. Kalau game sudah berakhir (checkmate/stalemate/DRAW lewat
     * threefold repetition atau 50-move rule), jam dihentikan sepenuhnya
     * supaya tidak ada timeout nyasar setelah game usai.
     */
    public synchronized void notifyMoveMade(PlayerColor moverColor) {
        if (gameClock == null) return;
        gameClock.onMoveMade(moverColor);
        GameStatus status = gameState.getStatus();
        if (status == GameStatus.CHECKMATE || status == GameStatus.STALEMATE || status == GameStatus.DRAW) {
            gameClock.stop();
        }
    }

    /** Hentikan jam catur langsung (dipanggil ClientHandler saat resign/draw-accept). */
    public synchronized void stopClock() {
        if (gameClock != null) gameClock.stop();
    }

    /** Teruskan tawaran seri dari sender ke lawannya, dan catat sebagai pending. */
    public synchronized void relayDrawOffer(ClientHandler sender) {
        pendingDrawOfferFrom = sender.getAssignedColor();
        Message offer = new Message(MessageType.DRAW_OFFER, null, sender.getAssignedColor().name());
        for (ClientHandler client : clients) {
            if (client != sender) client.sendMessage(offer);
        }
    }

    /** Teruskan notifikasi penolakan tawaran seri ke lawan (si penawar), dan hapus status pending. */
    public synchronized void relayDrawDecline(ClientHandler sender) {
        pendingDrawOfferFrom = null;
        Message decline = new Message(MessageType.DRAW_DECLINE, null, sender.getAssignedColor().name());
        for (ClientHandler client : clients) {
            if (client != sender) client.sendMessage(decline);
        }
    }

    /**
     * Finalisasi seri kalau memang ada tawaran pending dari LAWAN
     * acceptingColor (bukan tawaran dari diri sendiri - itu tidak masuk akal).
     *
     * @return true kalau tawaran valid & berhasil difinalisasi jadi DRAW.
     */
    public synchronized boolean finalizeDrawIfPending(PlayerColor acceptingColor) {
        if (pendingDrawOfferFrom == null || pendingDrawOfferFrom == acceptingColor) {
            return false;
        }
        pendingDrawOfferFrom = null;
        gameState.setStatus(GameStatus.DRAW);
        gameState.setDrawReason(DrawReason.AGREEMENT);
        stopClock();
        broadcastState();
        broadcastEnd();
        return true;
    }

    /** Callback dari GameClock ketika salah satu pemain kehabisan waktu. */
    private synchronized void handleTimeout(PlayerColor timedOutColor) {
        log(timedOutColor + " kehabisan waktu. Game berakhir (TIMEOUT).");
        gameState.setLoserColor(timedOutColor);
        gameState.setStatus(GameStatus.TIMEOUT);
        broadcastState();
        broadcastEnd();
    }

    /** Broadcast STATE_UPDATE (snapshot GameState terkini) ke SEMUA client. Observer notify. */
    public synchronized void broadcastState() {
        Message update = new Message(MessageType.STATE_UPDATE, gameState);
        for (ClientHandler client : clients) {
            client.sendMessage(update);
        }
    }

    /** Broadcast pesan CHAT ke SEMUA client (termasuk pengirim, supaya UI chat konsisten). */
    public synchronized void broadcastChat(String senderName, String text) {
        Message chat = new Message(MessageType.CHAT, text, senderName);
        for (ClientHandler client : clients) {
            client.sendMessage(chat);
        }
    }

    /** Broadcast END (game over) dengan status akhir ke semua client. */
    public synchronized void broadcastEnd() {
        Message end = new Message(MessageType.END, gameState.getStatus());
        for (ClientHandler client : clients) {
            client.sendMessage(end);
        }
    }

    /** Dipanggil ClientHandler saat koneksi client putus (error/DISCONNECT).
     *  Kalau game sudah berakhir (RESIGNATION/TIMEOUT/DRAW/dsb. - mis. pemain
     *  menekan "Kembali ke Menu Utama" yang mengirim RESIGN lalu disconnect),
     *  status akhir JANGAN ditimpa jadi DISCONNECTED dan lawan tidak perlu
     *  dikirimi ERROR lagi - cukup hapus client yang putus secara diam-diam. */
    public synchronized void handleDisconnect(ClientHandler handler) {
        clients.remove(handler);
        GameStatus status = gameState.getStatus();
        boolean gameStillActive = status == GameStatus.PLAYING
                || status == GameStatus.CHECK
                || status == GameStatus.WAITING_FOR_PLAYER;
        if (!gameStillActive) {
            log(handler.getAssignedColor() + " terputus setelah game berakhir (" + status + "), status akhir dipertahankan.");
            return;
        }
        if (gameClock != null) gameClock.stop();
        gameState.setStatus(GameStatus.DISCONNECTED);
        log(handler.getAssignedColor() + " terputus dari server.");

        Message notice = new Message(MessageType.ERROR,
                "Lawan (" + handler.getAssignedColor() + ") terputus dari permainan.");
        for (ClientHandler client : clients) {
            client.sendMessage(notice);
        }
    }

    private void log(String msg) {
        System.out.println("[GameServer] " + msg);
    }
}
