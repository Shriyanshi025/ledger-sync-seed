package in.simplifymoney.ledgersync.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransactionCorrelatorTest {

    private static final OffsetDateTime TIME_1 = OffsetDateTime.parse("2026-07-04T20:24:00+05:30");
    private static final OffsetDateTime TIME_2 = OffsetDateTime.parse("2026-07-05T10:00:00+05:30");

    @Test
    void testA_exactDuplicateReDeliveryMerges() {
        ParsedTxn p1 = new ParsedTxn("4821", TIME_1, Direction.DEBIT, new BigDecimal("90.00"), "UPI/PAN SHOP", new BigDecimal("72773.77"), "m-1");
        ParsedTxn p2 = new ParsedTxn("4821", TIME_1, Direction.DEBIT, new BigDecimal("90.00"), "UPI/PAN SHOP", new BigDecimal("72773.77"), "m-2");

        List<NormalizedTxn> result = TransactionCorrelator.correlate(List.of(p1, p2));
        assertEquals(1, result.size());
        NormalizedTxn t = result.get(0);
        assertEquals(List.of("m-1", "m-2"), t.sourceMessageIds());
        assertEquals(new BigDecimal("90.00"), t.amount());
    }

    @Test
    void testB_smsAndEmailEvidenceMerges() {
        ParsedTxn sms = new ParsedTxn("4821", TIME_1, Direction.DEBIT, new BigDecimal("76.49"), "RELIANCE SMART", new BigDecimal("91609.94"), "m-sms");
        ParsedTxn email = new ParsedTxn("4821", TIME_1, Direction.DEBIT, new BigDecimal("76.49"), "RELIANCE SMART", null, "m-email");

        List<NormalizedTxn> result = TransactionCorrelator.correlate(List.of(sms, email));
        assertEquals(1, result.size());
        NormalizedTxn t = result.get(0);
        assertEquals(List.of("m-email", "m-sms"), t.sourceMessageIds());
        assertEquals("RELIANCE SMART", t.merchant());
    }

    @Test
    void testC_differentOccurredAtRemainsSeparate() {
        ParsedTxn p1 = new ParsedTxn("4821", TIME_1, Direction.DEBIT, new BigDecimal("50.00"), "KIRANA STORE", new BigDecimal("90000.00"), "m-1");
        ParsedTxn p2 = new ParsedTxn("4821", TIME_2, Direction.DEBIT, new BigDecimal("50.00"), "KIRANA STORE", new BigDecimal("89950.00"), "m-2");

        List<NormalizedTxn> result = TransactionCorrelator.correlate(List.of(p1, p2));
        assertEquals(2, result.size());
    }

    @Test
    void testD_differentDirectionRemainsSeparate() {
        ParsedTxn debit = new ParsedTxn("4821", TIME_1, Direction.DEBIT, new BigDecimal("8000.00"), "IMPS/P2A/PARAG KAPOOR", new BigDecimal("80071.04"), "m-1");
        ParsedTxn credit = new ParsedTxn("4821", TIME_1, Direction.CREDIT, new BigDecimal("8000.00"), "IMPS/P2A/PARAG KAPOOR", new BigDecimal("96071.04"), "m-2");

        List<NormalizedTxn> result = TransactionCorrelator.correlate(List.of(debit, credit));
        assertEquals(2, result.size());
    }

    @Test
    void testE_conflictingStatedBalancesDoNotMerge() {
        ParsedTxn p1 = new ParsedTxn("4821", TIME_1, Direction.DEBIT, new BigDecimal("50.00"), "KIRANA STORE", new BigDecimal("90000.00"), "m-1");
        ParsedTxn p2 = new ParsedTxn("4821", TIME_1, Direction.DEBIT, new BigDecimal("50.00"), "KIRANA STORE", new BigDecimal("89950.00"), "m-2");

        List<NormalizedTxn> result = TransactionCorrelator.correlate(List.of(p1, p2));
        assertEquals(2, result.size());
    }

    @Test
    void testF_missingMerchantMergesAndPicksNonBlankMerchant() {
        ParsedTxn p1 = new ParsedTxn("4821", TIME_1, Direction.DEBIT, new BigDecimal("99.99"), "", null, "m-email");
        ParsedTxn p2 = new ParsedTxn("4821", TIME_1, Direction.DEBIT, new BigDecimal("99.99"), "IRCTC", new BigDecimal("93011.42"), "m-sms");

        List<NormalizedTxn> result = TransactionCorrelator.correlate(List.of(p1, p2));
        assertEquals(1, result.size());
        NormalizedTxn t = result.get(0);
        assertEquals("IRCTC", t.merchant());
        assertEquals(List.of("m-email", "m-sms"), t.sourceMessageIds());
    }

    @Test
    void testG_sourceIdsAreDeterministicRegardlessOfInputOrder() {
        ParsedTxn p1 = new ParsedTxn("4821", TIME_1, Direction.DEBIT, new BigDecimal("100.00"), "STORE", null, "m-B");
        ParsedTxn p2 = new ParsedTxn("4821", TIME_1, Direction.DEBIT, new BigDecimal("100.00"), "STORE", null, "m-A");

        List<NormalizedTxn> res1 = TransactionCorrelator.correlate(List.of(p1, p2));
        List<NormalizedTxn> res2 = TransactionCorrelator.correlate(List.of(p2, p1));

        assertEquals(res1, res2);
        assertEquals(List.of("m-A", "m-B"), res1.get(0).sourceMessageIds());
    }

    @Test
    void testH_duplicateEvidenceIsIdempotentAtCorrelatorLevel() {
        ParsedTxn p1 = new ParsedTxn("4821", TIME_1, Direction.DEBIT, new BigDecimal("100.00"), "STORE", null, "m-1");
        List<NormalizedTxn> res1 = TransactionCorrelator.correlate(List.of(p1));
        List<NormalizedTxn> res2 = TransactionCorrelator.correlate(List.of(p1, p1));

        assertEquals(1, res1.size());
        assertEquals(1, res2.size());
        assertEquals(res1.get(0), res2.get(0));
    }

    @Test
    void testI_amountNormalizationScaleTwo() {
        ParsedTxn p1 = new ParsedTxn("4821", TIME_1, Direction.DEBIT, new BigDecimal("5"), "WATER CAN", null, "m-1");
        ParsedTxn p2 = new ParsedTxn("4821", TIME_1, Direction.DEBIT, new BigDecimal("5.00"), "WATER CAN", null, "m-2");

        List<NormalizedTxn> result = TransactionCorrelator.correlate(List.of(p1, p2));
        assertEquals(1, result.size());
        assertEquals(new BigDecimal("5.00"), result.get(0).amount());
        assertEquals(2, result.get(0).amount().scale());
    }
}
