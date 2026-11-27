import { randomUUID } from "node:crypto";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { SyncSnapshot } from "../sync/schema";
import { writeSnapshot } from "./sqlite";

export const defaultBackupPrefix = "dt-money/backups/";
const leaseDuration = 2 * 60 * 1000;
const dateInSaoPaulo = new Intl.DateTimeFormat("en-US", {
  timeZone: "America/Sao_Paulo",
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
});

export interface BackupSource {
  snapshot(): Promise<SyncSnapshot>;
  acquireBackupLease(day: string, owner: string, now: number, expiresAt: number): Promise<boolean>;
  releaseBackupLease(day: string, owner: string): Promise<void>;
  confirmBackup(day: string): Promise<void>;
}

export interface BackupStorage {
  exists(key: string): Promise<boolean>;
  put(key: string, bytes: Uint8Array): Promise<"uploaded" | "already_exists">;
}

export interface BackupResult {
  outcome: "uploaded" | "already_exists" | "in_progress";
  key: string;
}

export async function runBackup({ source, storage, recipient, prefix = defaultBackupPrefix, now = new Date() }: {
  source: BackupSource;
  storage: BackupStorage;
  recipient: string;
  prefix?: string;
  now?: Date;
}): Promise<BackupResult> {
  if (!/^(?:[a-zA-Z0-9_-]+\/)+$/.test(prefix)) throw new Error("Invalid backup prefix");
  const date = dateInSaoPaulo.formatToParts(now);
  const day = ["year", "month", "day"].map((part) => date.find(({ type }) => type === part)?.value).join("-");
  const key = `${prefix}${day}.sqlite.age`;
  const owner = randomUUID();
  const time = now.getTime();
  if (!await source.acquireBackupLease(day, owner, time, time + leaseDuration)) {
    return { outcome: "in_progress", key };
  }

  try {
    if (await storage.exists(key)) {
      await source.confirmBackup(day);
      return { outcome: "already_exists", key };
    }
    const { Encrypter } = await import("age-encryption");
    const age = new Encrypter();
    age.addRecipient(recipient);
    const directory = await mkdtemp(join(tmpdir(), "dt-money-backup-"));
    let encrypted: Uint8Array;
    try {
      const filename = join(directory, "backup.sqlite");
      await writeSnapshot(filename, await source.snapshot());
      encrypted = await age.encrypt(await readFile(filename));
    } finally {
      await rm(directory, { recursive: true, force: true });
    }
    const outcome = await storage.put(key, encrypted);
    await source.confirmBackup(day);
    return { outcome, key };
  } finally {
    await source.releaseBackupLease(day, owner);
  }
}
