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
 *   RESIGN        : (client -> server) tidak butuh payload - pemain mengundurkan diri
 *   DRAW_OFFER    : (client -> server -> diteruskan ke lawan) tidak butuh payload - tawaran seri
 *   DRAW_ACCEPT   : (client -> server) tidak butuh payload - lawan menerima tawaran seri
 *   DRAW_DECLINE  : (client -> server -> diteruskan ke penawar) tidak butuh payload - tawaran seri ditolak
 *   END           : (server -> client) GameStatus - hasil akhir permainan (CHECKMATE/STALEMATE/DRAW/TIMEOUT/RESIGNATION)
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
    RESIGN,
    DRAW_OFFER,
    DRAW_ACCEPT,
    DRAW_DECLINE,
    END,
    ERROR,
    DISCONNECT
}
