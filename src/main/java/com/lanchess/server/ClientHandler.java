package com.lanchess.server;

import com.lanchess.model.GameState;
import com.lanchess.model.GameStatus;
import com.lanchess.model.Message;
import com.lanchess.model.MessageType;
import com.lanchess.model.Move;
import com.lanchess.model.PlayerColor;

import java.io.EOFException;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.util.Optional;

/**
 * Satu thread per client yang terhubung. Bertanggung jawab:
 *  - membuka stream ke client ini
 *  - loop membaca Message yang dikirim client (MOVE, CHAT, DISCONNECT, ...)
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

    private final Socket socket;
    private final GameServer server;
    private final PlayerColor assignedColor;

    private ObjectOutputStream out;
    private ObjectInputStream in;
    private volatile boolean connected = true;

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
            closeConnection();
        }
    }

    private void handleMessage(Message message) {
        switch (message.getType()) {
            case MOVE -> handleMove(message);
            case CHAT -> server.broadcastChat(assignedColor.name(), message.getPayloadAs(String.class));
            case DISCONNECT -> connected = false;
            default -> log("Tipe pesan tak terduga dari client: " + message.getType());
        }
    }

    private void handleMove(Message message) {
        GameState state = server.getGameState();

        // Hanya boleh jalan kalau memang giliran warna ini & game masih berjalan
        boolean gameActive = state.getStatus() == GameStatus.PLAYING || state.getStatus() == GameStatus.CHECK;
        if (!gameActive || state.getCurrentTurn() != assignedColor) {
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

        if (state.getStatus() == GameStatus.CHECKMATE || state.getStatus() == GameStatus.STALEMATE) {
            server.broadcastEnd();
        }
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
