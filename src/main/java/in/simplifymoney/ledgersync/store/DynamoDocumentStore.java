package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * DynamoDB Local DocumentStore implementation using JDK standard HTTP & JSON,
 * requiring zero external SDK runtime or build dependencies.
 */
public final class DynamoDocumentStore implements DocumentStore, AutoCloseable {

    private static final String TABLE_NAME = "LedgerTransactions";
    private static final String GSI1_NAME = "GSI1";
    private static final String GSI2_NAME = "GSI2";
    private static final String LOCAL_ACCESS_KEY_ID = "local";
    private static final String LOCAL_SECRET_ACCESS_KEY = "local";
    private static final String AWS_REGION = "us-east-1";
    private static final DateTimeFormatter AWS_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final URI endpoint;
    private final HttpClient httpClient;
    private final boolean isDynamoAvailable;
    private final InMemoryDocumentStore internalStore = new InMemoryDocumentStore();

    private QueryMetrics lastQueryMetrics = new QueryMetrics(0, 0);

    public record QueryMetrics(int examined, int returned) {}

    public DynamoDocumentStore() {
        this(URI.create("http://localhost:8000"));
    }

    public DynamoDocumentStore(URI endpoint) {
        this.endpoint = endpoint;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(500))
                .build();
        this.isDynamoAvailable = checkEndpointAvailable();
        if (this.isDynamoAvailable) {
            ensureTableCreated();
        }
    }

    public boolean isDynamoAvailable() {
        return isDynamoAvailable;
    }

    public QueryMetrics getLastQueryMetrics() {
        return lastQueryMetrics;
    }

    private boolean checkEndpointAvailable() {
        try {
            HttpRequest req = signedRequest("DynamoDB_20120810.ListTables", "{}")
                    .timeout(Duration.ofMillis(500))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private void ensureTableCreated() {
        try {
            Map<String, Object> desc = sendDynamoRequest("DynamoDB_20120810.DescribeTable", Map.of("TableName", TABLE_NAME));
            if (desc.containsKey("Table")) return;
        } catch (Exception ignored) { }

        Map<String, Object> createReq = Map.of(
                "TableName", TABLE_NAME,
                "AttributeDefinitions", List.of(
                        Map.of("AttributeName", "PK", "AttributeType", "S"),
                        Map.of("AttributeName", "SK", "AttributeType", "S"),
                        Map.of("AttributeName", "GSI1_PK", "AttributeType", "S"),
                        Map.of("AttributeName", "GSI1_SK", "AttributeType", "S"),
                        Map.of("AttributeName", "GSI2_PK", "AttributeType", "S"),
                        Map.of("AttributeName", "GSI2_SK", "AttributeType", "S")
                ),
                "KeySchema", List.of(
                        Map.of("AttributeName", "PK", "KeyType", "HASH"),
                        Map.of("AttributeName", "SK", "KeyType", "RANGE")
                ),
                "GlobalSecondaryIndexes", List.of(
                        Map.of(
                                "IndexName", GSI1_NAME,
                                "KeySchema", List.of(
                                        Map.of("AttributeName", "GSI1_PK", "KeyType", "HASH"),
                                        Map.of("AttributeName", "GSI1_SK", "KeyType", "RANGE")
                                ),
                                "Projection", Map.of("ProjectionType", "ALL"),
                                "ProvisionedThroughput", Map.of("ReadCapacityUnits", 5, "WriteCapacityUnits", 5)
                        ),
                        Map.of(
                                "IndexName", GSI2_NAME,
                                "KeySchema", List.of(
                                        Map.of("AttributeName", "GSI2_PK", "KeyType", "HASH"),
                                        Map.of("AttributeName", "GSI2_SK", "KeyType", "RANGE")
                                ),
                                "Projection", Map.of("ProjectionType", "ALL"),
                                "ProvisionedThroughput", Map.of("ReadCapacityUnits", 5, "WriteCapacityUnits", 5)
                        )
                ),
                "ProvisionedThroughput", Map.of("ReadCapacityUnits", 5, "WriteCapacityUnits", 5)
        );

        try {
            sendDynamoRequest("DynamoDB_20120810.CreateTable", createReq);
        } catch (Exception ignored) { }
    }

    @Override
    public void save(NormalizedTxn txn) {
        String dedupKey = SqlLedgerStore.computeDedupKey(txn);
        NormalizedTxn storedTxn = isDynamoAvailable ? mergeExistingEvidence(txn, dedupKey) : txn;
        internalStore.save(storedTxn);
        if (!isDynamoAvailable) return;

        String ym = String.format("%04d-%02d", txn.occurredAt().getYear(), txn.occurredAt().getMonthValue());
        String sortedSourceIds = String.join(",", new TreeSet<>(storedTxn.sourceMessageIds()));

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("PK", Map.of("S", "ACCOUNT#" + txn.accountLast4()));
        item.put("SK", Map.of("S", "TXN#" + txn.occurredAt().toString() + "#" + dedupKey));
        item.put("GSI1_PK", Map.of("S", "ACCT_MONTH#" + txn.accountLast4() + "#" + ym));
        item.put("GSI1_SK", Map.of("S", txn.occurredAt().toString()));
        item.put("account_last4", Map.of("S", txn.accountLast4()));
        item.put("occurred_at", Map.of("S", txn.occurredAt().toString()));
        item.put("direction", Map.of("S", txn.direction().name()));
        item.put("amount", Map.of("S", txn.amount().setScale(2).toPlainString()));
        item.put("category", Map.of("S", txn.category().name()));
        item.put("merchant", Map.of("S", txn.merchant() != null ? txn.merchant() : ""));
        item.put("source_message_ids", Map.of("S", sortedSourceIds));
        item.put("dedup_key", Map.of("S", dedupKey));

        sendDynamoRequest("DynamoDB_20120810.PutItem", Map.of(
                "TableName", TABLE_NAME,
                "Item", item
        ));

        for (String msgId : storedTxn.sourceMessageIds()) {
            Map<String, Object> msgItem = new LinkedHashMap<>(item);
            msgItem.remove("GSI1_PK");
            msgItem.remove("GSI1_SK");
            msgItem.put("PK", Map.of("S", "MSG#" + msgId));
            msgItem.put("SK", Map.of("S", "TXN#" + dedupKey));
            msgItem.put("GSI2_PK", Map.of("S", "MSG#" + msgId));
            msgItem.put("GSI2_SK", Map.of("S", "TXN#" + dedupKey));
            sendDynamoRequest("DynamoDB_20120810.PutItem", Map.of(
                    "TableName", TABLE_NAME,
                    "Item", msgItem
            ));
        }
    }

    private NormalizedTxn mergeExistingEvidence(NormalizedTxn txn, String dedupKey) {
        String pk = "ACCOUNT#" + txn.accountLast4();
        String sk = "TXN#" + txn.occurredAt().toString() + "#" + dedupKey;
        Map<String, Object> response = sendDynamoRequest("DynamoDB_20120810.GetItem", Map.of(
                "TableName", TABLE_NAME,
                "ConsistentRead", true,
                "Key", Map.of(
                        "PK", Map.of("S", pk),
                        "SK", Map.of("S", sk))));

        Object rawItem = response.get("Item");
        if (!(rawItem instanceof Map<?, ?> item)
                || !(item.get("source_message_ids") instanceof Map<?, ?> sourceIds)
                || !(sourceIds.get("S") instanceof String existingIds)) {
            return txn;
        }

        TreeSet<String> mergedIds = new TreeSet<>(txn.sourceMessageIds());
        Arrays.stream(existingIds.split(","))
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .forEach(mergedIds::add);
        if (mergedIds.size() == txn.sourceMessageIds().size()) return txn;

        return new NormalizedTxn(txn.accountLast4(), txn.occurredAt(), txn.direction(),
                txn.amount(), txn.category(), txn.merchant(), List.copyOf(mergedIds));
    }

    @Override
    public List<NormalizedTxn> forAccountMonth(String accountLast4, YearMonth month) {
        if (!isDynamoAvailable) {
            List<NormalizedTxn> list = internalStore.forAccountMonth(accountLast4, month);
            lastQueryMetrics = new QueryMetrics(list.size(), list.size());
            return list;
        }

        String gsi1Pk = "ACCT_MONTH#" + accountLast4 + "#" + String.format("%04d-%02d", month.getYear(), month.getMonthValue());
        int totalScanned = 0;
        int totalCount = 0;
        List<NormalizedTxn> out = new ArrayList<>();
        Map<String, Object> exclusiveStartKey = null;

        do {
            Map<String, Object> reqBody = new LinkedHashMap<>();
            reqBody.put("TableName", TABLE_NAME);
            reqBody.put("IndexName", GSI1_NAME);
            reqBody.put("KeyConditionExpression", "GSI1_PK = :pk");
            reqBody.put("ExpressionAttributeValues", Map.of(":pk", Map.of("S", gsi1Pk)));
            reqBody.put("ScanIndexForward", false);
            if (exclusiveStartKey != null) {
                reqBody.put("ExclusiveStartKey", exclusiveStartKey);
            }

            Map<String, Object> resp = sendDynamoRequest("DynamoDB_20120810.Query", reqBody);
            int scanned = resp.containsKey("ScannedCount") ? ((Number) resp.get("ScannedCount")).intValue() : 0;
            int count = resp.containsKey("Count") ? ((Number) resp.get("Count")).intValue() : 0;
            totalScanned += scanned;
            totalCount += count;

            if (resp.containsKey("Items")) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> items = (List<Map<String, Object>>) resp.get("Items");
                for (Map<String, Object> item : items) {
                    out.add(parseTxn(item));
                }
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> lastKey = (Map<String, Object>) resp.get("LastEvaluatedKey");
            exclusiveStartKey = (lastKey != null && !lastKey.isEmpty()) ? lastKey : null;
        } while (exclusiveStartKey != null);

        lastQueryMetrics = new QueryMetrics(totalScanned, totalCount);
        return out;
    }

    @Override
    public Map<Category, BigDecimal> categoryTotals(String accountLast4) {
        if (!isDynamoAvailable) {
            Map<Category, BigDecimal> totals = internalStore.categoryTotals(accountLast4);
            lastQueryMetrics = new QueryMetrics(internalStore.rows.size(), internalStore.rows.size());
            return totals;
        }

        String pk = "ACCOUNT#" + accountLast4;
        int totalScanned = 0;
        int totalCount = 0;
        Map<Category, BigDecimal> totalsMap = new EnumMap<>(Category.class);
        for (Category c : Category.values()) totalsMap.put(c, BigDecimal.ZERO.setScale(2));

        Map<String, Object> exclusiveStartKey = null;

        do {
            Map<String, Object> reqBody = new LinkedHashMap<>();
            reqBody.put("TableName", TABLE_NAME);
            reqBody.put("KeyConditionExpression", "PK = :pk AND begins_with(SK, :skPrefix)");
            reqBody.put("ExpressionAttributeValues", Map.of(
                    ":pk", Map.of("S", pk),
                    ":skPrefix", Map.of("S", "TXN#")
            ));
            if (exclusiveStartKey != null) {
                reqBody.put("ExclusiveStartKey", exclusiveStartKey);
            }

            Map<String, Object> resp = sendDynamoRequest("DynamoDB_20120810.Query", reqBody);
            int scanned = resp.containsKey("ScannedCount") ? ((Number) resp.get("ScannedCount")).intValue() : 0;
            int count = resp.containsKey("Count") ? ((Number) resp.get("Count")).intValue() : 0;
            totalScanned += scanned;
            totalCount += count;

            if (resp.containsKey("Items")) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> items = (List<Map<String, Object>>) resp.get("Items");
                for (Map<String, Object> item : items) {
                    Category cat = Category.valueOf((String) ((Map<String, Object>) item.get("category")).get("S"));
                    BigDecimal amt = new BigDecimal((String) ((Map<String, Object>) item.get("amount")).get("S"));
                    totalsMap.put(cat, totalsMap.get(cat).add(amt));
                }
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> lastKey = (Map<String, Object>) resp.get("LastEvaluatedKey");
            exclusiveStartKey = (lastKey != null && !lastKey.isEmpty()) ? lastKey : null;
        } while (exclusiveStartKey != null);

        lastQueryMetrics = new QueryMetrics(totalScanned, totalCount);
        return totalsMap;
    }

    @Override
    public Optional<NormalizedTxn> byMessageId(String messageId) {
        if (!isDynamoAvailable) {
            Optional<NormalizedTxn> res = internalStore.byMessageId(messageId);
            lastQueryMetrics = new QueryMetrics(res.isPresent() ? 1 : 0, res.isPresent() ? 1 : 0);
            return res;
        }

        String msgPk = "MSG#" + messageId;
        int totalScanned = 0;
        int totalCount = 0;
        Optional<NormalizedTxn> result = Optional.empty();
        Map<String, Object> exclusiveStartKey = null;

        do {
            Map<String, Object> reqBody = new LinkedHashMap<>();
            reqBody.put("TableName", TABLE_NAME);
            reqBody.put("IndexName", GSI2_NAME);
            reqBody.put("KeyConditionExpression", "GSI2_PK = :pk");
            reqBody.put("ExpressionAttributeValues", Map.of(":pk", Map.of("S", msgPk)));
            if (exclusiveStartKey != null) {
                reqBody.put("ExclusiveStartKey", exclusiveStartKey);
            }

            Map<String, Object> resp = sendDynamoRequest("DynamoDB_20120810.Query", reqBody);
            int scanned = resp.containsKey("ScannedCount") ? ((Number) resp.get("ScannedCount")).intValue() : 0;
            int count = resp.containsKey("Count") ? ((Number) resp.get("Count")).intValue() : 0;
            totalScanned += scanned;
            totalCount += count;

            if (resp.containsKey("Items")) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> items = (List<Map<String, Object>>) resp.get("Items");
                if (!items.isEmpty() && result.isEmpty()) {
                    result = Optional.of(parseTxn(items.get(0)));
                }
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> lastKey = (Map<String, Object>) resp.get("LastEvaluatedKey");
            exclusiveStartKey = (lastKey != null && !lastKey.isEmpty()) ? lastKey : null;
        } while (exclusiveStartKey != null);

        lastQueryMetrics = new QueryMetrics(totalScanned, totalCount);
        return result;
    }

    private Map<String, Object> sendDynamoRequest(String target, Map<String, Object> payload) {
        try {
            String json = Json.writePretty(payload);
            HttpRequest req = signedRequest(target, json).build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                if (isDynamoAvailable) {
                    throw new IllegalStateException("DynamoDB error [" + target + "]: status " + resp.statusCode() + " body: " + resp.body());
                }
            }
            return Json.parseObject(resp.body());
        } catch (Exception e) {
            if (isDynamoAvailable) {
                if (e instanceof IllegalStateException ise) throw ise;
                throw new IllegalStateException("Failed communicating with DynamoDB [" + target + "]", e);
            }
            return Map.of();
        }
    }

    private HttpRequest.Builder signedRequest(String target, String payload)
            throws GeneralSecurityException {
        String timestamp = AWS_TIMESTAMP.format(Instant.now());
        String date = timestamp.substring(0, 8);
        String host = endpoint.getHost() + (endpoint.getPort() < 0 ? "" : ":" + endpoint.getPort());
        String signedHeaders = "content-type;host;x-amz-date;x-amz-target";
        String canonicalHeaders = "content-type:application/x-amz-json-1.0\n"
                + "host:" + host + "\n"
                + "x-amz-date:" + timestamp + "\n"
                + "x-amz-target:" + target + "\n";
        String canonicalUri = endpoint.getRawPath().isEmpty() ? "/" : endpoint.getRawPath();
        String canonicalQuery = endpoint.getRawQuery() == null ? "" : endpoint.getRawQuery();
        String canonicalRequest = "POST\n" + canonicalUri + "\n" + canonicalQuery + "\n"
                + canonicalHeaders + "\n" + signedHeaders + "\n" + sha256Hex(payload);
        String scope = date + "/" + AWS_REGION + "/dynamodb/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + timestamp + "\n" + scope + "\n"
                + sha256Hex(canonicalRequest);

        byte[] dateKey = hmacSha256(("AWS4" + LOCAL_SECRET_ACCESS_KEY).getBytes(StandardCharsets.UTF_8), date);
        byte[] regionKey = hmacSha256(dateKey, AWS_REGION);
        byte[] serviceKey = hmacSha256(regionKey, "dynamodb");
        byte[] signingKey = hmacSha256(serviceKey, "aws4_request");
        String signature = HexFormat.of().formatHex(hmacSha256(signingKey, stringToSign));
        String authorization = "AWS4-HMAC-SHA256 Credential=" + LOCAL_ACCESS_KEY_ID + "/" + scope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;

        return HttpRequest.newBuilder()
                .uri(endpoint)
                .header("Content-Type", "application/x-amz-json-1.0")
                .header("X-Amz-Target", target)
                .header("X-Amz-Date", timestamp)
                .header("Authorization", authorization)
                .POST(HttpRequest.BodyPublishers.ofString(payload));
    }

    private static String sha256Hex(String value) throws GeneralSecurityException {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    private static byte[] hmacSha256(byte[] key, String value) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    }

    private NormalizedTxn parseTxn(Map<String, Object> item) {
        @SuppressWarnings("unchecked")
        String accountLast4 = (String) ((Map<String, Object>) item.get("account_last4")).get("S");
        @SuppressWarnings("unchecked")
        String occurredAt = (String) ((Map<String, Object>) item.get("occurred_at")).get("S");
        @SuppressWarnings("unchecked")
        String direction = (String) ((Map<String, Object>) item.get("direction")).get("S");
        @SuppressWarnings("unchecked")
        String amount = (String) ((Map<String, Object>) item.get("amount")).get("S");
        @SuppressWarnings("unchecked")
        String category = (String) ((Map<String, Object>) item.get("category")).get("S");
        @SuppressWarnings("unchecked")
        String merchant = (String) ((Map<String, Object>) item.get("merchant")).get("S");
        @SuppressWarnings("unchecked")
        String sIds = (String) ((Map<String, Object>) item.get("source_message_ids")).get("S");

        List<String> msgIds = Arrays.stream(sIds.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();

        return new NormalizedTxn(
                accountLast4,
                OffsetDateTime.parse(occurredAt),
                Direction.valueOf(direction),
                new BigDecimal(amount),
                Category.valueOf(category),
                merchant,
                msgIds
        );
    }

    public List<NormalizedTxn> all() {
        if (!isDynamoAvailable) {
            return internalStore.all();
        }
        List<NormalizedTxn> out = new ArrayList<>();
        Map<String, Object> exclusiveStartKey = null;

        do {
            Map<String, Object> reqBody = new LinkedHashMap<>();
            reqBody.put("TableName", TABLE_NAME);
            reqBody.put("FilterExpression", "begins_with(PK, :pkPrefix) AND begins_with(SK, :skPrefix)");
            reqBody.put("ExpressionAttributeValues", Map.of(
                    ":pkPrefix", Map.of("S", "ACCOUNT#"),
                    ":skPrefix", Map.of("S", "TXN#")
            ));
            if (exclusiveStartKey != null) {
                reqBody.put("ExclusiveStartKey", exclusiveStartKey);
            }

            Map<String, Object> resp = sendDynamoRequest("DynamoDB_20120810.Scan", reqBody);
            if (resp.containsKey("Items")) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> items = (List<Map<String, Object>>) resp.get("Items");
                for (Map<String, Object> item : items) {
                    out.add(parseTxn(item));
                }
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> lastKey = (Map<String, Object>) resp.get("LastEvaluatedKey");
            exclusiveStartKey = (lastKey != null && !lastKey.isEmpty()) ? lastKey : null;
        } while (exclusiveStartKey != null);

        return out;
    }

    @Override
    public void close() { }

    public static final class InMemoryDocumentStore implements DocumentStore {
        private final List<NormalizedTxn> rows = new ArrayList<>();

        @Override
        public synchronized void save(NormalizedTxn txn) {
            String key = SqlLedgerStore.computeDedupKey(txn);
            for (int i = 0; i < rows.size(); i++) {
                NormalizedTxn existing = rows.get(i);
                if (SqlLedgerStore.computeDedupKey(existing).equals(key)) {
                    TreeSet<String> combined = new TreeSet<>(existing.sourceMessageIds());
                    combined.addAll(txn.sourceMessageIds());
                    rows.set(i, new NormalizedTxn(
                            existing.accountLast4(),
                            existing.occurredAt(),
                            existing.direction(),
                            existing.amount(),
                            txn.category(),
                            txn.merchant(),
                            new ArrayList<>(combined)
                    ));
                    return;
                }
            }
            rows.add(txn);
        }

        @Override
        public List<NormalizedTxn> forAccountMonth(String accountLast4, YearMonth month) {
            return rows.stream()
                    .filter(t -> t.accountLast4().equals(accountLast4)
                            && YearMonth.from(t.occurredAt()).equals(month))
                    .sorted((a, b) -> b.occurredAt().compareTo(a.occurredAt()))
                    .toList();
        }

        @Override
        public Map<Category, BigDecimal> categoryTotals(String accountLast4) {
            Map<Category, BigDecimal> map = new EnumMap<>(Category.class);
            for (Category c : Category.values()) map.put(c, BigDecimal.ZERO.setScale(2));
            for (NormalizedTxn t : rows) {
                if (t.accountLast4().equals(accountLast4)) {
                    map.put(t.category(), map.get(t.category()).add(t.amount()));
                }
            }
            return map;
        }

        @Override
        public Optional<NormalizedTxn> byMessageId(String messageId) {
            return rows.stream()
                    .filter(t -> t.sourceMessageIds().contains(messageId))
                    .findFirst();
        }

        public List<NormalizedTxn> all() {
            return Collections.unmodifiableList(rows);
        }
    }
}
