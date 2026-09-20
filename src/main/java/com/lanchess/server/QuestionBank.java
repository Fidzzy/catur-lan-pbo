package com.lanchess.server;

import com.lanchess.model.Quiz;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Bank soal berbasis SQLite dengan auto-import dari Excel.
 *
 * Alur (sekali jalan di server startup):
 *   1. Buka / buat {@code quiz.db} di working directory, buat tabel
 *      {@code quiz} kalau belum ada.
 *   2. Kalau tabel kosong:
 *      a. Coba import dari {@code soal.xlsx} (format kolom: No | Pertanyaan
 *         | A | B | C | D | Jawaban) — biar user tidak perlu input manual.
 *      b. Kalau Excel tidak ada / rusak, seed dari soal default built-in
 *         (10 soal) supaya aplikasi tetap jalan.
 *   3. Baca semua baris valid dari tabel dan return ke QuizManager.
 *
 * Setelah file {@code quiz.db} terbuat, user bisa edit pakai DB Browser for
 * SQLite. Excel tidak dipakai lagi pada startup berikutnya (tabel sudah terisi).
 *
 * Kalau SQLite driver gagal load (mis. dependency belum di-reload), fallback
 * terakhir adalah 10 soal in-memory — aplikasi TIDAK akan crash.
 */
final class QuestionBank {

    private static final String DB_FILE = "quiz.db";
    private static final String EXCEL_FILE = "soal.xlsx";
    private static final long DEFAULT_TIME_LIMIT_MS = 8000L;

    private QuestionBank() {
    }

    static List<Quiz> load() {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            System.err.println("[QuestionBank] Driver SQLite tidak ditemukan. Fallback ke soal in-memory.");
            return defaults();
        }

