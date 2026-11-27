import { randomUUID } from "node:crypto";
import { link, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { execute, validateSnapshot, withDatabase } from "./sqlite";

export async function restoreBackup(encrypted: string, identityFile: string, output: string): Promise<{ generation: string; revision: number }> {
  const { Decrypter } = await import("age-encryption");
  const decrypter = new Decrypter();
  const identities = (await readFile(identityFile, "utf8")).split(/\r?\n/).map((line) => line.trim())
    .filter((line) => line && !line.startsWith("#"));
  for (const identity of identities) decrypter.addIdentity(identity);
  const destination = resolve(output);
  const directory = await mkdtemp(join(dirname(destination), ".dt-money-restore-"));
  try {
    const snapshot = join(directory, "restore.sqlite");
    await writeFile(snapshot, await decrypter.decrypt(await readFile(encrypted)), { flag: "wx", mode: 0o600 });
    const result = await withDatabase(snapshot, true, async (database) => {
      const { revision } = await validateSnapshot(database);
      const generation = randomUUID();
      await execute(database, "PRAGMA journal_mode = WAL");
      await execute(database, "UPDATE sync_metadata SET generation = ? WHERE id = 1", [generation]);
      await execute(database, "PRAGMA wal_checkpoint(TRUNCATE)");
      return { generation, revision };
    });
    // Publish only the validated file, without replacing any existing database.
    await link(snapshot, destination);
    return result;
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
}
