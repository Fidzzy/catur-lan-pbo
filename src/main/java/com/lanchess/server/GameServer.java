package com.lanchess.server;

import com.lanchess.model.DrawReason;
import com.lanchess.model.GameState;
import com.lanchess.model.GameStatus;
import com.lanchess.model.Message;
import com.lanchess.model.MessageType;
import com.lanchess.model.PlayerColor;
import com.lanchess.model.TimeControl;
import com.lanchess.model.GameMode;
import com.lanchess.model.QuizReward;

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

    /** Counter langkah penuh (satu full move = Putih + Hitam). Dipakai untuk trigger kuis. */
    private int fullMoveCounter = 0;

    /** Manajer kuis. Non-null hanya kalau gameMode == QUIZ. */
    private QuizManager quizManager;

    private GameClock gameClock;

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

    /** Sub-mode permainan (CLASSIC default - backward compatible). Di-set host via configure(). */
    private GameMode gameMode = GameMode.CLASSIC;

    /** Warna pemain yang sedang menawarkan seri, null kalau tidak ada tawaran pending. */
    private PlayerColor pendingDrawOfferFrom;

    /** Warna pemain yang sedang mengajak rematch, null kalau tidak ada ajakan pending. */
    private PlayerColor pendingRematchOfferFrom;

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
        configure(timeControl, hostColor, GameMode.CLASSIC);
    }

    /**
     * Versi lengkap - sekaligus menentukan GameMode room (CLASSIC/QUIZ).
     * Dipanggil oleh HostSetupController di sisi host. Kalau gameMode null,
     * dianggap CLASSIC (backward compatible).
     */
    public void configure(TimeControl timeControl, PlayerColor hostColor, GameMode gameMode) {
        gameState.setTimeControl(timeControl);
        this.hostColor = (hostColor != null) ? hostColor
                : (Math.random() < 0.5 ? PlayerColor.WHITE : PlayerColor.BLACK);
        this.gameMode = (gameMode != null) ? gameMode : GameMode.CLASSIC;
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
                        + gameState.getTimeControl() + ", Mode=" + gameMode);
                gameState.setStatus(GameStatus.PLAYING);

                if (gameMode == GameMode.QUIZ) {
                    quizManager = new QuizManager(this);
                }

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

    /**
     * Dipanggil ClientHandler setelah broadcastState tiap kali ada move
     * sukses. Counter fullMoveCounter HANYA bertambah kalau yang move BLACK
     * (satu full move = Putih + Hitam). Trigger kuis hanya berlaku untuk
     * gameMode QUIZ.
     *
     * Aturan trigger (spesifikasi):
     *   - fullMoveCounter % 10 == 0 (10, 20, 30, ...): WAJIB munculkan kuis.
     *   - fullMoveCounter >= 50: 50% chance.
     *   - fullMoveCounter >= 30: 40% chance.
     *   - fullMoveCounter >= 15: 20% chance.
     *   - selain itu: tidak ada kuis.
     *
     * @param moverColor warna yang BARU SAJA selesai move (BLACK = mungkin trigger).
     */
    public synchronized void maybeTriggerQuiz(PlayerColor moverColor) {
        if (gameMode != GameMode.QUIZ) return;
        if (moverColor != PlayerColor.BLACK) return;   // kuis hanya setelah Hitam selesai
        if (quizManager == null) return;
        if (quizManager.isQuizActive()) return;        // jangan tumpuk kuis

        fullMoveCounter++;
        boolean guaranteed = (fullMoveCounter > 0 && fullMoveCounter % 10 == 0);
        boolean chance = false;
        if (!guaranteed) {
            double p = 0.0;
            if (fullMoveCounter >= 50) p = 0.50;
            else if (fullMoveCounter >= 30) p = 0.40;
            else if (fullMoveCounter >= 15) p = 0.20;
            if (p > 0.0 && Math.random() < p) chance = true;
        }

        if (guaranteed || chance) {
            log("Trigger kuis (fullMoveCounter=" + fullMoveCounter
                    + ", " + (guaranteed ? "GUARANTEED" : "chance") + ")");
            pauseClockForQuiz();
            quizManager.startQuiz();
        }
    }

    /** Dipanggil ClientHandler saat menerima QUIZ_ANSWER dari client. */
    public synchronized void submitQuizAnswer(PlayerColor color, int answerIndex) {
        if (quizManager == null) return;
        quizManager.submitAnswer(color, answerIndex);
    }

    /** Broadcast generik ke semua client (dipakai QuizManager). */
    public synchronized void broadcastToAll(Message message) {
        for (ClientHandler client : clients) {
            client.sendMessage(message);
        }
    }

    /** Pause jam catur sebelum kuis. Placeholder - butuh GameClock.pause(). */
    public synchronized void pauseClockForQuiz() {
        if (gameClock != null) gameClock.pause();   // <-- perlu GameClock.java
    }

    /** Resume jam catur setelah kuis. Placeholder - butuh GameClock.resume(). */
    public synchronized void resumeClockAfterQuiz() {
        if (gameClock != null) gameClock.resume();  // <-- perlu GameClock.java
    }

    /** Tambah waktu ke pemain tertentu (reward TIME_BONUS kuis). */
    public synchronized void addTimeBonus(PlayerColor color, long bonusMs) {
        if (gameClock == null) return;
        gameClock.addTime(color, bonusMs);          // <-- perlu GameClock.java
        log("Time bonus +" + (bonusMs / 1000) + "s untuk " + color);
        broadcastState();                           // update tampilan jam di kedua client
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

    // =========================================================================
    // Rematch (main lagi setelah game over)
    // =========================================================================

    /**
     * Teruskan ajakan rematch dari sender ke lawannya, dan catat sebagai
     * pending. Kalau ternyata LAWAN juga sudah mengajak lebih dulu (kedua
     * pemain menekan "Main Lagi" hampir bersamaan), langsung mulai rematch
     * tanpa menunggu accept eksplisit.
     */
    public synchronized void relayRematchOffer(ClientHandler sender) {
        if (isGameActive()) {
            log("Ajakan rematch dari " + sender.getAssignedColor() + " diabaikan (game masih berjalan).");
            return;
        }
        PlayerColor from = sender.getAssignedColor();
        if (pendingRematchOfferFrom != null && pendingRematchOfferFrom != from) {
            log("Rematch disepakati (kedua pemain mengajak).");
            startRematch();
            return;
        }
        pendingRematchOfferFrom = from;
        Message offer = new Message(MessageType.REMATCH_OFFER, null, from.name());
        for (ClientHandler client : clients) {
            if (client != sender) client.sendMessage(offer);
        }
    }

    /** Teruskan notifikasi penolakan rematch ke lawan (si pengajak), dan hapus status pending. */
    public synchronized void relayRematchDecline(ClientHandler sender) {
        pendingRematchOfferFrom = null;
        Message decline = new Message(MessageType.REMATCH_DECLINE, null, sender.getAssignedColor().name());
        for (ClientHandler client : clients) {
            if (client != sender) client.sendMessage(decline);
        }
    }

    /**
     * Finalisasi rematch kalau memang ada ajakan pending dari LAWAN
     * acceptingColor.
     *
     * @return true kalau ajakan valid & permainan baru berhasil dimulai.
     */
    public synchronized boolean acceptRematchIfPending(PlayerColor acceptingColor) {
        if (isGameActive()) return false;
        if (pendingRematchOfferFrom == null || pendingRematchOfferFrom == acceptingColor) {
            return false;
        }
        log("Rematch disepakati (diterima oleh " + acceptingColor + ").");
        startRematch();
        return true;
    }

    /**
     * Mulai permainan baru di atas koneksi yang SUDAH ada: reset state
     * (papan awal, giliran WHITE, jam kembali penuh), restart jam kalau
     * pakai timer, lalu broadcast STATE_UPDATE + REMATCH_START supaya kedua
     * client me-reset UI-nya. Warna kedua pemain TIDAK berubah.
     */
    private synchronized void startRematch() {
        pendingRematchOfferFrom = null;
        pendingDrawOfferFrom = null;
        if (gameClock != null) gameClock.stop();
        gameState.reset();

        // reset state kuis
        fullMoveCounter = 0;
        if (quizManager != null) {
            quizManager = new QuizManager(this);   // instance baru = state bersih
        }

        log("Rematch dimulai! TimeControl=" + gameState.getTimeControl());
        if (!gameState.getTimeControl().isUnlimited()) {
            gameClock = new GameClock(gameState, this::handleTimeout);
            gameClock.startTurn();
        }
        broadcastState();
        Message start = new Message(MessageType.REMATCH_START, gameState);
        for (ClientHandler client : clients) {
            client.sendMessage(start);
        }
    }

    /** True kalau permainan sedang berjalan (rematch/draw-accept hanya valid setelah game over). */
    private synchronized boolean isGameActive() {
        GameStatus status = gameState.getStatus();
        return status == GameStatus.PLAYING
                || status == GameStatus.CHECK
                || status == GameStatus.WAITING_FOR_PLAYER;
    }

    /** True kalau ada kuis aktif - dipakai ClientHandler sebagai guard move. */
    public synchronized boolean isQuizActive() {
        return quizManager != null && quizManager.isQuizActive();
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
        // Kabari QuizManager kalau disconnect terjadi di tengah kuis
        if (quizManager != null && quizManager.isQuizActive()) {
            quizManager.onPlayerDisconnect(handler.getAssignedColor());
        }
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

    /**
     * Mode permainan yang dikonfigurasi host. Dipakai ClientHandler untuk
     * memvalidasi SET_MODE yang dikirim client (harus sama).
     */
    public synchronized GameMode getGameMode() {
        return gameMode;
    }
}

