package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.NormalizedTxn;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Proves the SQL and Document stores agree, and says precisely where they do not.
 */
public final class ConsistencyChecker {

    private final SqlLedgerStore sql;
    private final DocumentStore documents;

    public ConsistencyChecker(SqlLedgerStore sql, DocumentStore documents) {
        this.sql = sql;
        this.documents = documents;
    }

    public List<Divergence> check() {
        List<Divergence> divergences = new ArrayList<>();

        List<NormalizedTxn> sqlTxns = sql.all();
        Map<String, NormalizedTxn> sqlMap = new LinkedHashMap<>();
        for (NormalizedTxn t : sqlTxns) {
            sqlMap.put(SqlLedgerStore.computeDedupKey(t), t);
        }

        List<NormalizedTxn> docTxns = getDocumentTxns();
        Map<String, NormalizedTxn> docMap = new LinkedHashMap<>();
        for (NormalizedTxn t : docTxns) {
            docMap.put(SqlLedgerStore.computeDedupKey(t), t);
        }

        Set<String> allKeys = new java.util.TreeSet<>();
        allKeys.addAll(sqlMap.keySet());
        allKeys.addAll(docMap.keySet());

        for (String key : allKeys) {
            NormalizedTxn inSql = sqlMap.get(key);
            NormalizedTxn inDoc = docMap.get(key);

            if (inSql == null && inDoc != null) {
                divergences.add(new Divergence("Transaction missing in SQL store (Document-only key: " + key + ")",
                        "null", inDoc.toString()));
                continue;
            }

            if (inDoc == null && inSql != null) {
                divergences.add(new Divergence("Transaction missing in Document store (SQL-only key: " + key + ")",
                        inSql.toString(), "null"));
                continue;
            }

            // Field level comparisons
            if (!inSql.amount().equals(inDoc.amount())) {
                divergences.add(new Divergence("Amount mismatch for key [" + key + "]",
                        inSql.amount().toPlainString(), inDoc.amount().toPlainString()));
            }

            if (inSql.category() != inDoc.category()) {
                divergences.add(new Divergence("Category mismatch for key [" + key + "]",
                        inSql.category().name(), inDoc.category().name()));
            }

            if (inSql.direction() != inDoc.direction()) {
                divergences.add(new Divergence("Direction mismatch for key [" + key + "]",
                        inSql.direction().name(), inDoc.direction().name()));
            }

            if (!inSql.occurredAt().equals(inDoc.occurredAt())) {
                divergences.add(new Divergence("OccurredAt mismatch for key [" + key + "]",
                        inSql.occurredAt().toString(), inDoc.occurredAt().toString()));
            }

            String sqlMerch = inSql.merchant() != null ? inSql.merchant() : "";
            String docMerch = inDoc.merchant() != null ? inDoc.merchant() : "";
            if (!sqlMerch.equals(docMerch)) {
                divergences.add(new Divergence("Merchant mismatch for key [" + key + "]",
                        sqlMerch, docMerch));
            }

            Set<String> sqlMsgSet = new TreeSet<>(inSql.sourceMessageIds());
            Set<String> docMsgSet = new TreeSet<>(inDoc.sourceMessageIds());
            if (!sqlMsgSet.equals(docMsgSet)) {
                divergences.add(new Divergence("SourceMessageIds mismatch for key [" + key + "]",
                        sqlMsgSet.toString(), docMsgSet.toString()));
            }
        }

        return divergences;
    }

    private List<NormalizedTxn> getDocumentTxns() {
        if (documents instanceof DynamoDocumentStore dds) {
            return dds.all();
        }
        if (documents instanceof DynamoDocumentStore.InMemoryDocumentStore inMem) {
            return inMem.all();
        }
        return List.of();
    }

    /** One place the two stores disagree. */
    public record Divergence(String what, String inSql, String inDocuments) {}
}
