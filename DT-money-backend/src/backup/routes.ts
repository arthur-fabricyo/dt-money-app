import { createHash, timingSafeEqual } from "node:crypto";
import type { FastifyInstance } from "fastify";
import { createR2Storage } from "./r2";
import { BackupResult, BackupSource, runBackup } from "./service";

type Trigger = () => Promise<BackupResult>;

export function registerBackupRoutes(app: FastifyInstance, source: BackupSource, env = process.env, trigger?: Trigger): void {
  const { BACKUP_TRIGGER_TOKEN: token } = env;
  const digest = (value: string) => createHash("sha256").update(value).digest();
  const expected = token && digest(token);
  app.post("/internal/backup", { schema: { hide: true } }, async (request, reply) => {
    if (!expected) return reply.code(503).send({ outcome: "unavailable" });
    const bearer = request.headers.authorization?.match(/^Bearer (\S+)$/i)?.[1];
    if (!bearer || !timingSafeEqual(expected, digest(bearer))) {
      return reply.code(401).header("WWW-Authenticate", "Bearer").send({ outcome: "unauthorized" });
    }

    try {
      const result = await (trigger ?? (() => runConfiguredBackup(source, env)))();
      request.log.info({ outcome: result.outcome, key: result.key }, "Daily backup trigger finished");
      return reply.code(result.outcome === "in_progress" ? 409 : 200).send(result);
    } catch {
      request.log.error("Daily backup trigger failed");
      return reply.code(503).send({ outcome: "failed" });
    }
  });
}

async function runConfiguredBackup(source: BackupSource, env: NodeJS.ProcessEnv): Promise<BackupResult> {
  const {
    BACKUP_AGE_RECIPIENT: recipient,
    R2_ACCOUNT_ID: accountId,
    R2_BUCKET: bucket,
    R2_ACCESS_KEY_ID: accessKeyId,
    R2_SECRET_ACCESS_KEY: secretAccessKey,
    R2_BACKUP_PREFIX: prefix,
  } = env;
  if (!recipient || !accountId || !bucket || !accessKeyId || !secretAccessKey) throw new Error("Backup task not configured");
  const storage = createR2Storage({ accountId, bucket, accessKeyId, secretAccessKey });
  return runBackup({ source, storage, recipient, prefix });
}
