package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Correlates and deduplicates ParsedTxn observations into NormalizedTxn instances.
 *
 * One real transaction produces exactly one NormalizedTxn. Multiple raw messages
 * evidencing the same transaction are merged so that all source message IDs are
 * captured in sourceMessageIds().
 */
public final class TransactionCorrelator {

    private TransactionCorrelator() {}

    public static List<NormalizedTxn> correlate(List<ParsedTxn> parsedTxns) {
        if (parsedTxns == null || parsedTxns.isEmpty()) {
            return List.of();
        }

        // Sort parsedTxns deterministically before clustering so processing order cannot alter output
        List<ParsedTxn> sortedInput = new ArrayList<>(parsedTxns);
        sortedInput.sort(Comparator.comparing(ParsedTxn::accountLast4)
                .thenComparing(ParsedTxn::occurredAt)
                .thenComparing(ParsedTxn::direction)
                .thenComparing(p -> p.amount().setScale(2))
                .thenComparing(ParsedTxn::sourceMessageId));

        List<Cluster> clusters = new ArrayList<>();

        for (ParsedTxn p : sortedInput) {
            BigDecimal scaledAmount = p.amount().setScale(2);
            Cluster matchedCluster = null;

            for (Cluster c : clusters) {
                if (c.accountLast4.equals(p.accountLast4())
                        && c.direction == p.direction()
                        && c.amount.compareTo(scaledAmount) == 0
                        && c.occurredAt.equals(p.occurredAt())) {

                    // Check balance compatibility: non-null conflicting balances prevent auto-merging
                    if (p.statedBalance() != null && c.hasConflictingBalance(p.statedBalance())) {
                        continue;
                    }

                    // Check merchant compatibility
                    if (p.merchant() != null && !p.merchant().isBlank() && c.hasConflictingMerchant(p.merchant())) {
                        continue;
                    }

                    matchedCluster = c;
                    break;
                }
            }

            if (matchedCluster != null) {
                matchedCluster.addEvidence(p);
            } else {
                Cluster newCluster = new Cluster(p.accountLast4(), p.occurredAt(), p.direction(), scaledAmount);
                newCluster.addEvidence(p);
                clusters.add(newCluster);
            }
        }

        List<NormalizedTxn> result = new ArrayList<>();
        for (Cluster c : clusters) {
            result.add(c.toNormalizedTxn());
        }

        // Sort output deterministically
        result.sort(Comparator.comparing(NormalizedTxn::occurredAt)
                .thenComparing(NormalizedTxn::accountLast4)
                .thenComparing(NormalizedTxn::amount)
                .thenComparing(NormalizedTxn::direction)
                .thenComparing(t -> t.sourceMessageIds().get(0)));

        return Collections.unmodifiableList(result);
    }

    public static String normalizeMerchant(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toUpperCase(Locale.ROOT);
        if (s.startsWith("UPI/P2P/")) s = s.substring(8);
        else if (s.startsWith("UPI/P2A/")) s = s.substring(8);
        else if (s.startsWith("UPI/")) s = s.substring(4);
        else if (s.startsWith("IMPS/P2A/")) s = s.substring(9);
        else if (s.startsWith("IMPS/")) s = s.substring(5);
        else if (s.startsWith("POS/")) s = s.substring(4);

        while (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        return s.replaceAll("\\s+", " ").trim();
    }

    private static class Cluster {
        final String accountLast4;
        final OffsetDateTime occurredAt;
        final Direction direction;
        final BigDecimal amount;
        final List<ParsedTxn> evidences = new ArrayList<>();
        final Set<BigDecimal> statedBalances = new TreeSet<>();
        final Set<String> sourceMessageIds = new TreeSet<>();

        Cluster(String accountLast4, OffsetDateTime occurredAt, Direction direction, BigDecimal amount) {
            this.accountLast4 = Objects.requireNonNull(accountLast4, "accountLast4");
            this.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
            this.direction = Objects.requireNonNull(direction, "direction");
            this.amount = Objects.requireNonNull(amount, "amount");
        }

        boolean hasConflictingBalance(BigDecimal balance) {
            for (BigDecimal b : statedBalances) {
                if (b.compareTo(balance) != 0) {
                    return true;
                }
            }
            return false;
        }

        boolean hasConflictingMerchant(String merchant) {
            String normNew = normalizeMerchant(merchant);
            if (normNew.isEmpty()) return false;

            for (ParsedTxn e : evidences) {
                String normExisting = normalizeMerchant(e.merchant());
                if (!normExisting.isEmpty() && !normExisting.equals(normNew)) {
                    return true;
                }
            }
            return false;
        }

        void addEvidence(ParsedTxn p) {
            evidences.add(p);
            if (p.statedBalance() != null) {
                statedBalances.add(p.statedBalance().setScale(2));
            }
            sourceMessageIds.add(p.sourceMessageId());
        }

        NormalizedTxn toNormalizedTxn() {
            String selectedMerchant = "";
            for (ParsedTxn p : evidences) {
                if (p.merchant() != null && !p.merchant().isBlank()) {
                    selectedMerchant = p.merchant().trim();
                    break;
                }
            }

            Category cat = direction == Direction.DEBIT ? Category.SPEND : Category.INCOME;
            return new NormalizedTxn(
                    accountLast4,
                    occurredAt,
                    direction,
                    amount,
                    cat,
                    selectedMerchant,
                    List.copyOf(sourceMessageIds));
        }
    }
}
