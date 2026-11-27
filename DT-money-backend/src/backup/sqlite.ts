import { writeFile } from "node:fs/promises";
import { Database, OPEN_READONLY, OPEN_READWRITE } from "sqlite3";
import { SYNC_SNAPSHOT_INDEXES, SYNC_SNAPSHOT_SCHEMA, SYNC_SNAPSHOT_TABLES } from "../sync/schema";
import type { SyncSnapshot } from "../sync/schema";

const version = 1;

export function execute(database: Database, sql: string, values: unknown[] = []): Promise<void> {
  return new Promise((resolve, reject) => {
    database.run(sql, values, (error) => error ? reject(error) : resolve());
  });
}

function script(database: Database, sql: string): Promise<void> {
  return new Promise((resolve, reject) => {
    database.exec(sql, (error) => error ? reject(error) : resolve());
  });
}

function rows<T>(database: Database, sql: string): Promise<T[]> {
  return new Promise((resolve, reject) => {
    database.all(sql, (error, result: T[]) => error ? reject(error) : resolve(result));
  });
}

export async function withDatabase<T>(filename: string, write: boolean, work: (db: Database) => Promise<T>): Promise<T> {
  const database = await new Promise<Database>((resolve, reject) => {
    const db = new Database(filename, write ? OPEN_READWRITE : OPEN_READONLY, (error) => {
      error ? reject(error) : resolve(db);
    });
  });
  try {
    await script(database, "PRAGMA trusted_schema = OFF; PRAGMA foreign_keys = ON;");
    return await work(database);
  } finally {
    await new Promise<void>((resolve, reject) => {
      database.close((error) => error ? reject(error) : resolve());
    });
  }
}

function schemaSql(sql: string): string {
  return sql.trim().replace(/;$/, "").replace(/\s+IF\s+NOT\s+EXISTS\s+/i, " ").replace(/\s+/g, " ");
}

export async function validateSnapshot(database: Database): Promise<{ generation: string; revision: number }> {
  const [format] = await rows<{ user_version: number }>(database, "PRAGMA user_version");
  if (format?.user_version !== version) throw new Error("Unsupported backup version");

  const expectedSchema = [...SYNC_SNAPSHOT_TABLES.map((name) => SYNC_SNAPSHOT_SCHEMA[name].sql), ...SYNC_SNAPSHOT_INDEXES]
    .map(schemaSql).sort();
  const actualSchema = (await rows<{ sql: string }>(database, `
    SELECT sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT GLOB 'sqlite_*'
  `)).map(({ sql }) => schemaSql(sql)).sort();
  if (JSON.stringify(expectedSchema) !== JSON.stringify(actualSchema)) throw new Error("Invalid backup schema");

  const integrity = await rows<{ integrity_check: string }>(database, "PRAGMA integrity_check");
  if (integrity.length !== 1 || integrity[0].integrity_check !== "ok") throw new Error("Invalid backup integrity");
  if ((await rows(database, "PRAGMA foreign_key_check")).length !== 0) throw new Error("Invalid backup references");

  const metadata = await rows<{ id: number; generation: string }>(database, "SELECT id, generation FROM sync_metadata");
  const [{ revision }] = await rows<{ revision: number }>(database, "SELECT COALESCE(MAX(seq), 0) AS revision FROM sync_changes");
  if (metadata.length !== 1 || metadata[0].id !== 1 || typeof metadata[0].generation !== "string" || !metadata[0].generation ||
    !Number.isSafeInteger(revision) || revision < 0) {
    throw new Error("Invalid backup sync state");
  }
  return { generation: metadata[0].generation, revision };
}

export async function writeSnapshot(filename: string, snapshot: SyncSnapshot): Promise<void> {
  await writeFile(filename, "", { flag: "wx", mode: 0o600 });
  await withDatabase(filename, true, async (database) => {
    await script(database, `BEGIN; PRAGMA defer_foreign_keys = ON; PRAGMA user_version = ${version};`);
    for (const name of SYNC_SNAPSHOT_TABLES) {
      const { sql } = SYNC_SNAPSHOT_SCHEMA[name];
      await script(database, sql);
    }
    for (const name of SYNC_SNAPSHOT_TABLES) {
      const { columns } = SYNC_SNAPSHOT_SCHEMA[name];
      const insert = `INSERT INTO ${name} (${columns.join(", ")}) VALUES (${columns.map(() => "?").join(", ")})`;
      for (const row of snapshot.tables[name]) {
        await execute(database, insert, columns.map((column) => row[column]));
      }
    }
    for (const sql of SYNC_SNAPSHOT_INDEXES) await script(database, sql);
    const state = await validateSnapshot(database);
    if (state.generation !== snapshot.generation || state.revision !== snapshot.revision) {
      throw new Error("Inconsistent snapshot revision");
    }
    await script(database, "COMMIT;");
  });
}
