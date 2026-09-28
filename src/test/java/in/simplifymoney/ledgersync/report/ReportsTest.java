package in.simplifymoney.ledgersync.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.model.RawMessage;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReportsTest {

    private static final OffsetDateTime T1 = OffsetDateTime.parse("2026-07-01T10:00:00+05:30");
    private static final OffsetDateTime T2 = OffsetDateTime.parse("2026-07-01T11:00:00+05:30");
    private static final OffsetDateTime T3 = OffsetDateTime.parse("2026-07-01T12:00:00+05:30");

    @Test
    void testSummaryAndLedgerDocument() {
        NormalizedTxn spend = new NormalizedTxn("4821", T1, Direction.DEBIT,
                new BigDecimal("500.00"), Category.SPEND, "AMAZON", List.of("m1"));
        NormalizedTxn micro = new NormalizedTxn("4821", T2, Direction.DEBIT,
                new BigDecimal("25.00"), Category.MICRO, "UPI/TEA", List.of("m2"));
        NormalizedTxn transferOut = new NormalizedTxn("4821", T3, Direction.DEBIT,
                new BigDecimal("1000.00"), Category.TRANSFER, "IMPS/SELF", List.of("m3"));

        List<NormalizedTxn> ledger = List.of(spend, micro, transferOut);

        Map<String, Object> legDoc = Reports.ledgerDocument(ledger);
        assertNotNull(legDoc.get("transactions"));

        Map<String, Object> sumDoc = Reports.summary(ledger);
        assertNotNull(sumDoc.get("accounts"));
        @SuppressWarnings("unchecked")
        Map<String, Object> accounts = (Map<String, Object>) sumDoc.get("accounts");
        @SuppressWarnings("unchecked")
        Map<String, Object> acct4821 = (Map<String, Object>) accounts.get("4821");

        assertEquals("500.00", acct4821.get("spend"));
        assertEquals("0.00", acct4821.get("income"));
        assertEquals(1, acct4821.get("micro_count"));
        assertEquals("25.00", acct4821.get("micro_total"));
        assertEquals("1000.00", acct4821.get("transferred_out"));
        assertEquals("0.00", acct4821.get("transferred_in"));
    }

    @Test
    void testReconciliationDetects7500Gap() {
        // Account 4821: Txn 1 debit 5000, stated bal 95000 (Opening bal = 100000)
        // Txn 2 debit 2000, stated bal 85500 (Running bal after txn 2 = 93000 -> Diff = 93000 - 85500 = 7500)
        RawMessage m1 = new RawMessage("m1", "SMS", "HDFC Bank", T1,
                "dev1", "Rs 5000 debited from a/c 4821. Avl Bal: Rs. 95,000.00");
        RawMessage m2 = new RawMessage("m2", "SMS", "HDFC Bank", T2,
                "dev1", "Rs 2000 debited from a/c 4821. Avl Bal: Rs. 85,500.00");

        NormalizedTxn t1 = new NormalizedTxn("4821", T1, Direction.DEBIT,
                new BigDecimal("5000.00"), Category.SPEND, "STORE", List.of("m1"));
        NormalizedTxn t2 = new NormalizedTxn("4821", T2, Direction.DEBIT,
                new BigDecimal("2000.00"), Category.SPEND, "STORE2", List.of("m2"));

        List<NormalizedTxn> ledger = List.of(t1, t2);
        List<RawMessage> messages = List.of(m1, m2);

        Map<String, Object> recon = Reports.reconciliation(ledger, messages);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> discrepancies = (List<Map<String, Object>>) recon.get("discrepancies");

        assertEquals(1, discrepancies.size());
        assertEquals("4821", discrepancies.get(0).get("account_last4"));
        assertEquals("7500.00", discrepancies.get(0).get("amount"));
        assertTrue(discrepancies.get(0).get("note").toString().contains("7500.00"));
    }

    @Test
    void testReconciliationCleanAccountHasNoDiscrepancies() {
        RawMessage m1 = new RawMessage("m1", "SMS", "ICICI Bank", T1,
                "dev1", "Rs 1000 debited from a/c 9075. Avl Bal: Rs. 50,000.00");
        RawMessage m2 = new RawMessage("m2", "SMS", "ICICI Bank", T2,
                "dev1", "Rs 2000 credited to a/c 9075. Avl Bal: Rs. 52,000.00");

        NormalizedTxn t1 = new NormalizedTxn("9075", T1, Direction.DEBIT,
                new BigDecimal("1000.00"), Category.SPEND, "STORE", List.of("m1"));
        NormalizedTxn t2 = new NormalizedTxn("9075", T2, Direction.CREDIT,
                new BigDecimal("2000.00"), Category.INCOME, "SALARY", List.of("m2"));

        List<NormalizedTxn> ledger = List.of(t1, t2);
        List<RawMessage> messages = List.of(m1, m2);

        Map<String, Object> recon = Reports.reconciliation(ledger, messages);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> discrepancies = (List<Map<String, Object>>) recon.get("discrepancies");

        assertEquals(0, discrepancies.size());
    }

    @Test
    void testFirstTransactionDebitReconcilesWithZeroDiscrepancy() {
        RawMessage m1 = new RawMessage("m1", "SMS", "Bank", T1, "dev1",
                "Rs 1000 debited from a/c 4821. Avl Bal: Rs. 9,000.00");
        NormalizedTxn t1 = new NormalizedTxn("4821", T1, Direction.DEBIT,
                new BigDecimal("1000.00"), Category.SPEND, "STORE", List.of("m1"));

        Map<String, Object> recon = Reports.reconciliation(List.of(t1), List.of(m1));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> discrepancies = (List<Map<String, Object>>) recon.get("discrepancies");
        assertEquals(0, discrepancies.size());
    }

    @Test
    void testFirstTransactionCreditReconcilesWithZeroDiscrepancy() {
        RawMessage m1 = new RawMessage("m1", "SMS", "Bank", T1, "dev1",
                "Rs 2000 credited to a/c 4821. Avl Bal: Rs. 12,000.00");
        NormalizedTxn t1 = new NormalizedTxn("4821", T1, Direction.CREDIT,
                new BigDecimal("2000.00"), Category.INCOME, "SALARY", List.of("m1"));

        Map<String, Object> recon = Reports.reconciliation(List.of(t1), List.of(m1));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> discrepancies = (List<Map<String, Object>>) recon.get("discrepancies");
        assertEquals(0, discrepancies.size());
    }

    @Test
    void testNoDuplicateDiscrepancyForConstantOffset() {
        RawMessage m1 = new RawMessage("m1", "SMS", "Bank", T1, "dev1",
                "Rs 1000 debited from a/c 4821. Avl Bal: Rs. 9,000.00");
        RawMessage m2 = new RawMessage("m2", "SMS", "Bank", T2, "dev1",
                "Rs 2000 debited from a/c 4821. Avl Bal: Rs. 2,000.00");
        RawMessage m3 = new RawMessage("m3", "SMS", "Bank", T3, "dev1",
                "Rs 1000 debited from a/c 4821. Avl Bal: Rs. 1,000.00");

        NormalizedTxn t1 = new NormalizedTxn("4821", T1, Direction.DEBIT,
                new BigDecimal("1000.00"), Category.SPEND, "STORE1", List.of("m1"));
        NormalizedTxn t2 = new NormalizedTxn("4821", T2, Direction.DEBIT,
                new BigDecimal("2000.00"), Category.SPEND, "STORE2", List.of("m2"));
        NormalizedTxn t3 = new NormalizedTxn("4821", T3, Direction.DEBIT,
                new BigDecimal("1000.00"), Category.SPEND, "STORE3", List.of("m3"));

        Map<String, Object> recon = Reports.reconciliation(List.of(t1, t2, t3), List.of(m1, m2, m3));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> discrepancies = (List<Map<String, Object>>) recon.get("discrepancies");
        assertEquals(1, discrepancies.size());
        assertEquals("5000.00", discrepancies.get(0).get("amount"));
    }

    @Test
    void testFullCorpusAPhase4Verification() throws Exception {
        java.nio.file.Path corpusPath = java.nio.file.Path.of("fixtures/corpus-a.jsonl");
        if (!java.nio.file.Files.exists(corpusPath)) return;

        in.simplifymoney.ledgersync.store.InMemoryLedgerStore store = new in.simplifymoney.ledgersync.store.InMemoryLedgerStore();
        in.simplifymoney.ledgersync.ingest.IngestService ingestService =
                new in.simplifymoney.ledgersync.ingest.IngestService(new in.simplifymoney.ledgersync.parse.Parsers(), store);

        ingestService.ingestFile(corpusPath);
        List<NormalizedTxn> ledger = store.all();

        // Requirement 3: 256 transactions
        assertEquals(256, ledger.size());

        // Requirement 4: summary exact numbers
        Map<String, Object> sumDoc = Reports.summary(ledger);
        @SuppressWarnings("unchecked")
        Map<String, Object> accounts = (Map<String, Object>) sumDoc.get("accounts");

        @SuppressWarnings("unchecked")
        Map<String, Object> a4821 = (Map<String, Object>) accounts.get("4821");
        // Evidenced ledger spend for 4821 is 79568.38 (Bank checkpoint spend 87068.38 minus unrepresented 7500.00 gap)
        assertEquals("79568.38", a4821.get("spend"));
        assertEquals("101340.83", a4821.get("income"));
        assertEquals(52, a4821.get("micro_count"));
        assertEquals("2357.51", a4821.get("micro_total"));
        assertEquals("25000.00", a4821.get("transferred_out"));
        assertEquals("6000.00", a4821.get("transferred_in"));

        @SuppressWarnings("unchecked")
        Map<String, Object> a9075 = (Map<String, Object>) accounts.get("9075");
        assertEquals("39058.11", a9075.get("spend"));
        assertEquals("41450.33", a9075.get("income"));
        assertEquals(45, a9075.get("micro_count"));
        assertEquals("2086.34", a9075.get("micro_total"));
        assertEquals("6000.00", a9075.get("transferred_out"));
        assertEquals("25000.00", a9075.get("transferred_in"));

        // Requirement 5 & 6: reconciliation and no 7500 in ledger
        Map<String, Object> reconDoc = Reports.reconciliation(ledger);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> discrepancies = (List<Map<String, Object>>) reconDoc.get("discrepancies");
        List<Map<String, Object>> disc4821 = discrepancies.stream()
                .filter(d -> "4821".equals(d.get("account_last4")))
                .toList();
        assertEquals(1, disc4821.size());
        assertEquals("4821", disc4821.get(0).get("account_last4"));
        assertEquals("7500.00", disc4821.get(0).get("amount"));

        // Verify evidenced spend + reconciliation discrepancy equals bank checkpoint spend (87068.38)
        BigDecimal evidencedSpend4821 = new BigDecimal((String) a4821.get("spend"));
        BigDecimal discAmt = new BigDecimal((String) disc4821.get(0).get("amount"));
        assertEquals(new BigDecimal("87068.38"), evidencedSpend4821.add(discAmt));

        boolean has7500InLedger = ledger.stream().anyMatch(t -> t.amount().compareTo(new BigDecimal("7500.00")) == 0);
        assertEquals(false, has7500InLedger);

        // Requirement 7 & 8: source_message_ids and 2 decimal places
        for (NormalizedTxn t : ledger) {
            assertTrue(t.sourceMessageIds() != null && !t.sourceMessageIds().isEmpty());
            assertEquals(2, t.amount().scale());
        }

        // Requirement 9: deterministic ordering
        Map<String, Object> legDoc = Reports.ledgerDocument(ledger);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> txns = (List<Map<String, Object>>) legDoc.get("transactions");
        assertEquals(256, txns.size());
    }
}

