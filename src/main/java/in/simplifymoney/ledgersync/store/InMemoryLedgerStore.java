package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Used by SelfCheck and by tests. Keeps everything it is given. */
public final class InMemoryLedgerStore implements LedgerStore {

    private final List<NormalizedTxn> rows = new ArrayList<>();

    @Override
    public synchronized boolean save(NormalizedTxn txn) {
        String key = SqlLedgerStore.computeDedupKey(txn);
        for (int i = 0; i < rows.size(); i++) {
            NormalizedTxn existing = rows.get(i);
            if (SqlLedgerStore.computeDedupKey(existing).equals(key)) {
                java.util.Set<String> combined = new java.util.TreeSet<>(existing.sourceMessageIds());
                combined.addAll(txn.sourceMessageIds());
                List<String> combinedList = new ArrayList<>(combined);
                if (!combinedList.equals(existing.sourceMessageIds())) {
                    NormalizedTxn updated = new NormalizedTxn(
                            existing.accountLast4(),
                            existing.occurredAt(),
                            existing.direction(),
                            existing.amount(),
                            txn.category(),
                            txn.merchant(),
                            combinedList
                    );
                    rows.set(i, updated);
                }
                return false;
            }
        }
        rows.add(txn);
        return true;
    }

    @Override public List<NormalizedTxn> all() { return Collections.unmodifiableList(rows); }

    @Override public long count() { return rows.size(); }
}
