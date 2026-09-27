package in.simplifymoney.ledgersync.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ParsersTest {

    private final Parsers parsers = new Parsers();

    @Test
    void parsesHdfcDebitSms() {
        RawMessage m = new RawMessage(
                "m-00004-f52f55", "sms", "AD-HDFCBK-S",
                OffsetDateTime.parse("2026-07-01T11:52:00+05:30"), "dev-1",
                "Rs 99.99 debited from a/c **4821 on 01-07-26 at 11:52 to BIGBASKET. Avl Bal: Rs.93,111.41. Not you? Call 18002586161");

        Optional<ParsedTxn> res = parsers.parse(m);
        assertTrue(res.isPresent());
        ParsedTxn p = res.get();
        assertEquals("4821", p.accountLast4());
        assertEquals(OffsetDateTime.parse("2026-07-01T11:52:00+05:30"), p.occurredAt());
        assertEquals(Direction.DEBIT, p.direction());
        assertEquals(new BigDecimal("99.99"), p.amount());
        assertEquals("BIGBASKET", p.merchant());
        assertEquals(new BigDecimal("93111.41"), p.statedBalance());
    }

    @Test
    void parsesHdfcCreditSms() {
        RawMessage m = new RawMessage(
                "m-00001-31eb24", "sms", "AD-HDFCBK-S",
                OffsetDateTime.parse("2026-07-01T09:03:00+05:30"), "dev-1",
                "Rs.45,000.00 credited to a/c **4821 on 01-07-26 at 09:02 by SALARY CREDIT. Avl Bal: Rs.93,211.40");

        Optional<ParsedTxn> res = parsers.parse(m);
        assertTrue(res.isPresent());
        ParsedTxn p = res.get();
        assertEquals("4821", p.accountLast4());
        assertEquals(OffsetDateTime.parse("2026-07-01T09:02:00+05:30"), p.occurredAt());
        assertEquals(Direction.CREDIT, p.direction());
        assertEquals(new BigDecimal("45000.00"), p.amount());
        assertEquals("SALARY CREDIT", p.merchant());
        assertEquals(new BigDecimal("93211.40"), p.statedBalance());
    }

    @Test
    void parsesWholeRupeeDebitSms() {
        RawMessage m = new RawMessage(
                "m-00022-2f118b", "sms", "AD-HDFCBK-S",
                OffsetDateTime.parse("2026-07-04T11:56:00+05:30"), "dev-1",
                "Rs.5 debited from a/c **4821 on 04-07-26 at 11:54 to UPI/WATER CAN. Avl Bal: Rs.92,213.10. Not you? Call 18002586161");

        Optional<ParsedTxn> res = parsers.parse(m);
        assertTrue(res.isPresent());
        ParsedTxn p = res.get();
        assertEquals("4821", p.accountLast4());
        assertEquals(new BigDecimal("5.00"), p.amount());
        assertEquals("UPI/WATER CAN", p.merchant());
    }

    @Test
    void parsesIciciV2SmsFormat() {
        RawMessage m = new RawMessage(
                "m-00162-9709eb", "sms", "VM-ICICIB-T",
                OffsetDateTime.parse("2026-07-23T18:41:00+05:30"), "dev-1",
                "ICICI Bank Acct XX9075 Dr INR 5 on 23-Jul-2026 18:41; UPI/BARBER ref no 154245459403. BalAvl Rs 52,841.30");

        Optional<ParsedTxn> res = parsers.parse(m);
        assertTrue(res.isPresent());
        ParsedTxn p = res.get();
        assertEquals("9075", p.accountLast4());
        assertEquals(OffsetDateTime.parse("2026-07-23T18:41:00+05:30"), p.occurredAt());
        assertEquals(Direction.DEBIT, p.direction());
        assertEquals(new BigDecimal("5.00"), p.amount());
        assertEquals("UPI/BARBER", p.merchant());
        assertEquals(new BigDecimal("52841.30"), p.statedBalance());
    }

    @Test
    void parsesEmailDebit() {
        RawMessage m = new RawMessage(
                "m-00006-74ad8c", "email", "alerts@hdfcbank.net",
                OffsetDateTime.parse("2026-07-01T19:00:00+05:30"), "dev-1",
                "Date: Wed, 01 Jul 2026 18:57:00 +0530\nSubject: Transaction alert on your account\n\nDear Customer,\n\nYour account ending 4821 has been debited with INR 99.99.\nMerchant / Remarks: IRCTC\nTransaction reference: 8085121323\n\nThis is a system generated email.");

        Optional<ParsedTxn> res = parsers.parse(m);
        assertTrue(res.isPresent());
        ParsedTxn p = res.get();
        assertEquals("4821", p.accountLast4());
        assertEquals(OffsetDateTime.parse("2026-07-01T18:57:00+05:30"), p.occurredAt());
        assertEquals(Direction.DEBIT, p.direction());
        assertEquals(new BigDecimal("99.99"), p.amount());
        assertEquals("IRCTC", p.merchant());
        assertNull(p.statedBalance());
    }

    @Test
    void parsesEmailCredit() {
        RawMessage m = new RawMessage(
                "m-00002-69e4cd", "email", "alerts@hdfcbank.net",
                OffsetDateTime.parse("2026-07-01T09:47:00+05:30"), "dev-1",
                "Date: Wed, 01 Jul 2026 09:02:00 +0530\nSubject: Transaction alert on your account\n\nDear Customer,\n\nYour account ending 4821 has been credited with INR 45,000.\nMerchant / Remarks: SALARY CREDIT\nTransaction reference: 1597155421\n\nThis is a system generated email.");

        Optional<ParsedTxn> res = parsers.parse(m);
        assertTrue(res.isPresent());
        ParsedTxn p = res.get();
        assertEquals("4821", p.accountLast4());
        assertEquals(OffsetDateTime.parse("2026-07-01T09:02:00+05:30"), p.occurredAt());
        assertEquals(Direction.CREDIT, p.direction());
        assertEquals(new BigDecimal("45000.00"), p.amount());
        assertEquals("SALARY CREDIT", p.merchant());
        assertNull(p.statedBalance());
    }

    @Test
    void parsesEmailDateWithUtcOffsetAndNormalizesToIst() {
        RawMessage m = new RawMessage(
                "m-00131-cd229b", "email", "alerts@hdfcbank.net",
                OffsetDateTime.parse("2026-07-19T00:26:00+05:30"), "dev-1",
                "Date: Sat, 18 Jul 2026 18:50:00 +0000\nSubject: Transaction alert on your account\n\nDear Customer,\n\nYour account ending 4821 has been debited with INR 412.67.\nMerchant / Remarks: UBER INDIA\nTransaction reference: 4190129089\n\nThis is a system generated email.");

        Optional<ParsedTxn> res = parsers.parse(m);
        assertTrue(res.isPresent());
        ParsedTxn p = res.get();
        assertEquals("4821", p.accountLast4());
        assertEquals(OffsetDateTime.parse("2026-07-19T00:20:00+05:30"), p.occurredAt());
        assertEquals(new BigDecimal("412.67"), p.amount());
        assertEquals("UBER INDIA", p.merchant());
    }

    @Test
    void returnsEmptyForNonTransactionMessage() {
        RawMessage m = new RawMessage(
                "m-00319-3e76e0", "sms", "AX-SWGGYX",
                OffsetDateTime.parse("2026-07-15T13:05:00+05:30"), "dev-1",
                "Your Swiggy order is on the way! Rahul is 5 mins away.");

        Optional<ParsedTxn> res = parsers.parse(m);
        assertTrue(res.isEmpty());
    }
}
