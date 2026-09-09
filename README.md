# LAN Chess Arena

Game catur multiplayer via LAN — JavaFX GUI + TCP Socket murni (tanpa library networking eksternal) — plus mode single-player melawan **Stockfish** lewat protokol UCI. UI/UX mengikuti desain Figma kustom: dark gradient theme, panel kartu rounded, tombol pill, jam catur, dan panel riwayat langkah.

## Stack

- Java 21
- JavaFX 21.0.2
- Maven (`javafx-maven-plugin` untuk development, `maven-shade-plugin` untuk fat JAR)
- Networking: `java.net.Socket` + `ObjectOutputStream`/`ObjectInputStream`, port `5555`
- Bot: proses eksternal **Stockfish** via `ProcessBuilder` + protokol UCI mentah (tanpa library JNI/wrapper)
- Styling: JavaFX CSS (`theme.css`) — tidak ada framework UI eksternal

## Instalasi Stockfish (untuk mode vs Bot)

Stockfish **tidak dibundel** di dalam JAR (binary native beda per OS/arsitektur). Install salah satu:

```bash
# Ubuntu / Debian
sudo apt install stockfish

# macOS (Homebrew)
brew install stockfish

# Windows
# Unduh dari https://stockfishchess.org/download/ , extract, catat lokasi stockfish.exe
```

Aplikasi otomatis mendeteksi lokasi binary umum (`/usr/games/stockfish`, `/usr/local/bin/stockfish`, `/opt/homebrew/bin/stockfish`, atau langsung dari PATH sistem) lewat `StockfishLocator.autoDetect()`. Kalau gagal terdeteksi, field path di layar setup bisa diisi manual.

## Alur Layar (sesuai desain Figma)

```
MainMenuController (menu utama)
 ├─ "Play With Friend" ──► FriendModeController (pilih Host atau Join)
 │        ├─ "Play As Host" ──► HostSetupController (Timer + warna + Start)
 │        │                          └─ start GameServer ──► GameController (gameplay)
 │        └─ isi IP + "Join Game" ──► connect langsung ──► GameController (gameplay)
 │             (host sudah menentukan time control & warna sendiri di layarnya;
 │              joiner tinggal terima, tidak ada layar setup tambahan)
 │
 └─ "Play VS Bot" ──► BotSetupController (Select ELO + Timer + warna + Start)
                              └─ start StockfishEngine ──► BotGameController (gameplay)
```

## Cara Menjalankan

### 1. Development (satu perintah, langsung buka GUI)

```bash
mvn javafx:run
```

Ini membuka **Menu Utama** dengan dua pilihan mode:
- **Play With Friend** — lanjut ke layar Host/Join. Host memilih Timer & warna sendiri sebelum server dinyalakan; joiner cukup masukkan IP host.
- **Play VS Bot** — lanjut ke layar setup lengkap: pilih rating **ELO** lawan (1320–3190, dipetakan ke opsi UCI `UCI_Elo` Stockfish asli), **Timer**, warna sendiri, dan path Stockfish (biasanya auto-terisi).

Untuk uji coba 2 pemain LAN di **satu laptop**: jalankan `mvn javafx:run` dua kali (dua proses terpisah) — yang pertama klik "Play As Host", yang kedua masukkan `localhost` lalu "Join Game".

Untuk main sungguhan via LAN: satu laptop jadi host, cari IP LAN-nya (`ipconfig` di Windows / `ifconfig`/`ip addr` di Mac/Linux), lalu laptop kedua masukkan IP itu di kolom Join.

### 2. Server headless (tanpa GUI, opsional)

```bash
mvn compile
mvn exec:java -Dexec.mainClass="com.lanchess.server.GameServer"
```

Server yang dijalankan lewat `main()` langsung (tanpa `configure()`) memakai default: `TimeControl.UNLIMITED` dan host otomatis `WHITE` — backward-compatible dengan versi sebelum fitur Timer ditambahkan.

### 3. Build Fat JAR

```bash
mvn clean package
java -jar target/lan-chess-arena.jar
```

> Main-Class fat JAR adalah `AppLauncher` (bukan `MainApp`) — lihat komentar di `AppLauncher.java`.

## Arsitektur

