package in.simplifymoney.ledgersync.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepeatSafeIngestionTest {

    private static final OffsetDateTime T1 = OffsetDateTime.parse("2026-07-01T10:00:00+05:30");
    private static final OffsetDateTime T2 = OffsetDateTime.parse("2026-07-01T11:00:00+05:30");

    @Test
    void testRepeatSafeBehaviorInMemory() {
        InMemoryLedgerStore store = new InMemoryLedgerStore();

        NormalizedTxn t1 = new NormalizedTxn("4821", T1, Direction.DEBIT,
                new BigDecimal("500.00"), Category.SPEND, "AMAZON", List.of("m1"));

        // 1. First insert
        store.save(t1);
        assertEquals(1, store.count());
        assertEquals(List.of("m1"), store.all().get(0).sourceMessageIds());

        // 2. Exact replay
        store.save(t1);
        assertEquals(1, store.count());

        // 3. Additional evidence merge
        NormalizedTxn t1Extra = new NormalizedTxn("4821", T1, Direction.DEBIT,
                new BigDecimal("500.00"), Category.SPEND, "AMAZON", List.of("m2"));
        store.save(t1Extra);
        assertEquals(1, store.count());
        assertEquals(List.of("m1", "m2"), store.all().get(0).sourceMessageIds());

        // 4. Different occurred_at
        NormalizedTxn t2 = new NormalizedTxn("4821", T2, Direction.DEBIT,
                new BigDecimal("500.00"), Category.SPEND, "AMAZON", List.of("m3"));
        store.save(t2);
        assertEquals(2, store.count());
    }

    @Test
    void testRepeatSafeBehaviorSqlStore(@TempDir Path tempDir) throws Exception {
        Path dbFile = tempDir.resolve("test_ledger");
        Path migrationDir = Path.of("db/migration");

        try (SqlLedgerStore store = new SqlLedgerStore(dbFile)) {
            store.migrate(migrationDir);

            long initialCount = store.count();

            NormalizedTxn t1 = new NormalizedTxn("4821", T1, Direction.DEBIT,
                    new BigDecimal("500.00"), Category.SPEND, "SWIGGY", List.of("m1"));

            // 1. First insert
            store.save(t1);
            assertEquals(initialCount + 1, store.count());

            // 2. Exact replay -> no-op
            store.save(t1);
            assertEquals(initialCount + 1, store.count());

            // 3. Additional evidence merge
            NormalizedTxn t1Extra = new NormalizedTxn("4821", T1, Direction.DEBIT,
                    new BigDecimal("500.00"), Category.SPEND, "SWIGGY", List.of("m2", "m3"));
            store.save(t1Extra);
            assertEquals(initialCount + 1, store.count());

            NormalizedTxn retrieved = store.all().stream()
                    .filter(t -> t.accountLast4().equals("4821") && t.occurredAt().equals(T1))
                    .findFirst()
                    .orElseThrow();
            assertEquals(List.of("m1", "m2", "m3"), retrieved.sourceMessageIds());

            // 4. Same merchant/amount at different occurred_at time -> separate insert
            NormalizedTxn t2 = new NormalizedTxn("4821", T2, Direction.DEBIT,
                    new BigDecimal("500.00"), Category.SPEND, "SWIGGY", List.of("m4"));
            store.save(t2);
            assertEquals(initialCount + 2, store.count());
        }
    }

    @Test
    void testFullCorpusIngestStatsAndRepeatReplay(@TempDir Path tempDir) throws Exception {
        Path corpusPath = Path.of("fixtures/corpus-a.jsonl");
        if (!Files.exists(corpusPath)) return;

        Path dbFile = tempDir.resolve("test_corpus_ledger");
        Path migrationDir = Path.of("db/migration");

        try (SqlLedgerStore store = new SqlLedgerStore(dbFile)) {
            store.migrate(migrationDir);
            long initialRows = store.count();

            in.simplifymoney.ledgersync.ingest.IngestService ingestService =
                    new in.simplifymoney.ledgersync.ingest.IngestService(new in.simplifymoney.ledgersync.parse.Parsers(), store);

            // First ingestion
            in.simplifymoney.ledgersync.ingest.IngestService.Stats stats1 = ingestService.ingestFile(corpusPath);
            assertEquals(522, stats1.messagesRead());
            assertEquals(256, stats1.transactionsWritten());
            assertEquals(43, stats1.messagesSkipped());
            assertEquals(initialRows + 256, store.count());

            // Second identical ingestion
            in.simplifymoney.ledgersync.ingest.IngestService.Stats stats2 = ingestService.ingestFile(corpusPath);
            assertEquals(522, stats2.messagesRead());
            assertEquals(0, stats2.transactionsWritten());
            assertEquals(43, stats2.messagesSkipped());
            assertEquals(initialRows + 256, store.count());
        }
    }
}
