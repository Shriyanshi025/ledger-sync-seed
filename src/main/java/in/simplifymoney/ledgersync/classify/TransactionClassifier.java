package in.simplifymoney.ledgersync.classify;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.model.RawMessage;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;

/**
 * Classifies correlated NormalizedTxn records into SPEND, INCOME, MICRO, or TRANSFER.
 */
public final class TransactionClassifier {

    private TransactionClassifier() {}

    public static List<NormalizedTxn> classify(List<NormalizedTxn> txns) {
        return classify(txns, List.of());
    }

    public static List<NormalizedTxn> classify(List<NormalizedTxn> txns, List<RawMessage> messages) {
        if (txns == null || txns.isEmpty()) {
            return List.of();
        }

        Map<String, RawMessage> messageMap = new HashMap<>();
        if (messages != null) {
            for (RawMessage m : messages) {
                messageMap.put(m.messageId(), m);
            }
        }

        int n = txns.size();
        Category[] categories = new Category[n];
        boolean[] matchedTransfer = new boolean[n];

        // 1. TRANSFER Matching
        // Look for paired transactions across different accounts with opposite directions,
        // exact same amount, timestamp within 5 minutes, and explicit same-owner transfer evidence.
        for (int i = 0; i < n; i++) {
            if (matchedTransfer[i]) continue;
            NormalizedTxn t1 = txns.get(i);

            for (int j = i + 1; j < n; j++) {
                if (matchedTransfer[j]) continue;
                NormalizedTxn t2 = txns.get(j);

                if (isTransferPair(t1, t2, messageMap)) {
                    matchedTransfer[i] = true;
                    matchedTransfer[j] = true;
                    categories[i] = Category.TRANSFER;
                    categories[j] = Category.TRANSFER;
                    break;
                }
            }
        }

        // 2. MICRO & SPEND / INCOME Classification
        List<NormalizedTxn> result = new ArrayList<>(n);
        BigDecimal microLimit = new BigDecimal("100.00");

        for (int i = 0; i < n; i++) {
            NormalizedTxn t = txns.get(i);

            if (categories[i] == Category.TRANSFER) {
                result.add(withCategory(t, Category.TRANSFER));
                continue;
            }

            if (t.direction() == Direction.DEBIT
                    && t.amount().compareTo(microLimit) <= 0
                    && hasUpiEvidence(t, messageMap)) {
                result.add(withCategory(t, Category.MICRO));
            } else if (t.direction() == Direction.DEBIT) {
                result.add(withCategory(t, Category.SPEND));
            } else {
                result.add(withCategory(t, Category.INCOME));
            }
        }

        return Collections.unmodifiableList(result);
    }

    private static boolean isTransferPair(NormalizedTxn t1, NormalizedTxn t2, Map<String, RawMessage> messageMap) {
        // Must be on different accounts
        if (t1.accountLast4().equals(t2.accountLast4())) {
            return false;
        }

        // Must have opposite directions
        if (t1.direction() == t2.direction()) {
            return false;
        }

        // Must have exact same amount
        if (t1.amount().compareTo(t2.amount()) != 0) {
            return false;
        }

        // Must occur within 5 minutes (300 seconds)
        Duration gap = Duration.between(t1.occurredAt(), t2.occurredAt()).abs();
        if (gap.compareTo(Duration.ofMinutes(5)) > 0) {
            return false;
        }

        // Must have explicit same-owner transfer evidence
        return hasSameOwnerEvidence(t1, t2, messageMap);
    }

    private static boolean hasSameOwnerEvidence(NormalizedTxn t1, NormalizedTxn t2, Map<String, RawMessage> messageMap) {
        String m1 = t1.merchant().toUpperCase(Locale.ROOT);
        String m2 = t2.merchant().toUpperCase(Locale.ROOT);

        // Explicit same-owner evidence: e.g. PARAG KAPOOR / PARAG / KAPOOR / SELF / OWN
        boolean m1HasOwner = isOwnerString(m1);
        boolean m2HasOwner = isOwnerString(m2);

        if (m1HasOwner && m2HasOwner) {
            return true;
        }

        // Check source message bodies if merchant string normalization stripped details
        boolean msg1HasOwner = hasOwnerInMessages(t1, messageMap);
        boolean msg2HasOwner = hasOwnerInMessages(t2, messageMap);

        return (m1HasOwner || msg1HasOwner) && (m2HasOwner || msg2HasOwner);
    }

    private static boolean hasOwnerInMessages(NormalizedTxn t, Map<String, RawMessage> messageMap) {
        for (String srcId : t.sourceMessageIds()) {
            RawMessage m = messageMap.get(srcId);
            if (m != null) {
                String body = m.body().toUpperCase(Locale.ROOT);
                if (isOwnerString(body)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isOwnerString(String s) {
        return s.contains("PARAG") || s.contains("KAPOOR") || s.contains("SELF")
                || s.contains("OWN ACCOUNT") || s.contains("OWN ACCT") || s.contains("OWN A/C");
    }

    private static boolean hasUpiEvidence(NormalizedTxn t, Map<String, RawMessage> messageMap) {
        String merch = t.merchant().toUpperCase(Locale.ROOT);
        if (merch.contains("UPI")) {
            return true;
        }
        for (String srcId : t.sourceMessageIds()) {
            RawMessage m = messageMap.get(srcId);
            if (m != null && m.body().toUpperCase(Locale.ROOT).contains("UPI")) {
                return true;
            }
        }
        return false;
    }

    private static NormalizedTxn withCategory(NormalizedTxn t, Category category) {
        return new NormalizedTxn(
                t.accountLast4(),
                t.occurredAt(),
                t.direction(),
                t.amount(),
                category,
                t.merchant(),
                t.sourceMessageIds());
    }
}