```
com.lanchess
├── client/
│   ├── AppLauncher          -> main class fat JAR (TIDAK extends Application)
│   ├── MainApp               -> main class 'mvn javafx:run', membuka MainMenuController
│   ├── MainMenuController    -> menu utama (frame 1 Figma)
│   ├── FriendModeController  -> pilih Host/Join (frame 2 Figma)
│   ├── HostSetupController   -> setup Timer + warna sebelum host (frame 4 Figma)
│   ├── BotSetupController    -> setup ELO + Timer + warna sebelum vs Bot (frame 3 Figma)
│   ├── GameController        -> mode LAN: klik board <-> NetworkClient, clock interpolasi, history
│   ├── BotGameController     -> mode vs Bot: klik board <-> StockfishEngine, clock lokal otoritatif
│   ├── BoardView             -> Canvas render papan 8x8 (grid minimalis + highlight premove)
│   ├── ClockPanel            -> komponen visual jam catur (mm:ss, 2 warna)
│   ├── MoveHistoryPanel      -> panel riwayat langkah (kotak kanan-atas, frame 5 Figma)
│   ├── PlayerColorChoice     -> widget 3 lingkaran pilih warna (Putih/Acak/Hitam)
│   ├── MoveNotationFormatter -> format Move -> notasi aljabar ringkas untuk history panel
│   ├── Theme                 -> loader stylesheet theme.css
│   └── NetworkClient         -> Socket, ObjectStream, listener thread, callback
├── server/
│   ├── GameServer     -> ServerSocket:5555, terima 2 client, pegang GameState, relay resign/draw
│   ├── ClientHandler  -> 1 thread per client, baca Message, delegasi ke MoveValidator + GameClock
│   ├── MoveValidator  -> SATU-SATUNYA sumber kebenaran aturan catur (rules + repetition + 50-move)
│   └── GameClock      -> jam catur server-authoritative (dipakai ulang lokal di mode Bot juga)
├── bot/
│   ├── StockfishEngine  -> proses UCI: bestmove, setElo, evaluateCentipawns (untuk draw-offer bot)
│   ├── FenConverter     -> GameState <-> FEN, parsing notasi UCI ("e2e4") -> Move
│   ├── BotDifficulty    -> preset rating ELO (1320-3190) & movetime per level
│   └── StockfishLocator -> auto-detect lokasi binary Stockfish di sistem
└── model/
    ├── GameState, Move, Message, PlayerColor, GameStatus, MessageType, PieceType, TimeControl, DrawReason
    ├── BoardFactory  -> Factory Pattern, posisi awal bidak
    └── pieces/       -> Piece (abstract) + King/Queen/Rook/Bishop/Knight/Pawn

src/main/resources/com/lanchess/client/theme.css  -> design system: gradient bg, card-panel, pill-button, dst.
```

## Protokol Komunikasi (4 fase) — Mode LAN

1. **LOBBY** — client connect -> server assign `PlayerColor` (host dapat warna pilihannya sendiri via `GameServer.configure()`, lawan dapat warna sebaliknya) -> kirim `STATE_UPDATE` awal.
2. **GAMEPLAY LOOP** — client kirim `MOVE` -> `MoveValidator` validasi penuh -> `GameServer.notifyMoveMade()` update `GameClock` -> broadcast `STATE_UPDATE`, atau `MOVE_REJECTED` kalau ilegal.
3. **CHAT** — kapan saja, broadcast ke semua client. **RESIGN** — kapan saja selama game aktif, langsung mengakhiri game. **DRAW_OFFER/DRAW_ACCEPT/DRAW_DECLINE** — server hanya me-relay tawaran ke lawan (tidak mengubah state) sampai lawan accept/decline.
4. **GAME OVER** — server deteksi checkmate/stalemate/timeout/**resignation**/**draw** (repetisi, 50-move, atau kesepakatan) -> broadcast `END`.

## Resign, Draw Offer, Threefold Repetition, 50-Move Rule

