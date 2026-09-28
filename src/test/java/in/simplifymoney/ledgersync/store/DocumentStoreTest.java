package in.simplifymoney.ledgersync.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentStoreTest {

    private static final OffsetDateTime T1 = OffsetDateTime.parse("2026-07-01T10:00:00+05:30");
    private static final OffsetDateTime T2 = OffsetDateTime.parse("2026-07-02T11:00:00+05:30");

    @Test
    void testDocumentStoreQueriesAndBackfill(@TempDir Path tempDir) {
        DynamoDocumentStore store = new DynamoDocumentStore();

        NormalizedTxn t1 = new NormalizedTxn("4821", T1, Direction.DEBIT,
                new BigDecimal("500.00"), Category.SPEND, "AMAZON", List.of("m1"));
        NormalizedTxn t2 = new NormalizedTxn("4821", T2, Direction.CREDIT,
                new BigDecimal("2000.00"), Category.INCOME, "SALARY", List.of("m2"));

        store.save(t1);
        store.save(t2);

        // Q1: forAccountMonth (newest first)
        List<NormalizedTxn> monthTxns = store.forAccountMonth("4821", YearMonth.of(2026, 7));
        assertEquals(2, monthTxns.size());
        assertEquals(T2, monthTxns.get(0).occurredAt()); // Newest first

        // Q2: categoryTotals
        Map<Category, BigDecimal> totals = store.categoryTotals("4821");
        assertEquals(new BigDecimal("500.00"), totals.get(Category.SPEND));
        assertEquals(new BigDecimal("2000.00"), totals.get(Category.INCOME));

        // Q3: byMessageId
        Optional<NormalizedTxn> found = store.byMessageId("m1");
        assertTrue(found.isPresent());
        assertEquals("AMAZON", found.get().merchant());

        // Verify QueryMetrics
        DynamoDocumentStore.QueryMetrics metrics = store.getLastQueryMetrics();
        assertTrue(metrics.examined() >= 1);
        assertTrue(metrics.returned() >= 1);
    }

    @Test
    void testConsistencyCheckerFindsFieldDivergence(@TempDir Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("test_consistency_ledger");
        Path migrationDir = Path.of("db/migration");

        try (SqlLedgerStore sqlStore = new SqlLedgerStore(dbFile)) {
            sqlStore.migrate(migrationDir);

            NormalizedTxn t1 = new NormalizedTxn("4821", T1, Direction.DEBIT,
                    new BigDecimal("500.00"), Category.SPEND, "AMAZON", List.of("m1"));
            sqlStore.save(t1);

            DynamoDocumentStore docStore = new DynamoDocumentStore();
            // Intentionally save modified transaction to document store to trigger divergence
            NormalizedTxn t1Altered = new NormalizedTxn("4821", T1, Direction.DEBIT,
                    new BigDecimal("500.00"), Category.MICRO, "AMAZON", List.of("m1"));
            docStore.save(t1Altered);

            ConsistencyChecker checker = new ConsistencyChecker(sqlStore, docStore);
            List<ConsistencyChecker.Divergence> divergences = checker.check();

            assertFalse(divergences.isEmpty());
            assertTrue(divergences.stream().anyMatch(d -> d.what().contains("Category mismatch")));
        }
    }

    @Test
    void testBackfillRerunSafetyAndMetrics(@TempDir Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("test_backfill_ledger");
        Path migrationDir = Path.of("db/migration");

        try (SqlLedgerStore sqlStore = new SqlLedgerStore(dbFile)) {
            sqlStore.migrate(migrationDir);

            NormalizedTxn t1 = new NormalizedTxn("4821", T1, Direction.DEBIT,
                    new BigDecimal("500.00"), Category.SPEND, "AMAZON", List.of("m1"));
            sqlStore.save(t1);

            DynamoDocumentStore docStore = new DynamoDocumentStore();
            Backfill backfill = new Backfill(sqlStore, docStore);

            // First backfill run
            Backfill.Result res1 = backfill.run();
            assertTrue(res1.read() >= 1);
            assertTrue(res1.written() >= 1);

            // Second backfill run (exact rerun)
            Backfill.Result res2 = backfill.run();
            assertEquals(res1.read(), res2.read());
            assertEquals(0, res2.written());
            assertEquals(res1.read(), res2.skipped());

            // Consistency check after backfill
            ConsistencyChecker checker = new ConsistencyChecker(sqlStore, docStore);
            List<ConsistencyChecker.Divergence> divergences = checker.check();
            assertTrue(divergences.isEmpty(), "Expected 0 divergences after clean backfill, found: " + divergences);
        }
    }
}
