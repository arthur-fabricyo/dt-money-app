import type { Row } from "@libsql/client";

export const SYNC_SNAPSHOT_SCHEMA = {
  sync_metadata: {
    columns: ["id", "generation"],
    sql: `CREATE TABLE IF NOT EXISTS sync_metadata (
      id INTEGER PRIMARY KEY CHECK (id = 1),
      generation TEXT NOT NULL
    )`,
  },
  sync_users: {
    columns: ["id", "email", "name", "password_hash"],
    sql: `CREATE TABLE IF NOT EXISTS sync_users (
      id INTEGER PRIMARY KEY AUTOINCREMENT,
      email TEXT UNIQUE NOT NULL,
      name TEXT NOT NULL,
      password_hash TEXT NOT NULL
    )`,
  },
  sync_accounts: {
    columns: ["id"],
    sql: `CREATE TABLE IF NOT EXISTS sync_accounts (
      id TEXT PRIMARY KEY NOT NULL
    )`,
  },
  sync_changes: {
    columns: ["seq", "account", "entity", "id", "revision", "data"],
    sql: `CREATE TABLE IF NOT EXISTS sync_changes (
      seq INTEGER PRIMARY KEY AUTOINCREMENT,
      account TEXT NOT NULL REFERENCES sync_accounts(id),
      entity TEXT NOT NULL CHECK (entity IN ('category', 'transaction')),
      id TEXT NOT NULL,
      revision INTEGER NOT NULL CHECK (revision > 0),
      data TEXT,
      UNIQUE (account, entity, id, revision)
    )`,
  },
  sync_entities: {
    columns: ["account", "entity", "id", "revision", "data", "import_id"],
    sql: `CREATE TABLE IF NOT EXISTS sync_entities (
      account TEXT NOT NULL REFERENCES sync_accounts(id),
      entity TEXT NOT NULL CHECK (entity IN ('category', 'transaction')),
      id TEXT NOT NULL,
      revision INTEGER NOT NULL CHECK (revision > 0),
      data TEXT,
      import_id TEXT CHECK (import_id IS NULL OR entity = 'transaction'),
      PRIMARY KEY (account, entity, id),
      UNIQUE (account, import_id),
      FOREIGN KEY (account, entity, id, revision)
        REFERENCES sync_changes(account, entity, id, revision)
    )`,
  },
  sync_receipts: {
    columns: ["account", "mutation_id", "acknowledgement"],
    sql: `CREATE TABLE IF NOT EXISTS sync_receipts (
      account TEXT NOT NULL REFERENCES sync_accounts(id),
      mutation_id TEXT NOT NULL,
      acknowledgement TEXT NOT NULL,
      PRIMARY KEY (account, mutation_id)
    )`,
  },
} as const;

export const SYNC_SNAPSHOT_INDEXES = [
  "CREATE INDEX IF NOT EXISTS sync_changes_by_account ON sync_changes (account, seq)",
] as const;

export type SyncSnapshotTable = keyof typeof SYNC_SNAPSHOT_SCHEMA;

export const SYNC_SNAPSHOT_TABLES = Object.keys(SYNC_SNAPSHOT_SCHEMA) as SyncSnapshotTable[];

export interface SyncSnapshot {
  generation: string;
  revision: number;
  tables: Record<SyncSnapshotTable, Row[]>;
}

export const BACKUP_LEASE_SCHEMA = `CREATE TABLE IF NOT EXISTS sync_backup_leases (
  day TEXT PRIMARY KEY NOT NULL,
  owner TEXT NOT NULL,
  expires_at INTEGER NOT NULL
)`;

export const BACKUP_STATUS_SCHEMA = `CREATE TABLE IF NOT EXISTS sync_backup_status (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  confirmed_day TEXT NOT NULL
)`;
