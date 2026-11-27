const assert = require('node:assert/strict');
const { execFile } = require('node:child_process');
const { mkdtemp, rm } = require('node:fs/promises');
const { tmpdir } = require('node:os');
const { join } = require('node:path');
const { pathToFileURL } = require('node:url');
const { promisify } = require('node:util');
const { test } = require('node:test');
const fastify = require('fastify');
const { createClient } = require('@libsql/client');
const { createSyncStore } = require('../src/sync/store.ts');
const { registerSyncRoutes } = require('../src/sync/routes.ts');

async function openSync(t, url) {
  const client = createClient({ url });
  t.after(() => client.close());
  const store = createSyncStore(client);
  await store.initialize();
  const server = fastify();
  t.after(() => server.close());
  const sessions = new Map([['Bearer own-session', 'account-1'], ['Bearer other-session', 'account-2']]);
  registerSyncRoutes(server, {
    store,
    authenticate: async (request) => sessions.get(request.headers.authorization),
  });
  const inject = (body, session) => server.inject({
    method: 'POST',
    url: '/sync',
    headers: session ? { authorization: session } : {},
    payload: body,
  });
  const sync = async (mutations = [], { session = 'Bearer own-session', generation = null, cursor = 0 } = {}) => {
    const response = await inject({ generation, cursor, mutations }, session);
    assert.equal(response.statusCode, 200, response.body);
    return response.json();
  };
  return { client, store, inject, sync };
}