- **Resign** — `ClientHandler.handleResign()`: langsung set `GameState.loserColor` + status `RESIGNATION`, stop jam, broadcast. Berlaku kapan saja game masih aktif, tidak harus giliran sendiri.
- **Draw Offer** — server TIDAK mengubah `GameState` sampai lawan benar-benar accept. `GameServer` melacak `pendingDrawOfferFrom` untuk mencegah exploit (mis. accept tawaran sendiri, atau accept padahal tidak ada tawaran pending).
- **Threefold Repetition** — `GameState.currentPositionKey()` menghasilkan kunci posisi (piece placement + giliran + hak castling + target en passant, TANPA halfmove/fullmove clock, sesuai definisi FIDE) setiap kali move dieksekusi. Posisi awal permainan ikut dihitung (dicatat di constructor `GameState()`). Begitu satu posisi tercatat 3x, `MoveValidator.updateGameStatus()` otomatis set `DRAW` + `DrawReason.THREEFOLD_REPETITION` (tidak perlu "klaim" manual dari pemain, konsisten dengan checkmate/stalemate yang juga otomatis di app ini).
- **50-Move Rule** — `GameState.computeHalfmoveClock()` menghitung half-move sejak capture/pawn-move terakhir; begitu mencapai 100 (50 langkah penuh), otomatis `DRAW` + `DrawReason.FIFTY_MOVE_RULE`.
- **Bot mode menerima/menolak draw offer berdasarkan evaluasi Stockfish SUNGGUHAN** (bukan random): `StockfishEngine.evaluateCentipawns()` parsing baris `info ... score cp/mate ...` dari output UCI. Bot menolak kalau yakin unggul >150 centipawn dari sudut pandangnya sendiri, menerima kalau posisi seimbang atau bot tertinggal.

## Premove (Client-Side)

Klik papan **saat bukan giliran sendiri** (mode LAN: menunggu lawan; mode Bot: Stockfish sedang berpikir) mengantrikan langkah, bukan mengabaikannya. Begitu giliran benar-benar tiba, premove otomatis dicoba dikirim — kalau posisi sudah berubah gara-gara langkah lawan sehingga premove tidak legal lagi, dibuang diam-diam tanpa error. Klik-kanan membatalkan premove yang sedang diantrikan. Ini murni fitur UX client-side (`GameController`/`BotGameController`) — server tetap memvalidasi setiap langkah yang benar-benar terkirim persis seperti langkah biasa, premove tidak diberi keistimewaan apapun di sisi validasi.

## Jam Catur (Timer) — Server-Authoritative

Desain kunci di `GameClock` (dipakai server LAN **dan** dipakai ulang secara lokal di mode Bot):

- **Deduksi waktu HANYA di boundary giliran** — dihitung dari selisih `System.currentTimeMillis()` asli saat move dieksekusi, BUKAN lewat ticker periodik, supaya tidak ada akumulasi drift.
- **Timeout dideteksi lewat SATU `TimerTask` terjadwal presisi** di sisa waktu pemain yang sedang jalan. Begitu pemain itu jalan, task lama dibatalkan dan dijadwalkan ulang untuk pemain berikutnya.
- **Client TIDAK menghitung waktu otoritatif sendiri** (mode LAN) — hanya interpolasi visual dari nilai terakhir di `STATE_UPDATE`, supaya server tetap satu-satunya sumber kebenaran tanpa perlu broadcast tiap detik.
- Mode Bot memakai `GameClock` yang SAMA secara lokal (satu JVM, jadi langsung otoritatif tanpa perlu split authoritative/display).

Semua ini **diverifikasi dengan Timer sungguhan** selama pengembangan: 5 skenario unit-level (deduksi waktu, timeout tepat waktu, pembatalan saat move tepat waktu, `stop()` mencegah timeout telat, UNLIMITED tidak pernah timeout) + 1 integration test end-to-end dengan 2 socket TCP asli yang membuktikan waktu WHITE berkurang tepat sesuai jeda nyata setelah move dikirim lewat jaringan.

## Mode vs Bot (Stockfish)

Mode ini **tidak memakai socket sama sekali** — semuanya jalan in-process di satu JVM, memakai `MoveValidator` dan `GameClock` yang SAMA dengan mode LAN:

```
Pemain klik kotak tujuan
        │
        ▼
MoveValidator.findLegalMove() + executeMove() + GameClock.onMoveMade()
        │
        ▼
Giliran bot? ──► FenConverter.toFen(state) ──► StockfishEngine.getBestMove(fen, movetime)
        │                                              │  (background thread, BLOCKING
        │                                              │   0.3-2.5 detik tergantung ELO)
        │                                              ▼
        │                                     FenConverter.parseUciMove("e7e8q", state)
        │                                              │
        │                                              ▼
        └────────────────────────────  MoveValidator.findLegalMove() lagi (validasi ulang!)
                                                   │
                                                   ▼
                                execute + GameClock.onMoveMade() + Platform.runLater() update UI
```