        Path dbPath = Path.of(DB_FILE);
        boolean dbExists = Files.exists(dbPath);

        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath())) {
            ensureSchema(conn);

            List<Quiz> list = readAll(conn);
            if (list.isEmpty()) {
                System.out.println("[QuestionBank] Tabel quiz kosong, mencoba import dari " + EXCEL_FILE + "...");
                int imported = importFromExcel(conn, Path.of(EXCEL_FILE));
                if (imported > 0) {
                    System.out.println("[QuestionBank] Berhasil import " + imported + " soal dari " + EXCEL_FILE);
                } else {
                    System.out.println("[QuestionBank] Excel tidak ditemukan / tidak valid, seeding soal default...");
                    seedDefaults(conn);
                }
                list = readAll(conn);
            }

            if (!dbExists) {
                System.out.println("[QuestionBank] Dibuat file baru: " + dbPath.toAbsolutePath());
                System.out.println("[QuestionBank] Edit pakai DB Browser for SQLite (gratis).");
            }
            System.out.println("[QuestionBank] Loaded " + list.size() + " soal dari " + DB_FILE);
            return list;

        } catch (SQLException e) {
            System.err.println("[QuestionBank] Gagal akses SQLite: " + e.getMessage() + ". Fallback ke soal in-memory.");
            return defaults();
        }
    }

    // =========================================================================
    // Schema & read
    // =========================================================================

    private static void ensureSchema(Connection conn) throws SQLException {
        String sql = """
                CREATE TABLE IF NOT EXISTS quiz (
                    id         INTEGER PRIMARY KEY AUTOINCREMENT,
                    pertanyaan TEXT NOT NULL,
                    opsi_a     TEXT NOT NULL,
                    opsi_b     TEXT NOT NULL,
                    opsi_c     TEXT NOT NULL,
                    opsi_d     TEXT NOT NULL,
                    jawaban    TEXT NOT NULL CHECK (jawaban IN ('A','B','C','D'))
                )
                """;
        try (Statement st = conn.createStatement()) {
            st.execute(sql);
        }
    }

    private static List<Quiz> readAll(Connection conn) throws SQLException {
        List<Quiz> result = new ArrayList<>();
        String sql = "SELECT id, pertanyaan, opsi_a, opsi_b, opsi_c, opsi_d, jawaban FROM quiz";
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                long id = rs.getLong("id");
                String q = rs.getString("pertanyaan");
                String a = rs.getString("opsi_a");
                String b = rs.getString("opsi_b");
                String c = rs.getString("opsi_c");
                String d = rs.getString("opsi_d");
                String j = rs.getString("jawaban");

                if (isBlank(q) || isBlank(a) || isBlank(b) || isBlank(c) || isBlank(d) || isBlank(j)) {
                    System.err.println("[QuestionBank] Row id=" + id + " dilewati: ada kolom kosong.");
                    continue;
                }
                int correct = answerToIndex(j);
                if (correct < 0) {
                    System.err.println("[QuestionBank] Row id=" + id + " dilewati: jawaban '" + j + "' tidak valid.");
                    continue;
                }
                result.add(new Quiz(q.trim(), List.of(a.trim(), b.trim(), c.trim(), d.trim()),
                        correct, DEFAULT_TIME_LIMIT_MS));
            }
        }
        return result;
    }

    // =========================================================================
    // Import dari Excel (format: No | Pertanyaan | A | B | C | D | Jawaban)
    // =========================================================================

    private static int importFromExcel(Connection conn, Path xlsx) {
        if (!Files.exists(xlsx)) return 0;

        int imported = 0;
        DataFormatter fmt = new DataFormatter();

        try (InputStream in = Files.newInputStream(xlsx);
             Workbook wb = new XSSFWorkbook(in)) {

            Sheet sheet = wb.getSheetAt(0);
            if (sheet == null) return 0;

            String insertSql = "INSERT INTO quiz (pertanyaan, opsi_a, opsi_b, opsi_c, opsi_d, jawaban) "
                    + "VALUES (?, ?, ?, ?, ?, ?)";

            try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                // Baris 0 = header, mulai dari baris 1
                for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                    Row row = sheet.getRow(r);
                    if (row == null) continue;

                    // Kolom: 0=No, 1=Pertanyaan, 2=A, 3=B, 4=C, 5=D, 6=Jawaban
                    String question = cell(row, 1, fmt);
                    String optA = cell(row, 2, fmt);
                    String optB = cell(row, 3, fmt);
                    String optC = cell(row, 4, fmt);
                    String optD = cell(row, 5, fmt);
                    String answerRaw = cell(row, 6, fmt);

                    // Deteksi header berulang di tengah (mis. baris 16 di soal.xlsx)
                    if (question.equalsIgnoreCase("Pertanyaan")) continue;

                    // Baris benar-benar kosong -> skip tanpa warning
                    if (question.isEmpty() && optA.isEmpty() && optB.isEmpty()
                            && optC.isEmpty() && optD.isEmpty() && answerRaw.isEmpty()) {
                        continue;
                    }

                    if (question.isEmpty() || optA.isEmpty() || optB.isEmpty()
                            || optC.isEmpty() || optD.isEmpty()) {
                        System.err.println("[QuestionBank] Excel baris " + (r + 1)
                                + " dilewati: ada sel kosong.");
                        continue;
                    }

                    int correct = answerToIndex(answerRaw);
                    if (correct < 0) {
                        System.err.println("[QuestionBank] Excel baris " + (r + 1)
                                + " dilewati: jawaban '" + answerRaw + "' tidak valid.");
                        continue;
                    }

                    ps.setString(1, question);
                    ps.setString(2, optA);
                    ps.setString(3, optB);
                    ps.setString(4, optC);
                    ps.setString(5, optD);
                    ps.setString(6, String.valueOf((char) ('A' + correct)));
                    ps.addBatch();
                    imported++;
                }
                if (imported > 0) ps.executeBatch();
            }
        } catch (IOException | SQLException e) {
            System.err.println("[QuestionBank] Import Excel gagal: " + e.getMessage());
            return 0;
        }
        return imported;
    }

    private static String cell(Row row, int col, DataFormatter fmt) {
        Cell c = row.getCell(col);
        if (c == null) return "";
        return fmt.formatCellValue(c).trim();
    }

    // =========================================================================
    // Seed default (hanya kalau Excel tidak ada)
    // =========================================================================

    private static void seedDefaults(Connection conn) throws SQLException {
        String sql = "INSERT INTO quiz (pertanyaan, opsi_a, opsi_b, opsi_c, opsi_d, jawaban) "
                + "VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Quiz q : defaults()) {
                ps.setString(1, q.getQuestion());
                ps.setString(2, q.getOptions().get(0));
                ps.setString(3, q.getOptions().get(1));
                ps.setString(4, q.getOptions().get(2));
                ps.setString(5, q.getOptions().get(3));
                ps.setString(6, String.valueOf((char) ('A' + q.getCorrectIndex())));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    // =========================================================================
    // Utility
    // =========================================================================

    /** "A"/"B"/"C"/"D" atau "1".."4" -> 0..3. Return -1 kalau tidak valid. */
    private static int answerToIndex(String raw) {
        if (raw == null) return -1;
        String s = raw.trim().toUpperCase();
        if (s.isEmpty()) return -1;
        char first = s.charAt(0);
        if (first >= 'A' && first <= 'D') return first - 'A';
        if (first >= '1' && first <= '4') return first - '1';
        return -1;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static List<Quiz> defaults() {
        return List.of(
                new Quiz("Berapa jumlah pion tiap pemain di awal permainan?",
                        List.of("6", "8", "10", "16"), 1, DEFAULT_TIME_LIMIT_MS),
                new Quiz("Buah catur apa yang hanya boleh melangkah diagonal?",
                        List.of("Benteng", "Kuda", "Pion saat menangkap", "Raja"), 2, DEFAULT_TIME_LIMIT_MS),
                new Quiz("Apa nama langkah khusus raja + benteng?",
                        List.of("En Passant", "Promosi", "Castling", "Skakmat"), 2, DEFAULT_TIME_LIMIT_MS),
                new Quiz("Berapa nilai konvensional Ratu (Queen)?",
                        List.of("3", "5", "9", "10"), 2, DEFAULT_TIME_LIMIT_MS),
                new Quiz("Berapa jumlah kotak di papan catur standar?",
                        List.of("36", "49", "64", "81"), 2, DEFAULT_TIME_LIMIT_MS),
                new Quiz("Apa itu 'en passant'?",
                        List.of("Promosi pion", "Tangkapan khusus pion", "Seri otomatis", "Tukar buah"), 1, DEFAULT_TIME_LIMIT_MS),
                new Quiz("Siapa juara dunia catur pertama?",
                        List.of("Emanuel Lasker", "Wilhelm Steinitz", "Bobby Fischer", "Paul Morphy"), 1, DEFAULT_TIME_LIMIT_MS),
                new Quiz("Buah catur mana yang tidak bisa mundur?",
                        List.of("Benteng", "Pion", "Kuda", "Ratu"), 1, DEFAULT_TIME_LIMIT_MS),
                new Quiz("Berapa langkah maksimum sebelum aturan 50-move rule berlaku?",
                        List.of("25", "50", "75", "100"), 1, DEFAULT_TIME_LIMIT_MS),
                new Quiz("Apa nama pembukaan catur '1.e4 e5 2.Nf3 Nc6'?",
                        List.of("Sicilian", "Ruy Lopez", "Italian", "King's Knight"), 3, DEFAULT_TIME_LIMIT_MS)
        );
    }
}