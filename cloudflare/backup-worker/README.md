# Daily backup Worker

The Worker calls the Render service at 06h15 UTC every day. The service creates an encrypted SQLite snapshot for the São Paulo calendar day, then uploads it to the private R2 bucket with a conditional write. The Worker retries a failed or busy call within the scheduled run.

Set `BACKUP_TRIGGER_URL` to the HTTPS URL of `POST /internal/backup`. It can be configured as a Worker variable or through `wrangler secret put`. Set `BACKUP_TRIGGER_TOKEN` through `wrangler secret put` and use the same randomly generated token for the Render service. Deploy from this directory with `npx wrangler deploy`.

Set `BACKUP_AGE_RECIPIENT`, `R2_ACCOUNT_ID`, `R2_BUCKET`, `R2_ACCESS_KEY_ID` and `R2_SECRET_ACCESS_KEY` in Render. The R2 key needs object read and write access scoped to this bucket. Store the matching age recovery identity separately. Only the public recipient is needed by Render.

Use a private R2 bucket with its public development URL disabled and no public custom domain. Set a lifecycle rule using separate administrative credentials. `lifecycle add` preserves existing rules.

```sh
npx wrangler r2 bucket lifecycle add YOUR_BUCKET dt-money-backups 'dt-money/backups/' --expire-days 10
npx wrangler r2 bucket lifecycle list YOUR_BUCKET
```

For synthetic testing, set a separate Turso database, bucket and `R2_BACKUP_PREFIX`. Substitute that prefix, including its trailing slash, in the lifecycle command. The trigger responds with `uploaded` or `already_exists` on success. A busy lease returns `409` and transient generation or upload failure returns `503`.

```sh
curl --fail-with-body -X POST -H "Authorization: Bearer $BACKUP_TRIGGER_TOKEN" "$BACKUP_TRIGGER_URL"
```

Download a selected backup using authenticated R2 access. From `DT-money-backend`, restore it to a new SQLite file in a private directory and import that file into a separate Turso database. The restore command checks the archive schema, validates the SQLite contents, creates a new sync generation and prepares WAL mode for import.

```sh
node --import tsx src/backup/restore-cli.ts /private/input.sqlite.age /private/age-identity.txt /private/dt-money-restored.db
turso db import /private/dt-money-restored.db
```

Point a test instance at the imported database and verify sync before changing the live service configuration. Pause writes during a live switch and retain the previous Turso database.

Deployment references include [Cron Triggers](https://developers.cloudflare.com/workers/configuration/cron-triggers/), [conditional writes](https://developers.cloudflare.com/r2/api/s3/conditional-operations/), [R2 lifecycle rules](https://developers.cloudflare.com/r2/buckets/object-lifecycles/), [Wrangler lifecycle commands](https://developers.cloudflare.com/r2/reference/wrangler-commands/), and [Turso database import](https://docs.turso.tech/cli/db/import).
