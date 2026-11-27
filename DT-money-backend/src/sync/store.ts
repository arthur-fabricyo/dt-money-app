import { randomUUID } from "node:crypto";
import { LibsqlError } from "@libsql/client";
import type { Client, InValue, Row, Transaction } from "@libsql/client";
import { UNCATEGORIZED } from "./contract";
import type {
  Acknowledgement, CategoryData, Change, Entity, EntityData, Mutation,
  SyncRequest, SyncResponse, TransactionData,
} from "./contract";
import {
  BACKUP_LEASE_SCHEMA, BACKUP_STATUS_SCHEMA,
  SYNC_SNAPSHOT_INDEXES, SYNC_SNAPSHOT_SCHEMA, SYNC_SNAPSHOT_TABLES,
} from "./schema";
import type { SyncSnapshot } from "./schema";

const PAGE_SIZE = 100;
const FALLBACK_CATEGORY: CategoryData = {
  name: "Sem categoria",
  color: "#6B7280",
  description: "",
  archived: false,
};

export interface SyncStore {
  initialize(): Promise<void>;
  sync(account: string, input: SyncRequest): Promise<SyncResponse>;
  snapshot(): Promise<SyncSnapshot>;
  acquireBackupLease(day: string, owner: string, now: number, expiresAt: number): Promise<boolean>;
  releaseBackupLease(day: string, owner: string): Promise<void>;
  confirmBackup(day: string): Promise<void>;
  lastBackupDate(): Promise<string | null>;
}

function execute(tx: Transaction, sql: string, ...args: InValue[]) {
  return tx.execute({ sql, args });
}

function readChange(row: Row): Change {
  return {
    entity: row.entity as Entity,
    id: String(row.id),
    revision: Number(row.revision),
    data: row.data === null ? null : JSON.parse(String(row.data)),
  };
}

function getGeneration(row: Row | undefined): string {
  if (!row || typeof row.generation !== "string" || row.generation.length === 0) {
    throw new Error("Sync generation is missing");
  }
  return row.generation;
}

function isSameData(left: EntityData | null, right: EntityData | null): boolean {
  if (left === null || right === null) return left === right;
  if ("cents" in left && "cents" in right) {
    return left.description === right.description && left.cents === right.cents &&
      left.typeId === right.typeId && left.categoryId === right.categoryId &&
      left.occurredAt === right.occurredAt && left.importId === right.importId;
  }
  if ("name" in left && "name" in right) {
    return left.name === right.name && left.color === right.color &&
      left.description === right.description && left.archived === right.archived;
  }
  return false;
}

function mergeFields<T extends EntityData>(base: T, existing: T, incoming: T): T | undefined {
  const merged = { ...existing };
  for (const field of Object.keys(incoming) as (keyof T)[]) {
    if (incoming[field] === base[field]) continue;
    if (existing[field] !== base[field] && existing[field] !== incoming[field]) return undefined;
    merged[field] = incoming[field];
  }
  return merged;
}

