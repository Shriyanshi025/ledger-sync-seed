package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bank transaction alert emails.
 *
 * Handles transaction alert emails from banks (e.g. HDFC Bank, ICICI Bank).
 * Extracts accountLast4, occurredAt (from Date header normalized to IST),
 * direction, amount, and merchant. Stated balance is null.
 */
public final class EmailParser implements MessageParser {

    private static final Pattern DATE_HEADER = Pattern.compile(
            "^Date:\\s*(?<date>.+)$", Pattern.MULTILINE);

    private static final Pattern ALERT_BODY = Pattern.compile(
            "Your account ending (?<acct>\\d{4}) has been (?<dir>credited|debited) with (?:INR|Rs\\.?)\\s*(?<amount>[0-9,]+(?:\\.[0-9]{1,2})?)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern MERCHANT = Pattern.compile(
            "Merchant / Remarks:\\s*(?<merchant>.+)$", Pattern.MULTILINE);

    @Override
    public boolean supports(RawMessage m) {
        return "email".equals(m.channel());
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        String body = m.body();

        Matcher alert = ALERT_BODY.matcher(body);
        if (!alert.find()) return Optional.empty();

        String acct = alert.group("acct");
        Direction dir = "credited".equalsIgnoreCase(alert.group("dir")) ? Direction.CREDIT : Direction.DEBIT;

        BigDecimal amount = Amounts.first(body);
        if (amount == null) return Optional.empty();

        OffsetDateTime at = null;
        Matcher dateMatcher = DATE_HEADER.matcher(body);
        if (dateMatcher.find()) {
            at = Dates.parseEmailDate(dateMatcher.group("date"));
        }
        if (at == null) {
            at = m.receivedAt();
        }

        String merchant = "";
        Matcher merchantMatcher = MERCHANT.matcher(body);
        if (merchantMatcher.find()) {
            merchant = merchantMatcher.group("merchant").trim();
        }

        return Optional.of(new ParsedTxn(acct, at, dir, amount, merchant, null, m.messageId()));
    }
}
