package com.lanchess.server;

import com.lanchess.model.GameState;
import com.lanchess.model.GameStatus;
import com.lanchess.model.Message;
import com.lanchess.model.MessageType;
import com.lanchess.model.Move;
import com.lanchess.model.PlayerColor;
import com.lanchess.model.GameMode;

import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.Optional;
import java.util.Set;

/**
 * Satu thread per client yang terhubung. Bertanggung jawab:
 *  - membuka stream ke client ini
 *  - loop membaca Message yang dikirim client (MOVE, CHAT, RESIGN, DRAW_OFFER, ...)
 *  - mendelegasikan validasi/eksekusi MOVE ke MoveValidator (server = single
 *    source of truth, client tidak pernah dipercaya untuk menentukan legal/tidaknya move)
 *  - memberitahu GameServer untuk broadcast setiap kali state berubah
 *
 * CATATAN URUTAN STREAM (critical):
 *   ObjectOutputStream HARUS dibuat & di-flush() SEBELUM ObjectInputStream,
 *   di KEDUA sisi (server maupun client). ObjectInputStream constructor
 *   memblokir menunggu header stream dari lawan; kalau kedua sisi sama-sama
 *   membuat ObjectInputStream duluan, keduanya saling menunggu -> deadlock.
 *   Karena NetworkClient (sisi client) juga membuat output dulu, urutan ini
 *   konsisten di kedua sisi.
 */
public class ClientHandler implements Runnable {

    /** Status yang berarti "game sudah selesai" - dipakai berkali-kali untuk guard aksi RESIGN/DRAW/MOVE. */
    private static final Set<GameStatus> ACTIVE_STATUSES = Set.of(GameStatus.PLAYING, GameStatus.CHECK);

    private final Socket socket;
    private final GameServer server;
    private final PlayerColor assignedColor;

    private ObjectOutputStream out;
    private ObjectInputStream in;
    private volatile boolean connected = true;
    /** Flag idempotency: SET_MODE hanya diproses sekali per koneksi. */
    private boolean modeValidated = false;

    /**
     * Dihitung mundur sekali, TEPAT SETELAH kedua stream (in & out) selesai
     * disiapkan di run(). GameServer.start() menunggu ini (awaitReady())
     * sebelum melanjutkan ke broadcastState() awal - mencegah race dimana
     * broadcastState() mencoba menulis ke ClientHandler yang field `out`-nya
     * belum sempat diinisialisasi (NullPointerException).
     */
    private final java.util.concurrent.CountDownLatch readyLatch = new java.util.concurrent.CountDownLatch(1);

    public ClientHandler(Socket socket, GameServer server, PlayerColor assignedColor) {
        this.socket = socket;
        this.server = server;
        this.assignedColor = assignedColor;
    }

    @Override
    public void run() {
        try {
            // Output SEBELUM input - lihat catatan urutan stream di javadoc class ini
            out = new ObjectOutputStream(socket.getOutputStream());
            out.flush();
            in = new ObjectInputStream(socket.getInputStream());
            readyLatch.countDown(); // stream siap - GameServer.start() yang sedang awaitReady() boleh lanjut

            // Beri tahu client ini warnanya, lalu kirim snapshot state awal
            sendMessage(new Message(MessageType.ASSIGN_COLOR, assignedColor));
            sendMessage(new Message(MessageType.STATE_UPDATE, server.getGameState()));

            while (connected) {
                Message received = (Message) in.readObject();
                handleMessage(received);
            }

        } catch (EOFException | java.net.SocketException e) {
            log("Koneksi ditutup oleh client (" + e.getClass().getSimpleName() + ")");
        } catch (IOException | ClassNotFoundException e) {
            log("Error koneksi: " + e.getMessage());
        } finally {
            readyLatch.countDown(); // jaga-jaga: kalau stream setup GAGAL, jangan sampai awaitReady() menunggu selamanya
            closeConnection();
        }
    }

