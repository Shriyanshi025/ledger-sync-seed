package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.ingest.IngestService;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.report.Reports;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProvenanceLifecycleTest {

    @Test
    void testMigrationPreservesLegacyRowsAndCategorizesProvenance(@TempDir Path tempDir) {
        Path dbFile = tempDir.resolve("ledger-test");
        Path migrations = Path.of("db", "migration");

        try (SqlLedgerStore store = new SqlLedgerStore(dbFile)) {
            store.migrate(migrations);

            // 1. Raw count after migration should be 10 legacy rows
            assertEquals(10, store.all().size(), "Raw all() should return 10 legacy rows after migration");

            // 2. Canonical submission should only return m-legacy-0041 (INCIDENT_EVIDENCE)
            List<NormalizedTxn> canonical = store.canonicalForSubmission();
            assertEquals(1, canonical.size(), "Canonical submission view after migration should only contain INCIDENT_EVIDENCE row");
            assertTrue(canonical.get(0).sourceMessageIds().contains("m-legacy-0041"), "Canonical row must be m-legacy-0041");
        }
    }

    @Test
    void testFreshMigrateAndIngestProducesExactCounts(@TempDir Path tempDir) {
        Path dbFile = tempDir.resolve("ledger-test");
        Path migrations = Path.of("db", "migration");
        Path corpus = Path.of("fixtures", "corpus-a.jsonl");

        try (SqlLedgerStore store = new SqlLedgerStore(dbFile)) {
            store.migrate(migrations);
            new IngestService(new Parsers(), store).ingestFile(corpus);

            // 5 & 6. Raw count should be 266, canonical submission count should be 257
            List<NormalizedTxn> raw = store.all();
            List<NormalizedTxn> canonical = store.canonicalForSubmission();

            assertEquals(266, raw.size(), "Raw database count must be 266 (10 legacy + 256 corpus)");
            assertEquals(257, canonical.size(), "Canonical submission count must be 257 (1 incident evidence + 256 corpus)");

            // 4. Verify m-legacy-0041 is included in canonical view
            boolean hasIncidentEvidence = canonical.stream()
                    .anyMatch(t -> t.sourceMessageIds().contains("m-legacy-0041"));
            assertTrue(hasIncidentEvidence, "Canonical view must include Task 3 incident evidence (m-legacy-0041)");

            // 3. Verify ordinary legacy rows are excluded from canonical view
            boolean hasOrdinaryLegacy = canonical.stream()
                    .anyMatch(t -> t.sourceMessageIds().contains("m-legacy-0001"));
            assertFalse(hasOrdinaryLegacy, "Canonical view must exclude ordinary LEGACY rows");

            // 7. Canonical account/category summaries are generated from canonical view
            var summary = Reports.summary(canonical);
            assertNotNull(summary.accounts().get("4821"));
            assertNotNull(summary.accounts().get("9075"));

            // 12. Reconciliation reports ₹7,500 gap as unrepresented bank transaction without fabricating a row
            var recon = Reports.reconciliation(canonical);
            boolean hasUnrepresented7500 = recon.discrepancies().stream()
                    .anyMatch(d -> d.accountLast4().equals("4821")
                            && d.amount().toPlainString().equals("7500.00")
                            && d.note().contains("Unrepresented bank transaction"));
            assertTrue(hasUnrepresented7500, "₹7,500 gap must be honestly reported as unrepresented bank transaction");
        }
    }

    @Test
    void testRerunningIngestionIsRepeatSafe(@TempDir Path tempDir) {
        Path dbFile = tempDir.resolve("ledger-test");
        Path migrations = Path.of("db", "migration");
        Path corpus = Path.of("fixtures", "corpus-a.jsonl");

        try (SqlLedgerStore store = new SqlLedgerStore(dbFile)) {
            store.migrate(migrations);
            var ingestService = new IngestService(new Parsers(), store);

            // 8. Rerunning ingestion does not create duplicate CORPUS rows
            ingestService.ingestFile(corpus);
            assertEquals(266, store.all().size());
            assertEquals(257, store.canonicalForSubmission().size());

            ingestService.ingestFile(corpus);
            assertEquals(266, store.all().size(), "Rerunning ingestion must not increase raw count");
            assertEquals(257, store.canonicalForSubmission().size(), "Rerunning ingestion must not increase canonical count");
        }
    }
}
