package com.cinema.infrastructure.migration;

import com.cinema.infrastructure.database.DBContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.logging.Logger;

/**
 * One-shot migration runner — chạy 1 file SQL qua JDBC với splitter GO.
 *
 * <p>Run from project root:
 * <pre>
 *   mvn compile exec:java -Dexec.mainClass=com.cinema.infrastructure.migration.MigrationRunner \
 *       -Dexec.args="path/to/migration.sql"
 * </pre>
 *
 * <p>Runs the supplied migration without requiring a separate SQL client.
 */
public final class MigrationRunner {
    private static final Logger logger = Logger.getLogger(MigrationRunner.class.getName());

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println("Usage: MigrationRunner <path-to-sql-file>");
            System.exit(2);
        }
        Path path = Path.of(args[0]);
        System.out.println("=== MigrationRunner ===");
        System.out.println("File: " + path.toAbsolutePath());
        String content = Files.readString(path);
        runSqlBatches(content);
        System.out.println("=== DONE ===");
    }

    static void runSqlBatches(String content) throws Exception {
        // Tách theo GO (uppercase/lowercase) theo dòng
        String[] batches = content.split("(?im)^\\s*GO\\s*$");
        try (Connection conn = DBContext.getConnection()) {
            conn.setAutoCommit(true);
            int ok = 0;
            int skipped = 0;
            for (String batch : batches) {
                String trimmed = batch.strip();
                if (trimmed.isEmpty()) { skipped++; continue; }
                // Bỏ qua PRINT-only batches
                String upper = trimmed.toUpperCase();
                if (upper.contains("PRINT ") && !upper.contains("INSERT") && !upper.contains("UPDATE")
                        && !upper.contains("CREATE") && !upper.contains("ALTER") && !upper.contains("MERGE")) {
                    skipped++;
                    continue;
                }
                try (Statement st = conn.createStatement()) {
                    st.execute(trimmed);
                    ok++;
                } catch (Exception e) {
                    // In ra batch lỗi để debug
                    System.err.println("--- Batch failed (offset " + content.indexOf(trimmed) + ") ---");
                    System.err.println(trimmed.substring(0, Math.min(2000, trimmed.length())));
                    throw e;
                }
            }
            logger.info("Applied " + ok + " batch(es), skipped " + skipped);
        }
    }
}