    /** Blokir sampai stream in/out ClientHandler ini selesai disiapkan (atau gagal). Dipanggil GameServer.start(). */
    public void awaitReady() throws InterruptedException {
        readyLatch.await();
    }

    private void handleMessage(Message message) {
        switch (message.getType()) {
            case SET_MODE -> handleSetMode(message);
            case MOVE -> handleMove(message);
            case CHAT -> server.broadcastChat(assignedColor.name(), message.getPayloadAs(String.class));
            case RESIGN -> handleResign();
            case DRAW_OFFER -> handleDrawOffer();
            case DRAW_ACCEPT -> handleDrawAccept();
            case DRAW_DECLINE -> handleDrawDecline();
            case REMATCH_OFFER -> handleRematchOffer();
            case REMATCH_ACCEPT -> handleRematchAccept();
            case REMATCH_DECLINE -> handleRematchDecline();
            case QUIZ_ANSWER -> handleQuizAnswer(message);
            case DISCONNECT -> connected = false;
            default -> log("Tipe pesan tak terduga dari client: " + message.getType());
        }
    }

    /**
     * Validasi mode permainan yang dikirim client. Server otoritatif:
     * mode diambil dari GameServer.getGameMode() (yang di-set host via
     * configure()). Kalau mismatch, kirim ERROR lalu tutup koneksi.
     *
     * Idempotent - SET_MODE kedua dan seterusnya diabaikan (mencegah
     * client nakal mengganti mode di tengah permainan).
     */
    private void handleSetMode(Message message) {
        if (modeValidated) {
            log("SET_MODE diabaikan (sudah divalidasi sebelumnya).");
            return;
        }
        GameMode clientMode = message.getPayloadAs(GameMode.class);
        if (clientMode == null) clientMode = GameMode.CLASSIC; // defensive
        GameMode serverMode = server.getGameMode();

        if (clientMode != serverMode) {
            log("Mode mismatch: client=" + clientMode + ", server=" + serverMode + ". Memutus koneksi.");
            sendMessage(new Message(MessageType.ERROR,
                    "Mode permainan tidak cocok. Host memakai " + serverMode
                            + ", kamu memilih " + clientMode + "."));
            // Kirim DISCONNECT supaya client tahu ini bukan error biasa
            sendMessage(new Message(MessageType.DISCONNECT, null));
            connected = false;   // while-loop di run() akan keluar, finally -> closeConnection()
            return;
        }
        modeValidated = true;
        log("Mode dikonfirmasi: " + clientMode);
    }

    private void handleMove(Message message) {
        GameState state = server.getGameState();

        // GUARD KUIS: kalau kuis sedang aktif, tolak semua move.
        // Ini melindungi invariant "kuis tidak mengubah giliran catur".
        if (server.isQuizActive()) {
            sendMessage(new Message(MessageType.MOVE_REJECTED,
                    "Kuis sedang berlangsung. Selesaikan kuis dulu."));
            return;
        }

        if (!ACTIVE_STATUSES.contains(state.getStatus()) || state.getCurrentTurn() != assignedColor) {
            sendMessage(new Message(MessageType.MOVE_REJECTED, "Bukan giliranmu, atau permainan sudah selesai."));
            return;
        }

        Move rawMove = message.getPayloadAs(Move.class);
        Optional<Move> legalMove = MoveValidator.findLegalMove(state, rawMove);

        if (legalMove.isEmpty()) {
            sendMessage(new Message(MessageType.MOVE_REJECTED, "Langkah ilegal."));
            return;
        }

        MoveValidator.executeMove(state, legalMove.get());
        server.notifyMoveMade(assignedColor);
        log("Move dieksekusi: " + legalMove.get() + " | status baru: " + state.getStatus());

        server.broadcastState();

        if (!ACTIVE_STATUSES.contains(state.getStatus())) {
            server.broadcastEnd();
        } else {
            // Trigger kuis setelah state ter-broadcast & game masih aktif.
            // Kalau game over di move ini, tidak ada kuis.
            server.maybeTriggerQuiz(assignedColor);
        }
    }

