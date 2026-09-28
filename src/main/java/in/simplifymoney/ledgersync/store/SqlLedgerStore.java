package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The store this service has used since it was written: a single relational
 * table, reached over plain JDBC.
 *
 * The driver is a runtime dependency (see build.gradle) - this class compiles
 * against the JDK alone.
 */
public final class SqlLedgerStore implements LedgerStore, AutoCloseable {

    private static final String URL_PREFIX = "jdbc:h2:";
    private final Connection conn;

    public SqlLedgerStore(Path dbFile) {
        try {
            this.conn = DriverManager.getConnection(
                    URL_PREFIX + dbFile.toAbsolutePath() + ";MODE=PostgreSQL", "sa", "");
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "could not open the ledger database at " + dbFile
                            + " (is the H2 driver on the runtime classpath?)", e);
        }
    }

    public static String computeDedupKey(NormalizedTxn t) {
        return computeDedupKey(
                t.accountLast4(),
                t.occurredAt().toString(),
                t.direction().name(),
                t.amount(),
                t.merchant()
        );
    }

    public static String computeDedupKey(String accountLast4, String occurredAt, String direction, BigDecimal amount, String merchant) {
        String m = (merchant == null || merchant.isBlank()) ? "NONE" : merchant.trim().toLowerCase();
        String amtStr = amount != null ? amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString() : "0.00";
        return accountLast4 + "|" + occurredAt + "|" + direction + "|" + amtStr + "|" + m;
    }

    /** Applies every db/migration/V*.sql in filename order. */
    public void migrate(Path migrationDir) {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS schema_history ("
                    + "  filename VARCHAR(200) PRIMARY KEY,"
                    + "  applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");

            List<Path> files;
            try (var s = Files.list(migrationDir)) {
                files = s.filter(p -> p.getFileName().toString().endsWith(".sql")).sorted().toList();
            }
            for (Path f : files) {
                String name = f.getFileName().toString();
                try (PreparedStatement q = conn.prepareStatement(
                        "SELECT 1 FROM schema_history WHERE filename = ?")) {
                    q.setString(1, name);
                    try (ResultSet rs = q.executeQuery()) {
                        if (rs.next()) continue;
                    }
                }
                String sql = Files.readString(f);
                for (String stmt : sql.split(";")) {
                    if (!stmt.isBlank()) st.execute(stmt);
                }
                try (PreparedStatement ins = conn.prepareStatement(
                        "INSERT INTO schema_history(filename) VALUES (?)")) {
                    ins.setString(1, name);
                    ins.executeUpdate();
                }
                System.out.println("applied " + name);
            }

            // Post-migration deduplication cleanup & unique index enforcement
            st.execute("ALTER TABLE ledger ADD COLUMN IF NOT EXISTS dedup_key VARCHAR(300)");

            // Populate dedup_key for any rows missing it
            try (Statement s = conn.createStatement();
                 ResultSet rs = s.executeQuery("SELECT id, account_last4, occurred_at, direction, amount, merchant FROM ledger WHERE dedup_key IS NULL")) {
                while (rs.next()) {
                    long id = rs.getLong("id");
                    String k = computeDedupKey(
                            rs.getString("account_last4"),
                            rs.getString("occurred_at"),
                            rs.getString("direction"),
                            rs.getBigDecimal("amount"),
                            rs.getString("merchant")
                    );
                    try (PreparedStatement up = conn.prepareStatement("UPDATE ledger SET dedup_key = ? WHERE id = ?")) {
                        up.setString(1, k);
                        up.setLong(2, id);
                        up.executeUpdate();
                    }
                }
            }

            // Detect and resolve legacy duplicate dedup_keys before creating unique index
            try (Statement s = conn.createStatement();
                 ResultSet rs = s.executeQuery("SELECT dedup_key, COUNT(*) as cnt FROM ledger GROUP BY dedup_key HAVING COUNT(*) > 1")) {
                List<String> dupKeys = new ArrayList<>();
                while (rs.next()) {
                    dupKeys.add(rs.getString("dedup_key"));
                }
                for (String dupKey : dupKeys) {
                    try (PreparedStatement findPs = conn.prepareStatement(
                            "SELECT id, source_message_ids FROM ledger WHERE dedup_key = ? ORDER BY id")) {
                        findPs.setString(1, dupKey);
                        try (ResultSet dupRs = findPs.executeQuery()) {
                            List<Long> ids = new ArrayList<>();
                            java.util.Set<String> allSourceIds = new java.util.TreeSet<>();
                            while (dupRs.next()) {
                                ids.add(dupRs.getLong("id"));
                                String sIds = dupRs.getString("source_message_ids");
                                if (sIds != null) {
                                    Arrays.stream(sIds.split(","))
                                            .map(String::trim)
                                            .filter(str -> !str.isEmpty())
                                            .forEach(allSourceIds::add);
                                }
                            }
                            if (ids.size() > 1) {
                                long primaryId = ids.get(0);
                                String mergedSourceIds = String.join(",", allSourceIds);
                                try (PreparedStatement updatePrimary = conn.prepareStatement(
                                        "UPDATE ledger SET source_message_ids = ? WHERE id = ?")) {
                                    updatePrimary.setString(1, mergedSourceIds);
                                    updatePrimary.setLong(2, primaryId);
                                    updatePrimary.executeUpdate();
                                }
                                for (int i = 1; i < ids.size(); i++) {
                                    try (PreparedStatement del = conn.prepareStatement("DELETE FROM ledger WHERE id = ?")) {
                                        del.setLong(1, ids.get(i));
                                        del.executeUpdate();
                                    }
                                }
                                System.out.println("Migration merged legacy duplicate dedup_key [" + dupKey + "] -> combined source_message_ids: " + mergedSourceIds);
                            }
                        }
                    }
                }
            }

            st.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_ledger_dedup_key ON ledger (dedup_key)");
        } catch (Exception e) {
            throw new IllegalStateException("migration failed", e);
        }
    }

    @Override
    public boolean save(NormalizedTxn t) {
        String key = computeDedupKey(t);
        List<String> newSourceIds = t.sourceMessageIds() != null ? t.sourceMessageIds() : List.of();

        try (PreparedStatement selectPs = conn.prepareStatement(
                "SELECT id, source_message_ids FROM ledger WHERE dedup_key = ?")) {
            selectPs.setString(1, key);
            try (ResultSet rs = selectPs.executeQuery()) {
                if (rs.next()) {
                    long existingId = rs.getLong("id");
                    String existingSourceIdsStr = rs.getString("source_message_ids");
                    List<String> existingList = existingSourceIdsStr != null
                            ? Arrays.stream(existingSourceIdsStr.split(","))
                                .map(String::trim)
                                .filter(s -> !s.isEmpty())
                                .toList()
                            : List.of();

                    java.util.Set<String> combinedSet = new java.util.TreeSet<>(existingList);
                    combinedSet.addAll(newSourceIds);
                    List<String> combinedList = new ArrayList<>(combinedSet);

                    String newMergedSourceIdsStr = String.join(",", combinedList);

                    if (!newMergedSourceIdsStr.equals(existingSourceIdsStr)) {
                        try (PreparedStatement updatePs = conn.prepareStatement(
                                "UPDATE ledger SET source_message_ids = ?, category = ?, merchant = ? WHERE id = ?")) {
                            updatePs.setString(1, newMergedSourceIdsStr);
                            updatePs.setString(2, t.category().name());
                            updatePs.setString(3, t.merchant());
                            updatePs.setLong(4, existingId);
                            updatePs.executeUpdate();
                        }
                    }
                    return false;
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not query ledger for dedup_key " + key, e);
        }

        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO ledger(account_last4, occurred_at, direction, amount,"
                        + " category, merchant, source_message_ids, dedup_key)"
                        + " VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setString(1, t.accountLast4());
            ps.setString(2, t.occurredAt().toString());
            ps.setString(3, t.direction().name());
            ps.setBigDecimal(4, t.amount().setScale(2, java.math.RoundingMode.HALF_UP));
            ps.setString(5, t.category().name());
            ps.setString(6, t.merchant());
            String sortedSourceIds = String.join(",", new java.util.TreeSet<>(newSourceIds));
            ps.setString(7, sortedSourceIds);
            ps.setString(8, key);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            throw new IllegalStateException("could not save " + t, e);
        }
    }

    @Override
    public List<NormalizedTxn> all() {
        List<NormalizedTxn> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT account_last4, occurred_at, direction, amount, category,"
                             + " merchant, source_message_ids FROM ledger ORDER BY occurred_at")) {
            while (rs.next()) {
                out.add(new NormalizedTxn(
                        rs.getString(1),
                        OffsetDateTime.parse(rs.getString(2)),
                        Direction.valueOf(rs.getString(3)),
                        rs.getBigDecimal(4).setScale(2),
                        Category.valueOf(rs.getString(5)),
                        rs.getString(6),
                        Arrays.stream(rs.getString(7).split(","))
                                .filter(s -> !s.isBlank()).toList()));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the ledger", e);
        }
        return out;
    }

    @Override
    public long count() {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM ledger")) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            throw new IllegalStateException("could not count the ledger", e);
        }
    }

    public BigDecimal sumAmounts() {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT SUM(amount) FROM ledger")) {
            return rs.next() && rs.getBigDecimal(1) != null
                    ? rs.getBigDecimal(1).setScale(2) : BigDecimal.ZERO.setScale(2);
        } catch (SQLException e) {
            throw new IllegalStateException("could not total the ledger", e);
        }
    }

    @Override
    public void close() {
        try { conn.close(); } catch (SQLException ignored) { }
    }
}
