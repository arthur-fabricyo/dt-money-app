const assert = require('node:assert/strict');
const { test } = require('node:test');
const { createApp } = require('../src/server.ts');

const env = {
  DT_MONEY_SYNC_SQLITE_URL: 'file::memory:',
  APP_SECRET_KEY: 'synthetic-test-secret-at-least-32-characters',
};

test('the server entry point serves authenticated sync over HTTP with isolated accounts', async (t) => {
  const app = await createApp(env);
  t.after(() => app.close());
  const address = await app.listen({ host: '127.0.0.1', port: 0 });
  const post = async (path, payload, token) => fetch(address + path, {
    method: 'POST',
    headers: { 'content-type': 'application/json', ...(token && { authorization: `Bearer ${token}` }) },
    body: JSON.stringify(payload),
  });
  assert.equal((await fetch(address + '/health')).status, 200);
  const register = async (email) => {
    const response = await post('/auth/register', { email, password: 'synthetic-password', name: 'Teste' });
    assert.equal(response.status, 201);
    return response.json();
  };
  const own = await register('own@example.test');
  const other = await register('other@example.test');
  const mutation = {
    mutationId: 'synthetic-create', entity: 'transaction', id: 'synthetic-transaction', baseRevision: 0,
    data: { description: 'Teste', cents: 1, typeId: 1, categoryId: 'uncategorized', occurredAt: '2026-11-27T12:00:00Z' },
  };
  const request = { generation: null, cursor: 0, mutations: [mutation] };
  assert.equal((await post('/sync', request)).status, 401);
  const saved = await post('/sync', request, own.token);
  assert.equal(saved.status, 200);
  const initial = await saved.json();
  assert.equal(initial.acknowledgements[0].status, 'applied');
  const replay = await (await post('/sync', request, own.token)).json();
  assert.deepEqual(replay, initial);
  const isolated = await (await post('/sync', { ...request, mutations: [] }, other.token)).json();
  assert.deepEqual(isolated.changes, []);
  const status = await fetch(address + '/sync/backup-status', { headers: { authorization: `Bearer ${own.token}` } });
  assert.deepEqual(await status.json(), { lastBackupDate: null });
  assert.equal((await post('/internal/backup', {})).status, 503);
});

test('authentication bounds repeated requests before password work', async (t) => {
  const app = await createApp(env);
  t.after(() => app.close());
  for (let index = 0; index < 10; index++) {
    assert.equal((await app.inject({ method: 'POST', url: '/auth/login', payload: {} })).statusCode, 401);
  }
  const response = await app.inject({ method: 'POST', url: '/auth/login', payload: {} });
  assert.equal(response.statusCode, 429);
  assert.ok(Number(response.headers['retry-after']) > 0);
  assert.equal((await app.inject('/health')).statusCode, 200);
});

test('Render rejects missing or local-only database configuration', async () => {
  await assert.rejects(createApp({ ...env, RENDER: 'true' }), /Configura/);
  await assert.rejects(createApp({ RENDER: 'true' }), /Configura/);
});

test('different accounts retain independent auth budgets behind the same proxy', async (t) => {
  const app = await createApp(env);
  t.after(() => app.close());
  const attempt = (email) => app.inject({
    method: 'POST', url: '/auth/login', payload: { email, password: '' },
  });
  for (let index = 0; index < 10; index++) assert.equal((await attempt('first@example.test')).statusCode, 401);
  assert.equal((await attempt(' FIRST@example.test ')).statusCode, 429);
  assert.equal((await attempt('second@example.test')).statusCode, 401);
});
