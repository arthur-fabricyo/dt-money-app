import type { Client, Row } from "@libsql/client";
import { compare, hash } from "bcrypt";
import { createHash, randomBytes } from "node:crypto";
import type { FastifyInstance, FastifyReply, FastifyRequest } from "fastify";
import jwt from "jsonwebtoken";
import { z } from "zod";
import rateLimit from "@fastify/rate-limit";

const audience = "dt-money-sync";
const issuer = "dt-money-api";

const credentials = z.object({
  email: z.string().trim().email().max(254).transform((email) => email.toLowerCase()),
  password: z.string().min(8).refine((password) => Buffer.byteLength(password, "utf8") <= 72),
});
const registration = credentials.extend({ name: z.string().trim().min(1).max(100) });

export async function registerHostedAuth(app: FastifyInstance, client: Client, secret: string) {
  await app.register(rateLimit, { global: false });
  const authOptions = {
    bodyLimit: 4096,
    config: { rateLimit: {
      max: 10,
      timeWindow: "1 minute",
      hook: "preHandler",
      keyGenerator(request: FastifyRequest) {
        const email = (request.body as { email?: unknown } | undefined)?.email;
        const account = typeof email === "string" ? email.trim().toLowerCase() : "";
        return request.ip + ":" + createHash("sha256").update(account).digest("hex");
      },
    } },
  };
  await client.execute(`CREATE TABLE IF NOT EXISTS sync_users (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    email TEXT UNIQUE NOT NULL,
    name TEXT NOT NULL,
    password_hash TEXT NOT NULL
  )`);
  const dummyHash = await hash(randomBytes(32).toString("hex"), 10);

  function session(row: Row) {
    const user = { id: Number(row.id), name: String(row.name), email: String(row.email) };
    const token = jwt.sign({}, secret, {
      algorithm: "HS256", issuer, audience, subject: String(user.id), expiresIn: "30d",
    });
    return { user, token };
  }

  app.post("/auth/register", authOptions, async (request, reply) => {
    const parsed = registration.safeParse(request.body);
    if (!parsed.success) return reply.code(422).send({ message: "Dados de cadastro inválidos." });

    const { email, password, name } = parsed.data;
    const passwordHash = await hash(password, 10);
    // Random IDs prevent a restored database from reassigning an old device profile to a new account.
    const id = randomBytes(6).readUIntBE(0, 6) + 1;
    const result = await client.execute({
      sql: `INSERT INTO sync_users (id, email, name, password_hash) VALUES (?, ?, ?, ?)
            ON CONFLICT(email) DO NOTHING RETURNING id, email, name`,
      args: [id, email, name, passwordHash],
    });
    const row = result.rows[0];
    if (!row) return reply.code(409).send({ message: "Já existe uma conta para este e-mail." });
    return reply.code(201).send(session(row));
  });

  app.post("/auth/login", authOptions, async (request, reply) => {
    const parsed = credentials.safeParse(request.body);
    if (!parsed.success) return reply.code(401).send({ message: "Credenciais inválidas." });

    const { password, email } = parsed.data;
    const result = await client.execute({
      sql: "SELECT id, email, name, password_hash FROM sync_users WHERE email = ?",
      args: [email],
    });
    const row = result.rows[0];
    const valid = await compare(password, row ? String(row.password_hash) : dummyHash);
    if (!row || !valid) return reply.code(401).send({ message: "Credenciais inválidas." });
    return session(row);
  });

  return async function authenticate(request: FastifyRequest, reply: FastifyReply): Promise<string | undefined> {
    const match = /^Bearer ([^\s]+)$/i.exec(request.headers.authorization ?? "");
    let accountId: string | undefined;
    if (match) {
      try {
        const payload = jwt.verify(match[1], secret, { algorithms: ["HS256"], audience, issuer });
        if (typeof payload !== "string" && typeof payload.sub === "string" &&
            /^[1-9]\d{0,14}$/.test(payload.sub)) {
          accountId = payload.sub;
        }
      } catch {
        accountId = undefined;
      }
    }
    if (accountId) {
      const result = await client.execute({ sql: "SELECT id FROM sync_users WHERE id = ?", args: [Number(accountId)] });
      if (result.rows.length) return accountId;
    }
    await reply.code(401).send({ message: "Sessão inválida ou expirada." });
    return undefined;
  };
}
