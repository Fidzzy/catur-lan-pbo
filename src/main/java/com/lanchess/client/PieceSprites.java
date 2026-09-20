package com.lanchess.client;

import com.lanchess.model.PieceType;
import com.lanchess.model.PlayerColor;
import javafx.scene.image.Image;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Memuat set gambar bidak (Cburnett, gaya lichess) dari resources
 * {@code /com/lanchess/client/pieces/*.png} dan menyimpannya di cache.
 *
 * Kalau file gambar tidak ditemukan (mis. resources belum ikut di-copy),
 * {@link #get} mengembalikan {@code null} dan {@link BoardView} otomatis
 * fallback ke glyph unicode - papan tetap bisa digambar.
 */
public final class PieceSprites {

    private static final Map<String, Image> CACHE = new HashMap<>();
    private static boolean loaded = false;

    private PieceSprites() {
    }

    /** @return sprite bidak, atau null kalau tidak tersedia (pakai fallback unicode). */
    public static Image get(PlayerColor color, PieceType type) {
        if (!loaded) {
            loaded = true;
            loadAll();
        }
        return CACHE.get(key(color, type));
    }

    /** True kalau minimal 1 sprite berhasil dimuat. */
    public static boolean available() {
        get(PlayerColor.WHITE, PieceType.PAWN);
        return !CACHE.isEmpty();
    }

    private static void loadAll() {
        for (PlayerColor color : PlayerColor.values()) {
            for (PieceType type : PieceType.values()) {
                String path = "/com/lanchess/client/pieces/" + fileName(color, type);
                try (InputStream in = PieceSprites.class.getResourceAsStream(path)) {
                    if (in != null) {
                        CACHE.put(key(color, type), new Image(in));
                    }
                } catch (Exception ignored) {
                    // Biarkan kosong - BoardView akan fallback ke unicode.
                }
            }
        }
    }

    private static String key(PlayerColor color, PieceType type) {
        return color.name() + "_" + type.name();
    }

    private static String fileName(PlayerColor color, PieceType type) {
        return color.name().toLowerCase() + "-" + type.name().toLowerCase() + ".png";
    }
}
