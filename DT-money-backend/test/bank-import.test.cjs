const assert = require("node:assert/strict");
require("ts-node").register({ transpileOnly: true, compilerOptions: { esModuleInterop: true } });
const { before, after, test } = require("node:test");
const fastify = require("fastify");
const jwt = require("jsonwebtoken");
const { DtMoneyDataSource: db } = require("../src/infra/database/typeorm/dt-money/data-source");
const { SeederService } = require("../src/infra/database/typeorm/dt-money/seeders");
const { configure: schemas } = require("../src/infra/web/config/schema");
const { configure: errors } = require("../src/infra/web/config/error-handler");
const { configure: transactions } = require("../src/infra/web/routes/transaction.routes");

let app;
const secret = "bank-import-test";
const headers = (email = "one@example.com") => ({
  authorization: `Bearer ${jwt.sign({ email }, secret)}`,
});
const movement = (key, overrides = {}) => ({
  importId: key.repeat(64),
  occurredAt: "2025-01-06T15:30:00.500Z",
  bank: "nubank",
  method: "pix",
  dateSource: "alert",
  typeId: 1,
  value: 12.34,
  categoryId: 1,
  description: "Pix de teste",
  ...overrides,
});
const create = (payload, email) =>
  app.inject({ method: "POST", url: "/transaction", payload, headers: headers(email) });

before(async () => {
  process.env.APP_SCRETET_KEY = secret;
  db.setOptions({ database: ":memory:" });
  await db.initialize();
  await db.runMigrations();
  await new SeederService(db).run();
  for (const email of ["one@example.com", "two@example.com"]) {
    await db.getRepository("User").save({ name: email, email, password: "test-password-hash" });
  }
  app = fastify();
  errors(app);
  schemas(app);
  transactions(app);
  await app.ready();
});

after(async () => {
  if (app) await app.close();
  if (db.isInitialized) await db.destroy();
});

test("create is idempotent per user, including a replay after deletion", async () => {
  const body = movement("a");
  const [first, repeat] = await Promise.all([create(body), create(body)]);
  assert.equal(first.statusCode, 200, first.body);
  assert.equal(first.json().importId, body.importId);
  assert.equal(first.json().id, repeat.json().id);
  const other = await create(body, "two@example.com");
  assert.equal(other.statusCode, 200, other.body);
  assert.notEqual(other.json().id, first.json().id);
  const deleted = await app.inject({
    method: "DELETE", url: `/transaction/${first.json().id}`, headers: headers(),
  });
  assert.ok(deleted.statusCode < 300, deleted.body);
  const replay = await create(body);
  assert.equal(replay.json().id, first.json().id);
  assert.ok(replay.json().deletedAt);
});

test("movement date, probe and totals share a snapshot beyond the returned page", async () => {
  const body = movement("b");
  const first = await create(body);
  const second = await create(movement("c", { bank: "santander", value: 2 }));
  assert.equal(first.statusCode, 200, first.body);
  assert.equal(second.statusCode, 200, second.body);
  const path = "/transaction?to=2025-01-06T23%3A59%3A59.999Z&page=1&perPage=1";
  const result = await app.inject({
    method: "GET", url: `${path}&dateBasis=movement&probeImportIds=${body.importId}`, headers: headers(),
  });
  assert.equal(result.statusCode, 200, result.body);
  assert.equal(result.json().data[0].id, second.json().id);
  assert.deepEqual(result.json().foundImportIds, [body.importId]);
  assert.equal(result.json().latestTransactionId, second.json().id);
  assert.equal(result.json().totalTransactions.revenue, 14.34);
  const legacy = await app.inject({ method: "GET", url: path, headers: headers() });
  assert.deepEqual(legacy.json().data, []);
});

test("a delayed model update preserves an explicit manual category", async () => {
  const categories = await app.inject({ method: "GET", url: "/transaction/categories", headers: headers() });
  const uncategorized = categories.json().find((entry) => entry.name === "Sem categoria");
  assert.ok(uncategorized);
  assert.ok(categories.json().some((entry) => entry.id === 1 && entry.name === "Casa"));
  const body = movement("d", { categoryId: uncategorized.id });
  const created = await create(body);
  assert.equal(created.statusCode, 200, created.body);
  const patch = (categoryId, source) => app.inject({
    method: "PATCH",
    url: `/transaction/imports/${body.importId}/category`,
    headers: headers(),
    payload: { categoryId, source },
  });
  const manual = await patch(uncategorized.id, "manual");
  assert.equal(manual.statusCode, 200, manual.body);
  const delayed = await patch(3, "model");
  assert.equal(delayed.statusCode, 200, delayed.body);
  assert.equal(delayed.json().categoryId, uncategorized.id);
});

test("an expired session is distinguishable from a transport or server failure", async () => {
  const token = jwt.sign({ email: "one@example.com", exp: 1 }, secret);
  const response = await app.inject({
    method: "POST", url: "/transaction", payload: movement("e"),
    headers: { authorization: `Bearer ${token}` },
  });
  assert.equal(response.statusCode, 401);
});
