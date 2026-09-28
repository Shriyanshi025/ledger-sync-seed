package in.simplifymoney.ledgersync.classify;

import static org.junit.jupiter.api.Assertions.assertEquals;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.model.RawMessage;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class TransactionClassifierTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-07-05T11:00:00+05:30");

    @Test
    void classifies100UpiDebitAsMicro() {
        NormalizedTxn t = new NormalizedTxn("4821", NOW, Direction.DEBIT,
                new BigDecimal("100.00"), Category.SPEND, "UPI/STATIONERY", List.of("m1"));
        List<NormalizedTxn> classified = TransactionClassifier.classify(List.of(t));
        assertEquals(Category.MICRO, classified.get(0).category());
    }

    @Test
    void classifies100Point01UpiDebitAsSpend() {
        NormalizedTxn t = new NormalizedTxn("4821", NOW, Direction.DEBIT,
                new BigDecimal("100.01"), Category.SPEND, "UPI/STORE", List.of("m1"));
        List<NormalizedTxn> classified = TransactionClassifier.classify(List.of(t));
        assertEquals(Category.SPEND, classified.get(0).category());
    }

    @Test
    void classifies5UpiDebitAsMicro() {
        NormalizedTxn t = new NormalizedTxn("4821", NOW, Direction.DEBIT,
                new BigDecimal("5.00"), Category.SPEND, "UPI/WATER CAN", List.of("m1"));
        List<NormalizedTxn> classified = TransactionClassifier.classify(List.of(t));
        assertEquals(Category.MICRO, classified.get(0).category());
    }

    @Test
    void classifiesNonUpi50DebitAsSpend() {
        NormalizedTxn t = new NormalizedTxn("4821", NOW, Direction.DEBIT,
                new BigDecimal("50.00"), Category.SPEND, "AMAZON PAY", List.of("m1"));
        List<NormalizedTxn> classified = TransactionClassifier.classify(List.of(t));
        assertEquals(Category.SPEND, classified.get(0).category());
    }

    @Test
    void classifiesExplicitPaired8000TransferBothLegsAsTransfer() {
        NormalizedTxn t1 = new NormalizedTxn("4821", NOW, Direction.DEBIT,
                new BigDecimal("8000.00"), Category.SPEND, "IMPS/P2A/PARAG KAPOOR", List.of("m1"));
        NormalizedTxn t2 = new NormalizedTxn("9075", NOW.plusMinutes(2), Direction.CREDIT,
                new BigDecimal("8000.00"), Category.INCOME, "IMPS/P2A/PARAG KAPOOR", List.of("m2"));

        List<NormalizedTxn> classified = TransactionClassifier.classify(List.of(t1, t2));
        assertEquals(Category.TRANSFER, classified.get(0).category());
        assertEquals(Category.TRANSFER, classified.get(1).category());
    }

    @Test
    void rejectsTransferWithDifferentAmounts() {
        NormalizedTxn t1 = new NormalizedTxn("4821", NOW, Direction.DEBIT,
                new BigDecimal("8000.00"), Category.SPEND, "IMPS/P2A/PARAG KAPOOR", List.of("m1"));
        NormalizedTxn t2 = new NormalizedTxn("9075", NOW.plusMinutes(2), Direction.CREDIT,
                new BigDecimal("8000.01"), Category.INCOME, "IMPS/P2A/PARAG KAPOOR", List.of("m2"));

        List<NormalizedTxn> classified = TransactionClassifier.classify(List.of(t1, t2));
        assertEquals(Category.SPEND, classified.get(0).category());
        assertEquals(Category.INCOME, classified.get(1).category());
    }

    @Test
    void rejectsTransferWithEqualAmountButNoSameOwnerEvidence() {
        NormalizedTxn t1 = new NormalizedTxn("4821", NOW, Direction.DEBIT,
                new BigDecimal("1234.56"), Category.SPEND, "SWIGGY", List.of("m1"));
        NormalizedTxn t2 = new NormalizedTxn("9075", NOW.plusMinutes(1), Direction.CREDIT,
                new BigDecimal("1234.56"), Category.INCOME, "SALARY CREDIT", List.of("m2"));

        List<NormalizedTxn> classified = TransactionClassifier.classify(List.of(t1, t2));
        assertEquals(Category.SPEND, classified.get(0).category());
        assertEquals(Category.INCOME, classified.get(1).category());
    }

    @Test
    void classifiesNeftInwardSelfWithoutMatchingTrackedDebitAsIncome() {
        NormalizedTxn t = new NormalizedTxn("9075", NOW, Direction.CREDIT,
                new BigDecimal("18000.00"), Category.INCOME, "NEFT INWARD SELF", List.of("m1"));

        List<NormalizedTxn> classified = TransactionClassifier.classify(List.of(t));
        assertEquals(Category.INCOME, classified.get(0).category());
    }

    @Test
    void rejectsThirdPartyPaymentWithoutPairedOwnAccountLegAsTransfer() {
        NormalizedTxn t = new NormalizedTxn("4821", NOW, Direction.DEBIT,
                new BigDecimal("12000.00"), Category.SPEND, "IMPS/P2A/RAHUL SHARMA", List.of("m1"));

        List<NormalizedTxn> classified = TransactionClassifier.classify(List.of(t));
        assertEquals(Category.SPEND, classified.get(0).category());
    }

    @Test
    void duplicateEvidenceRemainsOneTransactionWithPreservedSources() {
        NormalizedTxn t = new NormalizedTxn("4821", NOW, Direction.DEBIT,
                new BigDecimal("25.00"), Category.SPEND, "UPI/BARBER", List.of("m1", "m2"));

        List<NormalizedTxn> classified = TransactionClassifier.classify(List.of(t));
        assertEquals(1, classified.size());
        assertEquals(Category.MICRO, classified.get(0).category());
        assertEquals(List.of("m1", "m2"), classified.get(0).sourceMessageIds());
    }

    @Test
    void classifiesExplicitPairedTransferWithSelfRemarkAsTransfer() {
        NormalizedTxn t1 = new NormalizedTxn("4821", NOW, Direction.DEBIT,
                new BigDecimal("17000.00"), Category.SPEND, "TRANSFER SELF", List.of("m1"));
        NormalizedTxn t2 = new NormalizedTxn("9075", NOW.plusMinutes(1), Direction.CREDIT,
                new BigDecimal("17000.00"), Category.INCOME, "NEFT INWARD SELF", List.of("m2"));

        List<NormalizedTxn> classified = TransactionClassifier.classify(List.of(t1, t2));
        assertEquals(Category.TRANSFER, classified.get(0).category());
        assertEquals(Category.TRANSFER, classified.get(1).category());
    }

    @Test
    void rejectsFalsePositiveWithTownOrBrownInMerchantName() {
        NormalizedTxn t1 = new NormalizedTxn("4821", NOW, Direction.DEBIT,
                new BigDecimal("500.00"), Category.SPEND, "BROWN TOWN STORE", List.of("m1"));
        NormalizedTxn t2 = new NormalizedTxn("9075", NOW.plusMinutes(1), Direction.CREDIT,
                new BigDecimal("500.00"), Category.INCOME, "BROWN TOWN REFUND", List.of("m2"));

        List<NormalizedTxn> classified = TransactionClassifier.classify(List.of(t1, t2));
        assertEquals(Category.SPEND, classified.get(0).category());
        assertEquals(Category.INCOME, classified.get(1).category());
    }
}
