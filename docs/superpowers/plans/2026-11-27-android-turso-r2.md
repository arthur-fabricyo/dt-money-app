# Android SQLite, Turso Sync and R2 Backup Implementation Plan

> **For agentic workers:** Use `superpowers:subagent-driven-development` or `superpowers:executing-plans`. Check off verified steps.

**Goal** Run DT Money offline on Android and optionally synchronize through the Render API to Turso, with restorable daily backups on R2.

**Architecture** Android owns a SQLite ledger and an atomic outbox. A Fastify API scopes sync to authenticated accounts and persists it in Turso. A Cloudflare Worker schedules the backup and the API produces and uploads the encrypted SQLite file.

**Tech stack** Kotlin, Compose, SQLite, WorkManager, TypeScript, Fastify, libSQL, Cloudflare R2 and Workers.

**Spec** [SQLite no celular, sincronização opcional e backup diário](../specs/2026-11-27-sqlite-no-celular-sincronizacao-backup-design.md).

## Global constraints

- Preserve the tracked legacy database and existing working changes. Use temporary databases and fictitious records for verification. Keep the legacy API available for import and for Expo.
- Keep bank notification text and original statement files out of synchronization and logs. Store cents as integers. Keep Turso, R2 and scheduler credentials on the server.
- A daily backup starts at 06h15 UTC and expires after 10 days under the designated R2 prefix. Use a dated key and a conditional upload.

## Shared sync contract

- `POST /sync` is authenticated and receives `{generation: string|null, cursor: number, mutations: Mutation[]}`. Return `{generation: string, cursor: number, reset: boolean, hasMore: boolean, changes: Change[], acknowledgements: Ack[]}`. A reset produces changes starting at cursor zero and defers applying the submitted batch until the client has reconciled the restored generation. Initial requests without a generation accept submissions.
- `Mutation` is `{mutationId: string, entity: "category"|"transaction", id: string, baseRevision: number, data: object|null}`. `Change` is `{entity, id, revision: number, data}` and `Ack` is `{mutationId, status: "applied"|"conflict", change: Change}`. Null data is a tombstone. Verify bounded batch sizes and pagination.
- Category data is `{name, color, description, archived}`. The stable fallback ID is `uncategorized`. Transaction data is `{description, cents, typeId, categoryId, occurredAt, importId}`. `typeId` retains the existing meaning, `categoryId` is a stable string and `importId` is optional. Android uses separate numeric row IDs for existing Compose models.

## Review focus

- Lost acknowledgement and replay use the same mutation ID and produce one remote change.
- A reconnect after a database restore preserves unsent local changes and invalidates old cursors.
- Capture migration of historical and pending records deduplicates using import identity.
- Closing the editor, rotating the phone or archiving a category leaves stored values and historical links readable.
- A backup trigger duplicated after a timeout leaves a single encrypted, restorable object for the day.

### 1. Android ledger, categories and synchronization

- [ ] Add instrumented tests for persistent offline CRUD, category archiving, outbox persistence, and migration replay. Run the relevant Gradle target to observe failure before wiring persistence.
- [ ] Create the ledger and outbox alongside `ImportStore.kt`. Wire `MoneyViewModel.kt`, `MainActivity.kt`, `AppSettings.kt`, `MoneyApi.kt` and the notification and classification workers to the app's local profile. Keep existing `Transaction`, `TransactionPage` and `Category` view representations. Import the legacy history through the authenticated API with a preview and preserve existing capture state.
- [ ] Add category CRUD and a color selector in Settings. Reuse existing styling. Validate the saved category against the active catalog when applying an AI suggestion. Prepare a Storage Access Framework JSON category import with a review and idempotent commit.
- [ ] Persist sync cursor, outbox and unresolved conflicts in the ledger. Use WorkManager network constraints and allow a manual sync. Use stable account and server scoping. Add and verify tests for reset and replay.
- [ ] Run `:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleDebugAndroidTest`. Run device tests when an Android device is accessible.

### 2. Fastify sync API and Turso persistence

- [ ] Write failing tests in `DT-money-backend/test/local-sync.test.cjs` covering idempotency, account isolation, deduplication, independent field edits, conflicts and generation reset. Run the file with `node --import tsx --test` before implementation.
- [ ] Implement the sync routes and persistence under `DT-money-backend/src/sync`. Use Turso with server credentials and transactions. Maintain local SQLite schema fixtures and isolate the legacy TypeORM path. Expose `initialize()` and `snapshot()` on the store. Return reproducible outcomes for repeated mutation IDs within each account.
- [ ] Verify tests against the local adapter. Validate the deployed API by synchronizing a synthetic mutation, restarting, and reading it back.

### 3. Server backup and Cloudflare schedule

- [ ] Write failing tests covering protected trigger, immutable daily key, snapshot restoration, and transient upload failure. Run those tests with `node --import tsx --test`.
- [ ] Implement backup and restore utilities under `DT-money-backend/src/backup`. Accept a snapshot source from the sync store. Validate SQLite contents, encrypt using an age recipient and upload conditionally with `If-None-Match: *`. On restore, verify the schema and restore into a separate database with a new sync generation.
- [ ] Create the Cloudflare Worker and its Wrangler configuration with `15 6 * * *`. The Worker calls the authenticated Render trigger. Configure 10-day expiration on the backup prefix and document providing secrets through Render and Cloudflare.
- [ ] Exercise encrypted backup and restore locally with synthetic data. Verify Cloudflare starts an authenticated run and the R2 object is private when credentials are available.

### 4. Integration, PR and free test deploy

- [ ] Adapt `DT-money-backend/src/server.ts`, runtime scripts and service settings for `PORT`, health checks and Turso-backed sync. Configure a synthetic test environment with distinct database, credentials and R2 prefix.
- [ ] Run Android and backend tests, verify the combined request shapes and ensure the database file, credentials and exports remain outside the PR. Update the README with testing, setup and restore commands.
- [ ] Open or update the PR and verify the Render service health, synthetic sync, backup and restore when the necessary app connections are available.