Poin penting desain:
- **`MoveValidator` & `GameClock` dipakai ulang tanpa modifikasi** di kedua mode — satu implementasi, bukan 2 versi yang bisa saling tidak sinkron.
- Langkah dari Stockfish **tetap divalidasi ulang** lewat `MoveValidator.findLegalMove()` sebelum dieksekusi, persis seperti langkah client manusia.
- **Rating ELO asli** (bukan angka abstrak): `BotDifficulty` memetakan ke opsi UCI `UCI_LimitStrength` + `UCI_Elo` Stockfish (rentang valid 1320–3190, diverifikasi lewat `uci` handshake sungguhan), plus `movetime` per level.
- `StockfishEngine.getBestMove()` selalu dipanggil dari **background thread terpisah**, karena UCI `go movetime` itu blocking.

## Aturan Catur yang Diimplementasikan

Semua di `MoveValidator` (server-side, single source of truth):
- Gerak dasar semua bidak + capture + blocking
- Deteksi skak & filter langkah yang meninggalkan raja sendiri dalam skak
- Checkmate & stalemate detection
- **Castling** (kingside & queenside): raja/benteng belum pernah gerak, jalur kosong, raja tidak sedang skak, raja tidak melewati/mendarat di kotak yang diserang
- **En passant**, dideteksi dari `GameState.getLastMove()`
- **Promosi pion**, pemain memilih Queen/Rook/Bishop/Knight lewat dialog, default Queen kalau tidak valid
- **Timeout** (kehabisan waktu di jam catur) — status `GameStatus.TIMEOUT`, ditangani sama seriusnya dengan checkmate/stalemate

## Design Patterns

- **Observer** — `GameServer` (subject) broadcast `STATE_UPDATE` ke semua `ClientHandler` (observer) setiap kali state berubah.
- **State Machine** — `GameStatus`: `WAITING_FOR_PLAYER -> PLAYING -> CHECK -> ... -> CHECKMATE/STALEMATE/TIMEOUT/RESIGNATION/DRAW`.
- **Command** — `Message` sebagai command object yang dikirim lewat network.
- **Factory** — `BoardFactory.createStandardBoard()` untuk inisialisasi posisi awal.

## Catatan Implementasi Kritis

1. **`Platform.runLater()`** dipakai di setiap callback `NetworkClient`/`StockfishEngine`/`GameClock` yang menyentuh UI, karena listener thread, engine thread, dan `Timer` thread bukan JavaFX Application Thread.
2. **Urutan stream**: `ObjectOutputStream` dibuat & di-`flush()` SEBELUM `ObjectInputStream`, di KEDUA sisi — kalau tidak, deadlock saat handshake.
3. **`out.reset()`** dipanggil setiap kali habis `writeObject()` mengirim `GameState` — karena objeknya di-mutate in-place lalu dikirim ulang dengan reference yang sama, tanpa `reset()` client akan menerima cached handle, bukan data terbaru.
4. **`AppLauncher`** sengaja TIDAK extends `Application` (workaround error "JavaFX runtime components are missing" saat `java -jar` fat JAR).
5. Semua class di `model/` implements `Serializable` (dengan `serialVersionUID` eksplisit) karena dikirim mentah lewat `ObjectStream` — termasuk `TimeControl` yang baru ditambahkan.
6. **`GameClock` thread-safe** lewat `synchronized` pada semua method-nya — race antara "move baru tiba" dan "timer timeout baru bunyi" ditangani aman (salah satu menang, bukan dua-duanya diproses).
7. **`StockfishEngine` tidak thread-safe** untuk pemanggilan konkuren — semua panggilan `getBestMove()`/`evaluateCentipawns()` terjadi berurutan dari satu background thread khusus, tidak pernah paralel.
8. Proses Stockfish **wajib di-`quit()`** saat keluar dari layar bot (lihat `Stage.setOnCloseRequest` & tombol "Kembali ke Menu").
9. **`GameServer.clients` pakai `CopyOnWriteArrayList`, bukan `ArrayList`** — ditemukan lewat testing beruntun: `sendMessage()` yang gagal (socket lawan sudah tertutup) bisa memicu `closeConnection()` -> `handleDisconnect()` secara REENTRANT (thread sama, `synchronized` boleh masuk lagi) DI TENGAH loop broadcast yang sedang meng-iterasi `clients` yang sama, menyebabkan `ConcurrentModificationException` dengan `ArrayList` biasa. `CopyOnWriteArrayList` aman untuk pola iterasi-sambil-kemungkinan-dimodifikasi ini.
10. **`ClientHandler.awaitReady()` (`CountDownLatch`)** — `GameServer.start()` menunggu stream `ClientHandler` benar-benar siap sebelum lanjut ke `broadcastState()` awal. Tanpa ini ada race: `broadcastState()` bisa terpanggil sebelum `ClientHandler.run()` sempat menginisialisasi `ObjectOutputStream`-nya sendiri, menyebabkan `NullPointerException`. Ditemukan lewat integration test beruntun 5x.

