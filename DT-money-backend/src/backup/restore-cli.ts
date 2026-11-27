import { restoreBackup } from "./restore";

const [archive, identity, output] = process.argv.slice(2);
if (process.argv.length !== 5) {
  console.error("Usage: node --import tsx src/backup/restore-cli.ts <backup.sqlite.age> <age-identity-file> <new-database.sqlite>");
  process.exitCode = 1;
} else {
  restoreBackup(archive, identity, output).then(({ generation, revision }) => {
    console.log(`Restored sync revision ${revision} with generation ${generation}.`);
  }).catch((error: NodeJS.ErrnoException) => {
    console.error(error.code === "EEXIST" ? "The destination already exists." : "Restore failed. Check the archive, recovery identity and destination directory.");
    process.exitCode = 1;
  });
}
