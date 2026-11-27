const assert = require("node:assert/strict");
const { execFile } = require("node:child_process");
const { mkdtemp, readFile, rm, writeFile } = require("node:fs/promises");
const { tmpdir } = require("node:os");
const { join } = require("node:path");
const { test } = require("node:test");
const { pathToFileURL } = require("node:url");
const { promisify } = require("node:util");
const { createClient } = require("@libsql/client");
const fastify = require("fastify");
const sqlite3 = require("sqlite3");

const now = new Date("2026-11-28T01:15:00Z");
const key = "dt-money/backups/2026-11-27.sqlite.age";
const account = "synthetic-account";
const data = JSON.stringify({ name: "Synthetic groceries", color: "#123456", description: "", archived: false });
const snapshot = {
  generation: "synthetic-generation",
  revision: 5,
  tables: {
    sync_metadata: [{ id: 1, generation: "synthetic-generation" }],
    sync_users: [],
    sync_accounts: [{ id: account }],
    sync_changes: [{ seq: 5, account, entity: "category", id: "synthetic-category", revision: 1, data }],
    sync_entities: [{ account, entity: "category", id: "synthetic-category", revision: 1, data, import_id: null }],
    sync_receipts: [],
  },
};

async function fixture(t) {
  const age = await import("age-encryption");
  const identity = await age.generateIdentity();
  const recipient = await age.identityToRecipient(identity);
  const temporary = await mkdtemp(join(tmpdir(), "dt-money-backup-test-"));
  t.after(() => rm(temporary, { recursive: true, force: true }));
  const uploads = new Map();
  const leases = new Map();
  const confirmed = [];
  const source = {
    snapshot: async () => structuredClone(snapshot),
    async acquireBackupLease(day, owner) {
      if (leases.has(day)) return false;
      leases.set(day, owner);
      return true;
    },
    async releaseBackupLease(day, owner) {
      if (leases.get(day) === owner) leases.delete(day);
    },
    async confirmBackup(day) {
      confirmed.push(day);
    },
  };
  const storage = {
    exists: async (objectName) => uploads.has(objectName),
    async put(objectName, bytes) {
      if (uploads.has(objectName)) return "already_exists";
      uploads.set(objectName, bytes);
      return "uploaded";
    },
  };
  return { identity, recipient, temporary, uploads, source, storage, confirmed };
}

async function query(filename, sql) {
  const database = new sqlite3.Database(filename, sqlite3.OPEN_READONLY);
  try {
    return await new Promise((resolve, reject) => database.all(sql, (err, rows) => err ? reject(err) : resolve(rows)));
  } finally {
    await new Promise((resolve, reject) => database.close((err) => err ? reject(err) : resolve()));
  }
}

test("the trigger requires its own bearer secret before running backups", async (t) => {
  const { registerBackupRoutes } = require("../src/backup/routes");
  const app = fastify();
  t.after(() => app.close());
  let runs = 0;
  registerBackupRoutes(app, {}, { BACKUP_TRIGGER_TOKEN: "test-backup-token" }, async () => {
    runs += 1;
    return { outcome: "uploaded", key };
  });
  for (const authorization of [undefined, "Bearer wrong-token", "test-backup-token"]) {
    const headers = authorization ? { authorization } : {};
    assert.equal((await app.inject({ method: "POST", url: "/internal/backup", headers })).statusCode, 401);
  }
  assert.equal(runs, 0);
  const response = await app.inject({ method: "POST", url: "/internal/backup", headers: { authorization: "Bearer test-backup-token" } });
  assert.equal(response.statusCode, 200);
  assert.deepEqual(response.json(), { outcome: "uploaded", key });
  assert.equal(runs, 1);
});

test("two runs publish one encrypted snapshot for the Sao Paulo calendar day", async (t) => {
  const { runBackup } = require("../src/backup/service");
  const options = { ...await fixture(t), now };
  assert.deepEqual(await runBackup(options), { outcome: "uploaded", key });
  const first = options.uploads.get(key);
  assert.equal(Buffer.from(first).subarray(0, 22).toString(), "age-encryption.org/v1\n");
  assert.equal(Buffer.from(first).includes("Synthetic groceries"), false);
  assert.deepEqual(await runBackup(options), { outcome: "already_exists", key });
  assert.deepEqual([...options.uploads.keys()], [key]);
  assert.strictEqual(options.uploads.get(key), first);
});

test("the R2 upload signs the conditional header and treats 412 as an existing object", async () => {
  const { createR2Storage } = require("../src/backup/r2");
  const storage = createR2Storage({ accountId: "ab1234", bucket: "test-bucket", accessKeyId: "test-key", secretAccessKey: "test-secret" }, async (request) => {
    assert.equal(request.method, "PUT");
    assert.equal(request.url, `https://ab1234.r2.cloudflarestorage.com/test-bucket/${key}`);
    assert.equal(request.headers.get("if-none-match"), "*");
    assert.equal(request.headers.get("x-amz-storage-class"), "STANDARD");
    assert.match(request.headers.get("authorization"), /SignedHeaders=[^,]*if-none-match/);
    assert.deepEqual(new Uint8Array(await request.arrayBuffer()), Uint8Array.of(1, 2, 3));
    return new Response(null, { status: 412 });
  });
  assert.equal(await storage.put(key, Uint8Array.of(1, 2, 3)), "already_exists");
});

test("a failed upload releases the lease for a later retry without publishing a success", async (t) => {
  const { runBackup } = require("../src/backup/service");
  const options = { ...await fixture(t), now };
  const put = options.storage.put;
  options.storage.put = async () => { throw new Error("synthetic upload failure"); };
  await assert.rejects(runBackup(options));
  assert.equal(options.uploads.size, 0);
  assert.deepEqual(options.confirmed, []);
  options.storage.put = put;
  assert.deepEqual(await runBackup(options), { outcome: "uploaded", key });
  assert.deepEqual(options.confirmed, ["2026-11-27"]);
});

