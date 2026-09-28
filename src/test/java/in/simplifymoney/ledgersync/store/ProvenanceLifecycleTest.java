package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.ingest.IngestService;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.report.Reports;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProvenanceLifecycleTest {

    @Test
    void testMigrationPreservesLegacyRowsAndCategorizesProvenance(@TempDir Path tempDir) {
        Path dbFile = tempDir.resolve("ledger-test");
        Path migrations = Path.of("db", "migration");

        try (SqlLedgerStore store = new SqlLedgerStore(dbFile)) {
            store.migrate(migrations);

            // 1. Raw count after migration should be 10 legacy rows (9 LEGACY + 1 INCIDENT_EVIDENCE)
            assertEquals(10, store.all().size(), "Raw all() should return 10 legacy rows after migration");

            // 2. Canonical submission should return 0 before corpus ingestion
            List<NormalizedTxn> canonical = store.canonicalForSubmission();
            assertEquals(0, canonical.size(), "Canonical submission view before corpus ingestion should be 0");
        }
    }

    @Test
    void testFreshMigrateAndIngestProducesExactCountsAndTotals(@TempDir Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("ledger-test");
        Path migrations = Path.of("db", "migration");
        Path corpus = Path.of("fixtures", "corpus-a.jsonl");

        try (SqlLedgerStore store = new SqlLedgerStore(dbFile)) {
            store.migrate(migrations);
            new IngestService(new Parsers(), store).ingestFile(corpus);

            List<NormalizedTxn> raw = store.all();
            List<NormalizedTxn> canonical = store.canonicalForSubmission();

            // 1. Raw physical database count is 266 (10 legacy/incident + 256 corpus writes)
            assertEquals(266, raw.size(), "Raw database count must be 266 physical rows (10 legacy + 256 corpus)");

            // 2. Canonical submission count is 256 corpus transactions
            assertEquals(256, canonical.size(), "Canonical submission count must be 256");

            // 3. Verify Task 3 incident evidence (m-legacy-0041 with 92213.10) is excluded from canonical view
            boolean hasIncidentEvidence = canonical.stream()
                    .anyMatch(t -> t.sourceMessageIds().contains("m-legacy-0041"));
            assertFalse(hasIncidentEvidence, "Canonical view must NOT include Task 3 incident evidence m-legacy-0041");

            // 4. Verify m-legacy-0041 remains preserved in raw database
            boolean hasIncidentEvidenceInRaw = raw.stream()
                    .anyMatch(t -> t.sourceMessageIds().contains("m-legacy-0041"));
            assertTrue(hasIncidentEvidenceInRaw, "Raw database must preserve Task 3 incident evidence m-legacy-0041");

            // 5. Canonical account totals match corpus-a-totals.json exactly
            Map<String, Object> summary = Reports.summary(canonical);
            @SuppressWarnings("unchecked")
            Map<String, Object> accounts = (Map<String, Object>) summary.get("accounts");

            @SuppressWarnings("unchecked")
            Map<String, Object> a4821 = (Map<String, Object>) accounts.get("4821");
            BigDecimal spend4821 = new BigDecimal(String.valueOf(a4821.get("spend")));
            BigDecimal micro4821 = new BigDecimal(String.valueOf(a4821.get("micro_total")));
            assertEquals("87068.38", spend4821.add(micro4821).toPlainString(), "Account 4821 total spend (spend + micro) must match fixture 87068.38");
            assertEquals("84710.87", a4821.get("spend"), "Account 4821 non-micro spend must be 84710.87");
            assertEquals("101340.83", a4821.get("income"), "Account 4821 income must match fixture 101340.83");
            assertEquals(52, a4821.get("micro_count"));
            assertEquals("2357.51", a4821.get("micro_total"));
            assertEquals("25000.00", a4821.get("transferred_out"));
            assertEquals("6000.00", a4821.get("transferred_in"));

            @SuppressWarnings("unchecked")
            Map<String, Object> a9075 = (Map<String, Object>) accounts.get("9075");
            BigDecimal spend9075 = new BigDecimal(String.valueOf(a9075.get("spend")));
            BigDecimal micro9075 = new BigDecimal(String.valueOf(a9075.get("micro_total")));
            assertEquals("39058.11", spend9075.add(micro9075).toPlainString(), "Account 9075 total spend (spend + micro) must match fixture 39058.11");
            assertEquals("36971.77", a9075.get("spend"), "Account 9075 non-micro spend must be 36971.77");
            assertEquals("41450.33", a9075.get("income"), "Account 9075 income must match fixture 41450.33");
            assertEquals(45, a9075.get("micro_count"));
            assertEquals("2086.34", a9075.get("micro_total"));
            assertEquals("6000.00", a9075.get("transferred_out"));
            assertEquals("25000.00", a9075.get("transferred_in"));

            // 6. Reconciliation reports ₹7,500 gap as unrepresented bank transaction without fabricating a row
            Map<String, Object> recon = Reports.reconciliation(canonical);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> discrepancies = (List<Map<String, Object>>) recon.get("discrepancies");
            boolean hasUnrepresented7500 = discrepancies.stream()
                    .anyMatch(d -> "4821".equals(d.get("account_last4"))
                            && "7500.00".equals(d.get("amount"))
                            && String.valueOf(d.get("note")).contains("Unrepresented bank transaction"));
            assertTrue(hasUnrepresented7500, "₹7,500 gap must be honestly reported as unrepresented bank transaction");
        }
    }

    @Test
    void testRerunningIngestionIsRepeatSafe(@TempDir Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("ledger-test");
        Path migrations = Path.of("db", "migration");
        Path corpus = Path.of("fixtures", "corpus-a.jsonl");

        try (SqlLedgerStore store = new SqlLedgerStore(dbFile)) {
            store.migrate(migrations);
            var ingestService = new IngestService(new Parsers(), store);

            ingestService.ingestFile(corpus);
            assertEquals(266, store.all().size());
            assertEquals(256, store.canonicalForSubmission().size());

            ingestService.ingestFile(corpus);
            assertEquals(266, store.all().size(), "Rerunning ingestion must not increase raw count");
            assertEquals(256, store.canonicalForSubmission().size(), "Rerunning ingestion must not increase canonical count");
        }
    }
}