async function fileDatabase(t) {
  const dir = await mkdtemp(join(tmpdir(), 'dt-money-sync-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  return pathToFileURL(join(dir, 'sync.sqlite')).href;
}

async function runFileStore(url, action, ...args) {
  // Exercise an actual process restart and release Windows' native file handles before cleanup.
  const { stdout } = await promisify(execFile)(process.execPath, [
    '--import', 'tsx', '-e', `
      const { createClient } = require('@libsql/client');
      const { createSyncStore } = require('./src/sync/store.ts');
      const client = createClient({ url: process.argv[1] });
      const store = createSyncStore(client);
      (async () => {
        await store.initialize();
        const result = await (${action})(store, ...JSON.parse(process.argv[2]));
        process.stdout.write(JSON.stringify(result));
      })().catch((error) => {
        console.error(error);
        process.exitCode = 1;
      }).finally(() => client.close());
    `, url, JSON.stringify(args),
  ], { cwd: join(__dirname, '..') });
  return JSON.parse(stdout);
}

const category = (mutationId = 'category-create', fields = {}, baseRevision = 0) => ({
  mutationId, entity: 'category', id: 'groceries', baseRevision,
  data: { name: 'Market', color: '#bb5533', description: 'Food for home', archived: false, ...fields },
});
const transaction = (mutationId = 'transaction-create', id = 'receipt', fields = {}, baseRevision = 0) => ({
  mutationId, entity: 'transaction', id, baseRevision,
  data: {
    description: 'Test receipt', cents: 1450, typeId: 2, categoryId: 'uncategorized',
    occurredAt: '2026-11-27T10:00:00.000Z', ...fields,
  },
});

test('lost acknowledgements replay the original outcome after a restart', async (t) => {
  const url = await fileDatabase(t);
  const first = await runFileStore(url, async (store, create, edit) => {
    const initial = await store.sync('account-1', { mutations: [create], generation: null, cursor: 0 });
    await store.sync('account-1', { mutations: [edit], generation: initial.generation, cursor: initial.cursor });
    return initial;
  }, category(), category('edit-name', { name: 'Food' }, 1));

  const { retry, history } = await runFileStore(url, async (store, create, generation, cursor) => ({
    retry: await store.sync('account-1', { mutations: [create], generation, cursor }),
    history: await store.sync('account-1', { mutations: [], generation: null, cursor: 0 }),
  }), category(), first.generation, first.cursor);
  assert.deepEqual(retry.acknowledgements, first.acknowledgements);
  assert.deepEqual(retry.changes.map(({ revision, data }) => [revision, data.name]), [[2, 'Food']]);
  assert.deepEqual(history.changes.map(({ revision }) => revision), [1, 2]);
});

test('authentication scopes entities, mutations and import IDs to the session account', async (t) => {
  const { inject, sync } = await openSync(t, 'file::memory:');
  const create = transaction('create-import', 'receipt', { importId: 'same-import' });
  const unauthorized = await inject({ generation: null, cursor: 0, mutations: [create] });
  assert.equal(unauthorized.statusCode, 401);
  const own = await sync([create]);
  const other = { session: 'Bearer other-session' };
  assert.deepEqual((await sync([], other)).changes, []);
  const independentlyCreated = await sync(
    [transaction('create-import', 'receipt', { importId: 'same-import', cents: 1900 })], other
  );
  assert.equal(independentlyCreated.acknowledgements[0].change.data.cents, 1900);
  assert.deepEqual((await sync()).changes, own.changes);
  assert.equal((await inject({ generation: null, cursor: 0, mutations: [], account: 'account-2' }, 'Bearer own-session')).statusCode, 400);
});

test('import identities deduplicate across different entity IDs and remain reserved on deletion', async (t) => {
  const { sync } = await openSync(t, 'file::memory:');
  const first = transaction('first-import', 'first-receipt', { importId: 'shared-import' });
  const duplicate = transaction('duplicate', 'second-receipt', { importId: 'shared-import' });
  const created = await sync([first, duplicate]);
  assert.deepEqual(created.changes.map(({ id }) => id), ['first-receipt']);
  assert.deepEqual(created.acknowledgements[1], {
    mutationId: 'duplicate', status: 'applied', change: created.changes[0],
  });
  const tombstone = { ...first, mutationId: 'delete', baseRevision: 1, data: null };
  await sync([tombstone]);
  const lateImport = await sync([{ ...duplicate, mutationId: 'late-import' }]);
  assert.deepEqual(lateImport.acknowledgements[0].change, {
    id: 'first-receipt', entity: 'transaction', revision: 2, data: null,
  });
  assert.equal(lateImport.changes.length, 2);
});

test('stale independent field edits merge while same-field edits and edits against deletions conflict', async (t) => {
  const { sync } = await openSync(t, 'file::memory:');
  await sync([category()]);
  await sync([category('device-1', { name: 'Food' }, 1)]);
  const merged = await sync([category('device-2', { color: '#0022ff' }, 1)]);
  assert.deepEqual(merged.acknowledgements[0], {
    mutationId: 'device-2', status: 'applied',
    change: { entity: 'category', id: 'groceries', revision: 3, data: {
      name: 'Food', color: '#0022ff', description: 'Food for home', archived: false,
    } },
  });
  const competingName = category('device-3', { name: 'Restaurants' }, 1);
  const conflict = await sync([competingName]);
  assert.equal(conflict.acknowledgements[0].status, 'conflict');
  assert.deepEqual(conflict.acknowledgements[0].change, merged.acknowledgements[0].change);
  await sync([{ ...category('delete-category', {}, 3), data: null }]);
  const staleEdit = await sync([category('after-deletion', { color: '#770022' }, 2), competingName]);
  assert.deepEqual(staleEdit.acknowledgements[0], {
    mutationId: 'after-deletion', status: 'conflict',
    change: { entity: 'category', id: 'groceries', revision: 4, data: null },
  });
  assert.deepEqual(staleEdit.acknowledgements[1], conflict.acknowledgements[0]);
});

test('archived categories keep historical links and fall back for new transactions', async (t) => {
  const { sync } = await openSync(t, 'file::memory:');
  await sync([category(), transaction('old', 'old', { categoryId: 'groceries' })]);
  await sync([category('archive', { archived: true }, 1)]);
  const report = await sync([
    transaction('edit-old', 'old', { categoryId: 'groceries', cents: 1500 }, 1),
    transaction('new', 'new', { categoryId: 'groceries' }),
  ]);
  assert.deepEqual(report.acknowledgements.map(({ change }) => change.data.categoryId), ['groceries', 'uncategorized']);
  const fallbackDeletion = await sync([{ ...category('delete-fallback'), id: 'uncategorized', data: null }]);
  assert.equal(fallbackDeletion.acknowledgements[0].status, 'conflict');
  assert.equal(fallbackDeletion.acknowledgements[0].change.data.archived, false);
});

test('restored generations reset pagination while deferring submitted mutations', async (t) => {
  const { client, sync } = await openSync(t, 'file::memory:');
  const old = await sync([category()]);
  await client.execute("UPDATE sync_metadata SET generation = 'restored' WHERE id = 1");
  const pending = transaction();
  const reset = await sync([pending], { generation: old.generation, cursor: old.cursor });
  assert.equal(reset.generation, 'restored');
  assert.equal(reset.reset, true);
  assert.deepEqual(reset.changes, old.changes);
  assert.deepEqual(reset.acknowledgements, []);
  const reconciled = await sync([pending], { generation: reset.generation, cursor: reset.cursor });
  assert.equal(reconciled.reset, false);
  assert.ok(reconciled.cursor > reset.cursor);
  assert.deepEqual(reconciled.changes.map(({ id }) => id), ['receipt']);
  assert.equal(reconciled.acknowledgements[0].status, 'applied');
});

test('sync validates the entire batch and pages the authenticated history', async (t) => {
  const { inject, sync } = await openSync(t, 'file::memory:');
  const session = 'Bearer own-session';
  const invalid = [
    transaction('bad-cents', 'bad', { cents: 10.25 }),
    transaction('raw-banking-text', 'bad', { rawText: 'Private notification' }),
    transaction('invalid-timestamp', 'bad', { occurredAt: 'invalid' }),
  ];
  for (const mutation of invalid) {
    assert.equal((await inject({ generation: null, cursor: 0, mutations: [category(), mutation] }, session)).statusCode, 400);
  }
  const batch = Array.from({ length: 100 }, (_, index) => transaction(`create-${index}`, `entry-${index}`));
  assert.equal((await inject({ generation: null, cursor: 0, mutations: [...batch, category()] }, session)).statusCode, 400);
  assert.deepEqual((await sync()).changes, []);
  await sync(batch);
  await sync([category()]);
  const first = await sync();
  assert.equal(first.hasMore, true);
  assert.equal(first.changes.length, 100);
  const rest = await sync([], { generation: first.generation, cursor: first.cursor });
  assert.equal(rest.hasMore, false);
  assert.deepEqual(rest.changes.map(({ id }) => id), ['groceries']);
});

test('concurrent replay writes one revision and returns the same receipt', async (t) => {
  const { sync } = await openSync(t, 'file::memory:');
  const [first, second] = await Promise.all([sync([category()]), sync([category()])]);
  assert.deepEqual(first.acknowledgements[0], second.acknowledgements[0]);
  assert.equal((await sync()).changes.length, 1);
});

test('snapshot includes auth, history and receipts and excludes expiring backup leases', async (t) => {
  const { client, store, sync } = await openSync(t, 'file::memory:');
  await client.execute({
    sql: 'INSERT INTO sync_users (id, email, name, password_hash) VALUES (?, ?, ?, ?)',
    args: [123, 'test@example.org', 'Test', 'example-hash'],
  });
  const now = 1_000;
  assert.equal(await store.acquireBackupLease('2026-11-27', 'worker-1', now, 2_000), true);
  assert.equal(await store.acquireBackupLease('2026-11-27', 'worker-2', now, 2_500), false);
  assert.equal(await store.acquireBackupLease('2026-11-27', 'worker-2', 2_001, 3_000), true);
  await store.releaseBackupLease('2026-11-27', 'worker-1');
  assert.equal(await store.acquireBackupLease('2026-11-27', 'worker-3', 2_100, 3_000), false);
  await store.releaseBackupLease('2026-11-27', 'worker-2');
  assert.equal(await store.acquireBackupLease('2026-11-27', 'worker-3', 2_100, 3_000), true);

  const latest = await sync([category()]);
  const snapshot = await store.snapshot();
  assert.equal(snapshot.generation, latest.generation);
  assert.equal(snapshot.revision, latest.cursor);
  assert.equal(snapshot.tables.sync_users[0].email, 'test@example.org');
  assert.equal(snapshot.tables.sync_changes.length, 1);
  assert.equal(snapshot.tables.sync_receipts.length, 1);
  assert.equal(snapshot.tables.sync_backup_leases, undefined);
});

test('backup confirmation keeps the newest successful date across restarts', async (t) => {
  const url = await fileDatabase(t);
  const before = await runFileStore(url, async (store) => {
    const initial = await store.lastBackupDate();
    await store.confirmBackup('2026-11-27');
    await store.confirmBackup('2026-11-26');
    return initial;
  });
  assert.equal(before, null);
  const result = await runFileStore(url, async (store) => ({
    day: await store.lastBackupDate(), tables: Object.keys((await store.snapshot()).tables),
  }));
  assert.equal(result.day, '2026-11-27');
  assert.ok(!result.tables.includes('sync_backup_status'));
});
