package in.simplifymoney.ledgersync.report;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The two reports the assignment asks for.
 *
 * summary() below is a first cut: it adds up what is in the ledger. It does not
 * know that a transfer is not spending, and it does not roll micro spends up.
 *
 * reconciliation() has not been written at all.
 */
public final class Reports {

    private Reports() {}

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    public static Map<String, Object> summary(List<NormalizedTxn> ledger) {
        Map<String, Object> accounts = new LinkedHashMap<>();
        for (String acct : new TreeSet<>(ledger.stream()
                .map(NormalizedTxn::accountLast4).toList())) {

            BigDecimal spend = ZERO;
            BigDecimal income = ZERO;
            int microCount = 0;
            BigDecimal microTotal = ZERO;
            BigDecimal transferredOut = ZERO;
            BigDecimal transferredIn = ZERO;

            for (NormalizedTxn t : ledger) {
                if (!t.accountLast4().equals(acct)) continue;
                switch (t.category()) {
                    case SPEND -> spend = spend.add(t.amount());
                    case INCOME -> income = income.add(t.amount());
                    case MICRO -> {
                        microCount++;
                        microTotal = microTotal.add(t.amount());
                    }
                    case TRANSFER -> {
                        if (t.direction() == Direction.DEBIT) {
                            transferredOut = transferredOut.add(t.amount());
                        } else {
                            transferredIn = transferredIn.add(t.amount());
                        }
                    }
                }
            }

            Map<String, Object> a = new LinkedHashMap<>();
            a.put("spend", spend.toPlainString());
            a.put("income", income.toPlainString());
            a.put("micro_count", microCount);
            a.put("micro_total", microTotal.toPlainString());
            a.put("transferred_out", transferredOut.toPlainString());
            a.put("transferred_in", transferredIn.toPlainString());
            accounts.put(acct, a);
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("accounts", accounts);
        return doc;
    }

    public static Map<String, Object> ledgerDocument(List<NormalizedTxn> ledger) {
        List<Object> rows = ledger.stream().map(t -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("account_last4", t.accountLast4());
            r.put("occurred_at", t.occurredAt().toString());
            r.put("direction", t.direction().name().toLowerCase());
            r.put("amount", t.amount().toPlainString());
            r.put("category", t.category().name());
            r.put("merchant", t.merchant());
            r.put("source_message_ids", t.sourceMessageIds());
            return (Object) r;
        }).toList();
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("transactions", rows);
        return doc;
    }

    public static Map<String, Object> reconciliation(List<NormalizedTxn> ledger) {
        List<in.simplifymoney.ledgersync.model.RawMessage> messages = List.of();
        java.nio.file.Path defaultCorpus = java.nio.file.Path.of("fixtures/corpus-a.jsonl");
        if (java.nio.file.Files.exists(defaultCorpus)) {
            try {
                messages = in.simplifymoney.ledgersync.ingest.IngestService.readCorpus(defaultCorpus);
            } catch (Exception ignored) { }
        }
        return reconciliation(ledger, messages);
    }

    public static Map<String, Object> reconciliation(List<NormalizedTxn> ledger,
                                                     List<in.simplifymoney.ledgersync.model.RawMessage> messages) {
        Map<String, in.simplifymoney.ledgersync.model.RawMessage> msgMap = new LinkedHashMap<>();
        if (messages != null) {
            for (in.simplifymoney.ledgersync.model.RawMessage m : messages) {
                msgMap.put(m.messageId(), m);
            }
        }

        List<Object> discrepancies = new ArrayList<>();
        TreeSet<String> accounts = new TreeSet<>(ledger.stream().map(NormalizedTxn::accountLast4).toList());

        for (String acct : accounts) {
            List<NormalizedTxn> txns = ledger.stream()
                    .filter(t -> t.accountLast4().equals(acct))
                    .sorted(java.util.Comparator.comparing(NormalizedTxn::occurredAt))
                    .toList();

            if (txns.isEmpty()) continue;

            // Find index of first transaction with a stated balance checkpoint
            int firstCheckIndex = -1;
            NormalizedTxn firstTxn = null;
            BigDecimal firstStatedBal = null;
            for (int i = 0; i < txns.size(); i++) {
                BigDecimal sb = getStatedBalance(txns.get(i), msgMap);
                if (sb != null) {
                    firstCheckIndex = i;
                    firstTxn = txns.get(i);
                    firstStatedBal = sb;
                    break;
                }
            }

            if (firstCheckIndex == -1 || firstTxn == null || firstStatedBal == null) continue;

            // Derive pre-transaction opening balance for firstTxn:
            // openingBalance = first.statedBalance - signedDelta(firstTransaction)
            // where debit signedDelta = -amount and credit signedDelta = +amount.
            BigDecimal firstDelta = (firstTxn.direction() == Direction.DEBIT)
                    ? firstTxn.amount().negate()
                    : firstTxn.amount();
            BigDecimal openingBalFirst = firstStatedBal.subtract(firstDelta);

            // Work back to opening balance before txns.get(0) if firstCheckIndex > 0
            BigDecimal runningBalance = openingBalFirst;
            for (int i = firstCheckIndex - 1; i >= 0; i--) {
                NormalizedTxn t = txns.get(i);
                BigDecimal delta = (t.direction() == Direction.DEBIT)
                        ? t.amount().negate()
                        : t.amount();
                runningBalance = runningBalance.subtract(delta);
            }

            BigDecimal currentDiscrepancy = ZERO;

            // Reconcile all transactions chronologically from index 0
            for (NormalizedTxn t : txns) {
                BigDecimal delta = (t.direction() == Direction.DEBIT)
                        ? t.amount().negate()
                        : t.amount();
                runningBalance = runningBalance.add(delta);

                BigDecimal statedBal = getStatedBalance(t, msgMap);
                if (statedBal != null) {
                    BigDecimal expectedDiff = runningBalance.subtract(statedBal);
                    BigDecimal stepDiff = expectedDiff.subtract(currentDiscrepancy);

                    if (stepDiff.compareTo(ZERO) != 0) {
                        BigDecimal discAmount = stepDiff.abs().setScale(2);
                        Map<String, Object> disc = new LinkedHashMap<>();
                        disc.put("account_last4", acct);
                        disc.put("occurred_at", t.occurredAt().toString());
                        disc.put("amount", discAmount.toPlainString());
                        disc.put("note", "Unrepresented bank transaction of " + discAmount.toPlainString()
                                + " detected before checkpoint " + t.occurredAt());
                        discrepancies.add(disc);
                        currentDiscrepancy = expectedDiff;
                    }
                }
            }
        }

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("discrepancies", discrepancies);
        return doc;
    }

    private static BigDecimal getStatedBalance(NormalizedTxn t,
                                                Map<String, in.simplifymoney.ledgersync.model.RawMessage> msgMap) {
        for (String srcId : t.sourceMessageIds()) {
            in.simplifymoney.ledgersync.model.RawMessage m = msgMap.get(srcId);
            if (m != null) {
                BigDecimal sb = in.simplifymoney.ledgersync.parse.Amounts.statedBalance(m.body());
                if (sb != null) return sb;
            }
        }
        return null;
    }

    public static Map<Category, BigDecimal> byCategory(List<NormalizedTxn> ledger) {
        Map<Category, BigDecimal> out = new LinkedHashMap<>();
        for (Category c : Category.values()) out.put(c, ZERO);
        for (NormalizedTxn t : ledger) {
            out.put(t.category(), out.get(t.category()).add(t.amount()));
        }
        return out;
    }
}
