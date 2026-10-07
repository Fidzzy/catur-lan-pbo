package com.lanchess.server;

import com.lanchess.model.GameState;
import com.lanchess.model.PlayerColor;

import java.util.Timer;
import java.util.TimerTask;
import java.util.function.Consumer;

/**
 * Jam catur server-authoritative untuk mode LAN (dan dipakai pola yang sama
 * secara lokal di mode Bot). Prinsip desain:
 *
 *  - Deduksi waktu HANYA terjadi di boundary giliran (saat move dieksekusi),
 *    dihitung dari selisih waktu asli System.currentTimeMillis(), BUKAN
 *    lewat ticker periodik - supaya tidak ada akumulasi drift jangka panjang.
 *  - Timeout (kehabisan waktu tanpa sempat jalan) dideteksi lewat SATU
 *    TimerTask terjadwal PERSIS di sisa waktu pemain yang sedang jalan.
 *    Begitu pemain itu jalan (onMoveMade dipanggil), task lama dibatalkan
 *    dan dijadwalkan ulang untuk pemain berikutnya - tidak pernah ada lebih
 *    dari satu task aktif dalam satu waktu.
 *  - Client TIDAK menghitung waktu otoritatif sendiri - hanya menampilkan
 *    interpolasi visual dari nilai terakhir yang diterima lewat STATE_UPDATE
 *    (lihat ClockDisplay di client), supaya server tetap satu-satunya
 *    sumber kebenaran tanpa perlu broadcast tiap detik.
 *
 * Thread-safety: semua method disinkronisasi pada monitor instance ini,
 * supaya race antara "move baru saja tiba" dan "timer timeout baru saja
 * bunyi" ditangani dengan aman (salah satu menang, tidak keduanya diproses).
 */
public class GameClock {

    private final GameState state;
    private final Consumer<PlayerColor> onTimeout;
    private final Timer timer;

    private TimerTask pendingTask;
    private long turnStartedAtMillis;
    private volatile boolean active = false;
    /** True kalau jam sedang dijeda (mis. selama kuis QUIZ berlangsung). */
    private boolean paused = false;

    /** Timestamp kapan pause() dipanggil - dipakai resume() untuk menggeser turnStartedAtMillis. */
    private long pauseStartedAtMillis;

    public GameClock(GameState state, Consumer<PlayerColor> onTimeout) {
        this.state = state;
        this.onTimeout = onTimeout;
        this.timer = new Timer("GameClock", true); // daemon, tidak menahan JVM exit
    }

    /** Mulai jam untuk giliran pemain SAAT INI. Dipanggil sekali saat game dimulai. */
    public synchronized void startTurn() {
        if (state.getTimeControl().isUnlimited()) return;
        active = true;
        turnStartedAtMillis = System.currentTimeMillis();
        schedulePendingTimeout(state.getCurrentTurn());
    }

    /**
     * Dipanggil TEPAT SETELAH sebuah move berhasil dieksekusi (giliran sudah
     * berpindah di GameState). Menghitung waktu yang terpakai pemain yang
     * BARU SAJA jalan (moverColor), lalu menjadwalkan timer baru untuk
     * pemain berikutnya (state.getCurrentTurn() yang sudah ter-update).
     */
    public synchronized void onMoveMade(PlayerColor moverColor) {
        if (!active || paused || state.getTimeControl().isUnlimited()) return;
        cancelPending();

        long elapsed = System.currentTimeMillis() - turnStartedAtMillis;
        state.deductElapsed(moverColor, elapsed);

        turnStartedAtMillis = System.currentTimeMillis();
        schedulePendingTimeout(state.getCurrentTurn());
    }

    /** Hentikan jam sepenuhnya (dipanggil saat game berakhir apapun sebabnya). */
    public synchronized void stop() {
        active = false;
        paused = false;
        cancelPending();
    }

    /**
     * Jeda jam sementara tanpa menghilangkan sisa waktu. Selama paused,
     * timer timeout dibatalkan (tidak akan bunyi), dan onMoveMade() tidak
     * diizinkan menghitung elapsed (dijaga oleh flag paused).
     *
     * Idempotent: pause() berturut-turut hanya berefek sekali.
     *
     * Dipakai QuizManager via GameServer.pauseClockForQuiz().
     */
    public synchronized void pause() {
        if (!active || paused) return;
        if (state.getTimeControl().isUnlimited()) return;
        cancelPending();
        paused = true;
        pauseStartedAtMillis = System.currentTimeMillis();
    }

    /**
     * Lanjutkan jam dari sisa waktu. Kita GESER turnStartedAtMillis sejauh
     * durasi pause supaya waktu selama kuis TIDAK dihitung sebagai waktu
     * berpikir pemain (metode boundary-deduction: elapsed hanya dihitung
     * dari turnStartedAtMillis ke onMoveMade()).
     *
     * Idempotent: resume() saat tidak paused tidak berefek.
     */
    public synchronized void resume() {
        if (!paused) return;
        if (state.getTimeControl().isUnlimited()) {
            paused = false;
            return;
        }
        long pausedDuration = System.currentTimeMillis() - pauseStartedAtMillis;
        turnStartedAtMillis += pausedDuration;   // kompensasi
        paused = false;
        schedulePendingTimeout(state.getCurrentTurn());   // jadwalkan ulang timeout
    }

    /**
     * Tambah sisa waktu pemain tertentu (reward TIME_BONUS kuis).
     * Kalau yang dapat bonus adalah pemain yang SEDANG giliran, timeout
     * yang sedang terjadwal harus dijadwalkan ulang supaya tidak bunyi
     * terlalu cepat.
     */
    public synchronized void addTime(PlayerColor color, long bonusMs) {
        if (state.getTimeControl().isUnlimited()) return;
        long current = state.getRemainingMillis(color);
        state.setRemainingMillis(color, current + bonusMs);

        if (active && !paused && state.getCurrentTurn() == color) {
            // Pemain ini sedang giliran - reschedule timeout dengan durasi baru.
            // Kita juga harus mem-flush elapsed yang sudah berjalan supaya
            // tidak ada dobel-hitung saat onMoveMade() nanti.
            long elapsed = System.currentTimeMillis() - turnStartedAtMillis;
            state.deductElapsed(color, elapsed);
            turnStartedAtMillis = System.currentTimeMillis();
            cancelPending();
            schedulePendingTimeout(color);
        }
    }

    /** Berapa lama giliran saat ini sudah berjalan (dipakai UI lokal untuk interpolasi visual countdown). */
    public synchronized long getElapsedInCurrentTurn() {
        return active ? System.currentTimeMillis() - turnStartedAtMillis : 0;
    }

    private void schedulePendingTimeout(PlayerColor colorToMove) {
        long remaining = state.getRemainingMillis(colorToMove);
        if (remaining <= 0) {
            active = false;
            onTimeout.accept(colorToMove);
            return;
        }
        pendingTask = new TimerTask() {
            @Override
            public void run() {
                synchronized (GameClock.this) {
                    if (!active) return; // sudah dibatalkan (move keburu masuk) atau sudah stop()
                    state.setRemainingMillis(colorToMove, 0);
                    active = false;
                    onTimeout.accept(colorToMove);
                }
            }
        };
        timer.schedule(pendingTask, remaining);
    }

    private void cancelPending() {
        if (pendingTask != null) {
            pendingTask.cancel();
            pendingTask = null;
        }
    }
}