async function applyMutation(tx: Transaction, account: string, mutation: Mutation): Promise<Acknowledgement> {
  const { entity, id, baseRevision, mutationId } = mutation;
  const conflict = (change: Change): Acknowledgement => ({ mutationId, status: "conflict", change });
  const applied = (change: Change): Acknowledgement => ({ mutationId, status: "applied", change });
  if (entity === "category" && id === UNCATEGORIZED) {
    return conflict({ entity, id, revision: 0, data: FALLBACK_CATEGORY });
  }

  const { rows: [stored] } = await execute(tx, `
    SELECT entity, id, revision, data, import_id FROM sync_entities
    WHERE account = ? AND entity = ? AND id = ?
  `, account, entity, id);
  const current = stored ? readChange(stored) : { entity, id, revision: 0, data: null };
  const existing = current.data;
  const previousImport = stored?.import_id == null ? null : String(stored.import_id);
  let next: EntityData | null | undefined = mutation.data;
  let importId = previousImport;

  if (mutation.entity === "transaction" && mutation.data !== null) {
    const transaction = mutation.data;
    if (previousImport && transaction.importId && transaction.importId !== previousImport) {
      return conflict(current);
    }
    importId = previousImport ?? transaction.importId ?? null;
    next = { ...transaction, importId };
    if (importId && (baseRevision === 0 || importId !== previousImport)) {
      const { rows: [duplicate] } = await execute(tx, `
        SELECT entity, id, revision, data FROM sync_entities
        WHERE account = ? AND import_id = ?
      `, account, importId);
      if (duplicate && baseRevision === 0) return applied(readChange(duplicate));
      if (duplicate) return conflict(readChange(duplicate));
    }
  }

  if (baseRevision !== current.revision) {
    if (baseRevision === 0 || baseRevision > current.revision) return conflict(current);
    if (existing === null && next === null) return applied(current);
    if (existing === null || next === null) return conflict(current);

    const { rows: [base] } = await execute(tx, `
      SELECT data FROM sync_changes
      WHERE account = ? AND entity = ? AND id = ? AND revision = ?
    `, account, entity, id, baseRevision);
    if (!base || base.data === null) return conflict(current);
    next = mergeFields(JSON.parse(String(base.data)), existing, next);
    if (next === undefined) return conflict(current);
  }

  if (next && "categoryId" in next && next.categoryId !== UNCATEGORIZED &&
      next.categoryId !== (existing as TransactionData | null)?.categoryId) {
    const { rows: [category] } = await execute(tx, `
      SELECT data FROM sync_entities WHERE account = ? AND entity = 'category' AND id = ?
    `, account, next.categoryId);
    if (category?.data == null || (JSON.parse(String(category.data)) as CategoryData).archived) {
      next.categoryId = UNCATEGORIZED;
    }
  }

  if (stored && isSameData(existing, next)) return applied(current);
  const revision = current.revision + 1;
  const data = next === null ? null : JSON.stringify(next);
  await execute(tx, `
    INSERT INTO sync_changes (account, entity, id, revision, data) VALUES (?, ?, ?, ?, ?)
  `, account, entity, id, revision, data);
  await execute(tx, `
    INSERT INTO sync_entities (account, entity, id, revision, data, import_id) VALUES (?, ?, ?, ?, ?, ?)
    ON CONFLICT (account, entity, id) DO UPDATE SET
      revision = excluded.revision, data = excluded.data, import_id = excluded.import_id
  `, account, entity, id, revision, data, importId);
  return applied({ entity, id, revision, data: next });
}