test("restore validates encrypted SQLite, preserves sync history, and changes the generation", async (t) => {
  const { runBackup } = require("../src/backup/service");
  const { restoreBackup } = require("../src/backup/restore");
  const options = { ...await fixture(t), now };
  await runBackup(options);
  const encrypted = join(options.temporary, "source.sqlite.age");
  const destination = join(options.temporary, "restored.sqlite");
  const identityFile = join(options.temporary, "identity.txt");
  await writeFile(encrypted, options.uploads.get(key));
  await writeFile(identityFile, `# synthetic age identity\n${options.identity}\n`);
  const result = await restoreBackup(encrypted, identityFile, destination);
  assert.equal(result.revision, 5);
  assert.notEqual(result.generation, "synthetic-generation");
  assert.deepEqual(await query(destination, "PRAGMA journal_mode"), [{ journal_mode: "wal" }]);
  assert.deepEqual(await query(destination, "SELECT * FROM sync_metadata"), [{ id: 1, generation: result.generation }]);
  assert.deepEqual(await query(destination, "SELECT * FROM sync_changes"), snapshot.tables.sync_changes);
  const before = await readFile(destination);
  await assert.rejects(restoreBackup(encrypted, identityFile, destination), { code: "EEXIST" });
  assert.deepEqual(await readFile(destination), before);
});

test("a snapshot with dangling account references is rejected before upload", async (t) => {
  const { runBackup } = require("../src/backup/service");
  const options = { ...await fixture(t), now };
  options.source.snapshot = async () => {
    const invalid = structuredClone(snapshot);
    invalid.tables.sync_accounts = [];
    return invalid;
  };
  await assert.rejects(runBackup(options));
  assert.equal(options.uploads.size, 0);
});

test("a live sync store snapshot restores account data and replay receipts", async (t) => {
  const { runBackup } = require("../src/backup/service");
  const { restoreBackup } = require("../src/backup/restore");
  const { createSyncStore } = require("../src/sync/store");
  const options = await fixture(t);
  const live = createClient({ url: "file::memory:" });
  t.after(() => live.close());
  const store = createSyncStore(live);
  await store.initialize();
  await live.execute("INSERT INTO sync_users (email, name, password_hash) VALUES ('test@example.com', 'Synthetic user', 'test-hash')");
  const mutation = { mutationId: "create-category", entity: "category", id: "synthetic-category", baseRevision: 0, data: JSON.parse(data) };
  const initial = await store.sync(account, { generation: null, cursor: 0, mutations: [mutation] });
  assert.deepEqual(await runBackup({ ...options, source: store, now }), { outcome: "uploaded", key });
  assert.equal(await store.lastBackupDate(), "2026-11-27");

  const encrypted = join(options.temporary, "store.sqlite.age");
  const destination = join(options.temporary, "restored-store.sqlite");
  const identityFile = join(options.temporary, "store-identity.txt");
  await writeFile(encrypted, options.uploads.get(key));
  await writeFile(identityFile, options.identity);
  const restoredState = await restoreBackup(encrypted, identityFile, destination);
  assert.deepEqual(await query(destination, "SELECT email FROM sync_users"), [{ email: "test@example.com" }]);
  // libsql retains native WAL handles on Windows after close(). A separate process
  // releases them before fixture cleanup, while exercising the restored store.
  const { stdout } = await promisify(execFile)(process.execPath, [
    "--import", "tsx", "-e", `
      const { createClient } = require('@libsql/client');
      const { createSyncStore } = require('./src/sync/store.ts');
      const [url, account, generation, cursor, mutation] = JSON.parse(process.argv[1]);
      const client = createClient({ url });
      (async () => {
        const store = createSyncStore(client);
        await store.initialize();
        const reset = await store.sync(account, { generation, cursor, mutations: [] });
        const replay = await store.sync(account, {
          generation: reset.generation, cursor: reset.cursor, mutations: [mutation],
        });
        process.stdout.write(JSON.stringify({ reset, replay }));
      })().catch((error) => {
        console.error(error);
        process.exitCode = 1;
      }).finally(() => client.close());
    `,
    JSON.stringify([pathToFileURL(destination).href, account, initial.generation, initial.cursor, mutation]),
  ], { cwd: join(__dirname, "..") });
  const { reset, replay } = JSON.parse(stdout);
  assert.equal(reset.reset, true);
  assert.equal(reset.generation, restoredState.generation);
  assert.deepEqual(reset.changes, initial.changes);
  assert.deepEqual(replay.acknowledgements, initial.acknowledgements);
  assert.deepEqual(replay.changes, []);
});

test("the cron caller replays a cold-start failure through the authenticated route", async () => {
  const { runScheduledBackup } = await import("../../cloudflare/backup-worker/worker.mjs");
  let requests = 0;
  const result = await runScheduledBackup({ BACKUP_TRIGGER_URL: "https://example.test/internal/backup", BACKUP_TRIGGER_TOKEN: "test-backup-token" }, async (url, options) => {
    assert.equal(url.toString(), "https://example.test/internal/backup");
    assert.equal(options.method, "POST");
    assert.equal(options.headers.authorization, "Bearer test-backup-token");
    requests += 1;
    return requests === 1 ? new Response(null, { status: 503 }) : Response.json({ outcome: "already_exists", key });
  }, async () => {});
  assert.equal(result, "already_exists");
  assert.equal(requests, 2);
});
