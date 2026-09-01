package com.lanchess.model;

import java.io.Serializable;

/**
 * Tipe pesan dalam protokol komunikasi Client <-> Server.
 *
 * Payload yang dibawa masing-masing tipe (lihat Message.payload):
 *   JOIN          : (client -> server) tidak butuh payload, atau String nama pemain (opsional)
 *   ASSIGN_COLOR  : (server -> client) PlayerColor - warna yang di-assign ke client ini
 *   STATE_UPDATE  : (server -> client) GameState - snapshot lengkap state permainan terbaru
 *   MOVE          : (client -> server) Move - langkah yang ingin dilakukan pemain
 *   MOVE_REJECTED : (server -> client) String - alasan kenapa move ditolak (ilegal)
 *   CHAT          : (dua arah) String - isi pesan chat
 *   END           : (server -> client) GameStatus - hasil akhir permainan (CHECKMATE/STALEMATE/DRAW)
 *   ERROR         : (dua arah) String - pesan error umum
 *   DISCONNECT    : (dua arah) tidak butuh payload - pemberitahuan client keluar
 */
public enum MessageType implements Serializable {
    JOIN,
    ASSIGN_COLOR,
    STATE_UPDATE,
    MOVE,
    MOVE_REJECTED,
    CHAT,
    END,
    ERROR,
    DISCONNECT
}