## Status Implementasi

- [x] Model layer lengkap (Piece hierarchy, GameState, Move, Message, TimeControl, DrawReason, enums)
- [x] MoveValidator lengkap (full rules: castling, en passant, promosi, check/checkmate/stalemate)
- [x] Networking core (GameServer, ClientHandler, NetworkClient) — termasuk 2 race condition yang ditemukan & diperbaiki lewat testing
- [x] Jam catur server-authoritative (GameClock) — LAN & Bot, dengan timeout fungsional penuh
- [x] JavaFX GUI sesuai desain Figma: menu utama, host/join, setup ELO/Timer/warna, gameplay dengan clock + move history + chat
- [x] Mode vs Bot Stockfish dengan rating ELO asli (StockfishEngine, FenConverter, BotDifficulty, StockfishLocator)
- [x] **Resign** — kapan saja selama game aktif, langsung mengakhiri game dengan loser yang jelas
- [x] **Draw Offer/Accept/Decline** — server hanya relay, tidak ubah state sampai lawan accept; bot mode menerima/menolak berdasarkan evaluasi Stockfish sungguhan
- [x] **Threefold repetition** — otomatis terdeteksi, termasuk posisi awal permainan ikut dihitung
- [x] **50-move rule** — otomatis terdeteksi di half-move ke-100 sejak capture/pawn-move terakhir
- [x] **Premove** — client-side, mode LAN & Bot, auto-submit begitu giliran tiba, dibuang diam-diam kalau sudah tidak legal
- [x] Unit test JUnit 5: `MoveValidatorTest` (22 kasus) + `FenConverterTest` (10 kasus) = 32 test
- [ ] Increment/delay pada jam catur (saat ini sudden-death murni)
- [ ] Insufficient material draw (mis. King vs King) — belum ada, opsional

## Menjalankan Unit Test

```bash
mvn test
```

## Yang Sudah Diverifikasi Nyata Selama Pengembangan

Bukan cuma ditulis dan diasumsikan benar — bagian yang mengandung logika (bukan pure layout JavaFX) dijalankan sungguhan sebelum difinalisasi:

- **32 unit test JUnit** (`MoveValidatorTest` 22 kasus + `FenConverterTest` 10 kasus)
- **5 skenario `GameClock`** dengan `Timer`/`TimerTask` sungguhan (termasuk timeout beneran bunyi tepat waktu)
- **1 integration test end-to-end (clock)**: `GameServer` + 2 socket TCP asli + move nyata + verifikasi waktu WHITE berkurang tepat sesuai jeda sungguhan yang dilihat dari client lain
- **1 integration test end-to-end (resign/draw)**: `GameServer` + 2 socket TCP asli, 3 skenario (resign, draw accept, draw decline) — juga menemukan & memperbaiki 2 race condition nyata di `GameServer`/`ClientHandler` (lihat Catatan Implementasi Kritis #9-10)
- **4 skenario draw rules** (threefold repetition via knight-shuffle sungguhan, 50-move rule, reset counter oleh capture/pawn-move, checkmate tidak terganggu logika baru)
- **Evaluation smoke test** dengan Stockfish 16 sungguhan: posisi seimbang ~0cp, unggul ratu >+500cp, tertinggal ratu <-500cp — memverifikasi parsing skor UCI yang dipakai keputusan draw-offer bot
- **Bot smoke test end-to-end** dengan Stockfish 16 sungguhan: self-play beberapa half-move, `UCI_Elo` diverifikasi diterima engine, setiap langkah balik dari engine divalidasi ulang lewat `MoveValidator` kita sendiri

**Catatan jujur**: layer JavaFX (layout visual, styling CSS, komponen GUI) TIDAK bisa saya compile-test langsung karena sandbox pengembangan ini tidak punya akses ke Maven Central untuk mengunduh dependency JavaFX. Semua kode GUI sudah saya review manual baris-per-baris, brace-balance semua 42 file sudah dicek otomatis, dan seluruh logic yang dipanggil GUI (MoveValidator, GameClock, FenConverter, dst.) sudah teruji nyata seperti di atas — tapi kalau ada error compile spesifik di layer JavaFX pas kamu jalankan `mvn javafx:run`, kirim pesan errornya untuk langsung diperbaiki.
