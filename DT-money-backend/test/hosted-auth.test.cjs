const assert = require('node:assert/strict');
const { test } = require('node:test');
const { mkdtemp, rm } = require('node:fs/promises');
const { join } = require('node:path');
const { tmpdir } = require('node:os');
const { pathToFileURL } = require('node:url');
const fastify = require('fastify');
const jwt = require('jsonwebtoken');
const { createClient } = require('@libsql/client');
const { registerHostedAuth } = require('../src/hosted-auth.ts');

test('hosted accounts survive an API restart and authenticate by stable identity', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'dt-auth-test-'));
  const url = pathToFileURL(join(directory, 'account.db')).href;
  const secret = 'test-only-hosted-auth-secret-with-enough-entropy';
  const password = 'synthetic password 1';
  let app;
  let client;

  async function start() {
    client = createClient({ url });
    app = fastify();
    const authenticate = await registerHostedAuth(app, client, secret);
    app.get('/identity', async (request, reply) => ({ id: await authenticate(request, reply) }));
  }

  async function stop() {
    await app?.close();
    client?.close();
  }

  try {
    await start();
    const created = await app.inject({ method: 'POST', url: '/auth/register', payload: {
      email: 'User@example.test', name: 'Teste', password,
    } });
    assert.equal(created.statusCode, 201);
    const original = created.json();
    assert.ok(Number.isSafeInteger(original.user.id));
    assert.equal(original.user.email, 'user@example.test');
    assert.equal(original.user.password_hash, undefined);
    const duplicate = await app.inject({ method: 'POST', url: '/auth/register', payload: {
      email: 'user@example.test', name: 'Outro', password,
    } });
    assert.equal(duplicate.statusCode, 409);
    await stop();
    await start();

    const login = await app.inject({ method: 'POST', url: '/auth/login', payload: {
      email: 'user@example.test', password,
    } });
    assert.equal(login.statusCode, 200);
    assert.equal(login.json().user.id, original.user.id);
    const allowed = await app.inject({ url: '/identity', headers: { authorization: `Bearer ${original.token}` } });
    assert.deepEqual(allowed.json(), { id: String(original.user.id) });

    const wrongAudience = jwt.sign({ sub: String(original.user.id) }, secret);
    const denied = await app.inject({ url: '/identity', headers: { authorization: `Bearer ${wrongAudience}` } });
    assert.equal(denied.statusCode, 401);
    const wrongPassword = await app.inject({ method: 'POST', url: '/auth/login', payload: {
      email: 'user@example.test', password: 'wrong password',
    } });
    assert.equal(wrongPassword.statusCode, 401);
  } finally {
    await stop();
    // libSQL's native handles may remain locked until process exit on Windows.
    await rm(directory, { recursive: true, force: true }).catch((error) => {
      if (process.platform !== 'win32' || error.code !== 'EBUSY') throw error;
    });
  }
});
