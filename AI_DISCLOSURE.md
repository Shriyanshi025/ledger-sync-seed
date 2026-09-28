# AI Assistance & Defect Resolution Disclosure

This document discloses the use of AI coding assistants during the development of the Simplify Money **Ledger Sync** project, detailing actual defects identified in AI-generated code, how they were detected, and how they were resolved and verified.

---

### AI Mistake 1: Initial Backfill Checked Only the First Source Message ID

- **What the AI Got Wrong**: The initial AI-generated `Backfill.java` implementation checked for existing transactions using only `target.byMessageId(t.sourceMessageIds().get(0))`. If a multi-message transaction had additional source evidence IDs, subsequent backfill runs failed to detect evidence updates or misclassified transactions.
- **How Detected**: Code review and edge-case analysis of multi-message evidence correlation during Task 4 audit.
- **What Was Changed**: Updated `Backfill.java` to check all source message IDs against `target.byMessageId(msgId)`, merging new evidence into `source_message_ids` while keeping backfill idempotent (`written` vs `skipped` counts accurate).
- **How Verified**: Added `testBackfillRerunSafetyAndMetrics` in `DocumentStoreTest.java`, asserting that a rerun yields `written = 0` and `skipped = N`.

---

### AI Mistake 2: Q2 (`categoryTotals`) Delegated to In-Memory Store Instead of DynamoDB

- **What the AI Got Wrong**: `DynamoDocumentStore.categoryTotals()` was initially written to call `internalStore.categoryTotals(accountLast4)` directly, bypassing the real DynamoDB query path even when DynamoDB Local was active.
- **How Detected**: Inspection of `DynamoDocumentStore.java` during Task 4 query review.
- **What Was Changed**: Replaced the delegation with a base-table Query (`PK = ACCOUNT#<account_last4> AND begins_with(SK, "TXN#")`), paginating over DynamoDB result pages and calculating category totals in Java.
- **How Verified**: Verified `getLastQueryMetrics()` returns accumulated `ScannedCount` and `Count` from actual DynamoDB response payloads.

---

### AI Mistake 3: ConsistencyChecker Empty List Fallback When Docker Was Offline

- **What the AI Got Wrong**: `ConsistencyChecker.getDocumentTxns()` contained a check `if (documents instanceof DynamoDocumentStore dds && !dds.isDynamoAvailable())` that returned `List.of()` when Docker DynamoDB Local was not running. This caused `ConsistencyChecker` to report all SQL transactions as missing in the document store without executing field-level comparisons.
- **How Detected**: `DocumentStoreTest.testConsistencyCheckerFindsFieldDivergence()` failed during offline Gradle test runs.
- **What Was Changed**: Added `public List<NormalizedTxn> all()` to `DynamoDocumentStore` returning stored transactions and updated `ConsistencyChecker` to call `dds.all()`.
- **How Verified**: All 54+ tests passed cleanly (`DocumentStoreTest` green).

---

### AI Mistake 4: Initial Reconciliation Initialized Running Balance to Stated Balance

- **What the AI Got Wrong**: Initial `Reports.reconciliation()` set `runningBalance = first.statedBalance` (which represents the bank balance *after* the first transaction) and then re-applied the first transaction's delta during iteration. This double-counted the first transaction and produced false initial reconciliation discrepancies.
- **How Detected**: `ReportsTest.testFullCorpusAPhase4Verification()` failed due to false initial discrepancies before the true ₹7,500 gap.
- **What Was Changed**: Updated `reconciliation()` to derive the pre-transaction opening balance from `openingBalance = first.statedBalance - signedDelta(firstTransaction)` and compare `runningBalance` against non-null stated balance checkpoints.
- **How Verified**: `ReportsTest` passed, detecting only the exact ₹7,500 discrepancy for account 4821.
