package com.lanchess.server;

import com.lanchess.model.GameState;
import com.lanchess.model.GameStatus;
import com.lanchess.model.Message;
import com.lanchess.model.MessageType;
import com.lanchess.model.Move;
import com.lanchess.model.PieceType;
import com.lanchess.model.PlayerColor;
import com.lanchess.model.TimeControl;
import com.lanchess.client.NetworkClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Tes alur LAN asli headless (tanpa JavaFX): server nyata + 2 NetworkClient nyata
 * via socket localhost. Mensimulasikan: host & joiner connect, tunggu PLAYING,
 * client putih kirim langkah pion e2-e4, pastikan tidak ada MOVE_REJECTED dan
 * client hitam menerima STATE_UPDATE berisi pion di e4.
 */
class LanTwoClientFlowTest {

    private static final int TEST_PORT = 15555;

    private static GameState latestState(ConcurrentLinkedQueue<Message> inbox) {
        GameState found = null;
        for (Message m : inbox) {
            if (m.getType() == MessageType.STATE_UPDATE) {
                found = m.getPayloadAs(GameState.class);
            }
        }
        return found;
    }

    private static boolean hasRejected(ConcurrentLinkedQueue<Message> inbox) {
        return inbox.stream().anyMatch(m -> m.getType() == MessageType.MOVE_REJECTED);
    }

    @Test
    @DisplayName("Alur LAN 2 client: pion putih e2-e4 diterima server dan ter-broadcast")
    void twoClientsPawnMoveEndToEnd() throws Exception {
        GameServer server = new GameServer();
        server.configure(TimeControl.UNLIMITED, PlayerColor.WHITE);
        Thread serverThread = new Thread(() -> server.start(TEST_PORT), "TestServer");
        serverThread.setDaemon(true);
        serverThread.start();
        Thread.sleep(500);

        ConcurrentLinkedQueue<Message> whiteInbox = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Message> blackInbox = new ConcurrentLinkedQueue<>();
        PlayerColor[] whiteAssigned = new PlayerColor[1];
        PlayerColor[] blackAssigned = new PlayerColor[1];

        NetworkClient whiteClient = new NetworkClient();
        whiteClient.connect("localhost", TEST_PORT, m -> {
            if (m.getType() == MessageType.ASSIGN_COLOR) whiteAssigned[0] = m.getPayloadAs(PlayerColor.class);
            else whiteInbox.add(m);
        });

        NetworkClient blackClient = new NetworkClient();
        blackClient.connect("localhost", TEST_PORT, m -> {
            if (m.getType() == MessageType.ASSIGN_COLOR) blackAssigned[0] = m.getPayloadAs(PlayerColor.class);
            else blackInbox.add(m);
        });

        // Tunggu kedua client menerima STATE_UPDATE berstatus PLAYING
        GameState whiteState = null;
        GameState blackState = null;
        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline) {
            whiteState = latestState(whiteInbox);
            blackState = latestState(blackInbox);
            if (whiteState != null && whiteState.getStatus() == GameStatus.PLAYING
                    && blackState != null && blackState.getStatus() == GameStatus.PLAYING) {
                break;
            }
            Thread.sleep(100);
        }
        System.out.println("ASSIGN white=" + whiteAssigned[0] + " black=" + blackAssigned[0]);
        System.out.println("whiteState=" + (whiteState == null ? "null" : whiteState.getStatus() + " turn=" + whiteState.getCurrentTurn()));
        System.out.println("blackState=" + (blackState == null ? "null" : blackState.getStatus() + " turn=" + blackState.getCurrentTurn()));

        assertEquals(PlayerColor.WHITE, whiteAssigned[0], "Client pertama (host) harus WHITE");
        assertEquals(PlayerColor.BLACK, blackAssigned[0], "Client kedua harus BLACK");
        assertNotNull(whiteState, "Client putih tidak pernah menerima STATE_UPDATE!");
        assertEquals(GameStatus.PLAYING, whiteState.getStatus(), "Server tidak pernah masuk PLAYING (client kedua tidak terdaftar?)");
        assertNotNull(blackState);

        // Simulasi klik UI client putih: ambil legal moves pion e2 lalu kirim e2-e4
        var legal = MoveValidator.getLegalMoves(whiteState, 6, 4);
        System.out.println("Legal e2 di client: " + legal);
        assertTrue(!legal.isEmpty(), "Pion e2 tidak punya legal moves di state client!");
        Move toSend = legal.stream()
                .filter(m -> m.getToRow() == 4 && m.getToCol() == 4).findFirst()
                .orElseThrow(() -> new AssertionError("e2-e4 tidak ada di legal moves"));
        whiteClient.sendMessage(new Message(MessageType.MOVE, toSend, PlayerColor.WHITE.name()));

        // Tunggu broadcast hasil
        deadline = System.currentTimeMillis() + 8000;
        GameState afterBlack = null;
        while (System.currentTimeMillis() < deadline) {
            afterBlack = latestState(blackInbox);
            if (afterBlack != null && afterBlack.getPieceAt(4, 4) != null
                    && afterBlack.getPieceAt(4, 4).getType() == PieceType.PAWN) {
                break;
            }
            if (hasRejected(whiteInbox)) break;
            Thread.sleep(100);
        }

        whiteInbox.stream().filter(m -> m.getType() == MessageType.MOVE_REJECTED)
                .forEach(m -> System.out.println("DITOLAK: " + m.getPayloadAs(String.class)));
        if (afterBlack != null) {
            System.out.println("State hitam terakhir: status=" + afterBlack.getStatus()
                    + " turn=" + afterBlack.getCurrentTurn()
                    + " e4=" + afterBlack.getPieceAt(4, 4));
        }

        if (hasRejected(whiteInbox)) {
            fail("Server MENOLAK langkah pion e2-e4 yang legal!");
        }
        assertNotNull(afterBlack, "Client hitam tidak menerima broadcast state baru!");
        assertNotNull(afterBlack.getPieceAt(4, 4), "Pion tidak sampai ke e4 di state broadcast!");
        assertEquals(PlayerColor.BLACK, afterBlack.getCurrentTurn(), "Giliran harus pindah ke BLACK");

        whiteClient.disconnect();
        blackClient.disconnect();
        server.stop();
        assertTrue(true);
    }
}
