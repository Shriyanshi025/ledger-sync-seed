package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.util.List;

/**
 * Moves everything already in the SQL store into the document store.
 * Safe to rerun and safe after partial failure.
 */
public final class Backfill {

    private final SqlLedgerStore source;
    private final DocumentStore target;

    public Backfill(SqlLedgerStore source, DocumentStore target) {
        this.source = source;
        this.target = target;
    }

    public Result run() {
        List<NormalizedTxn> allTxns = source.all();
        long read = allTxns.size();
        long written = 0;
        long skipped = 0;

        for (NormalizedTxn t : allTxns) {
            boolean existingFound = false;
            if (t.sourceMessageIds() != null) {
                for (String msgId : t.sourceMessageIds()) {
                    if (target.byMessageId(msgId).isPresent()) {
                        existingFound = true;
                        break;
                    }
                }
            }

            target.save(t);

            if (existingFound) {
                skipped++;
            } else {
                written++;
            }
        }

        return new Result(read, written, skipped);
    }

    public record Result(long read, long written, long skipped) {}
}