export function createSyncStore(client: Client): SyncStore {
  let localWork: Promise<unknown> = Promise.resolve();

  async function runTransaction<T>(mode: "read" | "write", run: (tx: Transaction) => Promise<T>): Promise<T> {
    for (let attempt = 0; ; attempt++) {
      let tx: Transaction | undefined;
      try {
        tx = await client.transaction(mode);
        const result = await run(tx);
        await tx.commit();
        return result;
      } catch (error) {
        if (!(error instanceof LibsqlError) ||
            (error.code !== "SQLITE_BUSY" && error.code !== "SQLITE_LOCKED") || attempt >= 3) {
          throw error;
        }
      } finally {
        tx?.close();
      }
      await new Promise((resolve) => setTimeout(resolve, 25 * 2 ** attempt));
    }
  }

  function inTransaction<T>(mode: "read" | "write", run: (tx: Transaction) => Promise<T>): Promise<T> {
    if (client.protocol !== "file") return runTransaction(mode, run);
    // A local SQLite writer can block the JS thread while another transaction is awaiting it.
    const operation = localWork.then(() => runTransaction(mode, run));
    localWork = operation.catch(() => undefined);
    return operation;
  }

  return {
    async initialize() {
      await client.batch([
        ...SYNC_SNAPSHOT_TABLES.map((table) => SYNC_SNAPSHOT_SCHEMA[table].sql),
        ...SYNC_SNAPSHOT_INDEXES,
        BACKUP_LEASE_SCHEMA,
        BACKUP_STATUS_SCHEMA,
        {
          sql: "INSERT INTO sync_metadata (id, generation) VALUES (1, ?) ON CONFLICT (id) DO NOTHING",
          args: [randomUUID()],
        },
      ], "write");
    },

    async sync(account, input) {
      return inTransaction(input.mutations.length ? "write" : "read", async (tx) => {
        const metadata = await tx.execute("SELECT generation FROM sync_metadata WHERE id = 1");
        const generation = getGeneration(metadata.rows[0]);
        const reset = input.generation !== null && input.generation !== generation;
        const from = reset || input.generation === null ? 0 : input.cursor;
        const acknowledgements: Acknowledgement[] = [];
        if (!reset && input.mutations.length > 0) {
          await execute(tx, "INSERT INTO sync_accounts (id) VALUES (?) ON CONFLICT (id) DO NOTHING", account);
          for (const mutation of input.mutations) {
            const { rows: [receipt] } = await execute(tx, `
              SELECT acknowledgement FROM sync_receipts WHERE account = ? AND mutation_id = ?
            `, account, mutation.mutationId);
            if (receipt) {
              acknowledgements.push(JSON.parse(String(receipt.acknowledgement)));
              continue;
            }
            const acknowledgement = await applyMutation(tx, account, mutation);
            await execute(tx, `
              INSERT INTO sync_receipts (account, mutation_id, acknowledgement) VALUES (?, ?, ?)
            `, account, mutation.mutationId, JSON.stringify(acknowledgement));
            acknowledgements.push(acknowledgement);
          }
        }

        const { rows } = await execute(tx, `
          SELECT seq, entity, id, revision, data FROM sync_changes
          WHERE account = ? AND seq > ? ORDER BY seq LIMIT ?
        `, account, from, PAGE_SIZE + 1);
        const page = rows.slice(0, PAGE_SIZE);
        return {
          generation,
          reset,
          cursor: page.length ? Number(page[page.length - 1].seq) : from,
          hasMore: rows.length > PAGE_SIZE,
          changes: page.map(readChange),
          acknowledgements,
        };
      });
    },

    async snapshot() {
      return inTransaction("read", async (tx) => {
        const tables = {} as SyncSnapshot["tables"];
        for (const table of SYNC_SNAPSHOT_TABLES) {
          const fields = SYNC_SNAPSHOT_SCHEMA[table].columns.join(", ");
          const { rows } = await tx.execute(`SELECT ${fields} FROM ${table}`);
          tables[table] = rows;
        }
        const { rows } = await tx.execute("SELECT COALESCE(MAX(seq), 0) AS revision FROM sync_changes");
        return {
          generation: getGeneration(tables.sync_metadata[0]),
          revision: Number(rows[0].revision),
          tables,
        };
      });
    },

    async acquireBackupLease(day, owner, now, expiresAt) {
      return inTransaction("write", async (tx) => {
        const { rowsAffected } = await execute(tx, `
          INSERT INTO sync_backup_leases (day, owner, expires_at) VALUES (?, ?, ?)
          ON CONFLICT (day) DO UPDATE SET owner = excluded.owner, expires_at = excluded.expires_at
          WHERE sync_backup_leases.expires_at <= ?
        `, day, owner, expiresAt, now);
        return rowsAffected === 1;
      });
    },

    async releaseBackupLease(day, owner) {
      await inTransaction("write", async (tx) => {
        await execute(tx, "DELETE FROM sync_backup_leases WHERE day = ? AND owner = ?", day, owner);
      });
    },

    async confirmBackup(day) {
      await inTransaction("write", async (tx) => {
        await execute(tx, `
          INSERT INTO sync_backup_status (id, confirmed_day) VALUES (1, ?)
          ON CONFLICT (id) DO UPDATE SET confirmed_day = excluded.confirmed_day
          WHERE excluded.confirmed_day > sync_backup_status.confirmed_day
        `, day);
      });
    },

    async lastBackupDate() {
      return inTransaction("read", async (tx) => {
        const { rows: [status] } = await tx.execute("SELECT confirmed_day FROM sync_backup_status WHERE id = 1");
        return status ? String(status.confirmed_day) : null;
      });
    },
  };
}