    /** Client menjawab soal kuis. Server yang menentukan valid/benar/waktu. */
    private void handleQuizAnswer(Message message) {
        Integer answerIndex = message.getPayloadAs(Integer.class);
        if (answerIndex == null || answerIndex < 0 || answerIndex > 3) {
            log("QUIZ_ANSWER tidak valid: " + answerIndex);
            return;
        }
        server.submitQuizAnswer(assignedColor, answerIndex);
    }

    /** Pemain mengundurkan diri. Berlaku kapan saja selama game masih berjalan, tidak harus giliran sendiri. */
    private void handleResign() {
        GameState state = server.getGameState();
        if (!ACTIVE_STATUSES.contains(state.getStatus())) return; // game sudah selesai, abaikan

        state.setLoserColor(assignedColor);
        state.setStatus(GameStatus.RESIGNATION);
        server.stopClock();
        log(assignedColor + " mengundurkan diri.");

        server.broadcastState();
        server.broadcastEnd();
    }

    /** Tawarkan seri ke lawan. Server hanya meneruskan (relay), tidak mengubah GameState sampai lawan accept. */
    private void handleDrawOffer() {
        GameState state = server.getGameState();
        if (!ACTIVE_STATUSES.contains(state.getStatus())) return;
        server.relayDrawOffer(this);
        log(assignedColor + " menawarkan seri.");
    }

    /** Lawan menerima tawaran seri yang sedang pending. */
    private void handleDrawAccept() {
        boolean finalized = server.finalizeDrawIfPending(assignedColor);
        if (finalized) {
            log("Seri disepakati (diterima oleh " + assignedColor + ").");
        }
    }

    /** Lawan menolak tawaran seri. Server meneruskan notifikasi penolakan ke penawar. */
    private void handleDrawDecline() {
        server.relayDrawDecline(this);
        log(assignedColor + " menolak tawaran seri.");
    }

    /** Ajakan main lagi (rematch). Hanya valid setelah game over - dicek di GameServer. */
    private void handleRematchOffer() {
        server.relayRematchOffer(this);
        log(assignedColor + " mengajak rematch.");
    }

    /** Lawan menerima ajakan rematch yang sedang pending. */
    private void handleRematchAccept() {
        boolean started = server.acceptRematchIfPending(assignedColor);
        if (started) {
            log("Rematch dimulai (diterima oleh " + assignedColor + ").");
        }
    }

    /** Lawan menolak ajakan rematch. Server meneruskan notifikasi penolakan ke pengajak. */
    private void handleRematchDecline() {
        server.relayRematchDecline(this);
        log(assignedColor + " menolak ajakan rematch.");
    }

    /**
     * Kirim Message ke client ini. synchronized supaya tidak ada dua thread
     * (mis. thread broadcast dari GameServer & thread run() ini sendiri)
     * menulis ke stream yang sama secara bersamaan dan merusak framing objek.
     *
     * out.reset() WAJIB dipanggil setelah setiap writeObject(GameState):
     * ObjectOutputStream melakukan caching referensi objek yang sudah pernah
     * dikirim. Karena GameState (board, Piece di dalamnya) di-MUTATE in-place
     * lalu dikirim ulang dengan reference yang SAMA, tanpa reset() client
     * akan menerima "handle" ke objek lama alih-alih data terbaru -> bug
     * papan tidak ter-update di client walau server sudah benar.
     */
    public synchronized void sendMessage(Message message) {
        if (!connected) return;
        try {
            out.writeObject(message);
            out.flush();
            out.reset();
        } catch (IOException e) {
            log("Gagal mengirim pesan ke client: " + e.getMessage());
            closeConnection();
        }
    }

    private void closeConnection() {
        if (!connected) return; // sudah pernah ditutup, jangan dobel-notify
        connected = false;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        server.handleDisconnect(this);
    }

    public PlayerColor getAssignedColor() {
        return assignedColor;
    }

    private void log(String msg) {
        System.out.println("[ClientHandler-" + assignedColor + "] " + msg);
    }
}
