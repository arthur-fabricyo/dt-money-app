import "dotenv/config";
import { createClient } from "@libsql/client";
import fastify from "fastify";
import { registerBackupRoutes } from "./backup/routes";
import { registerHostedAuth } from "./hosted-auth";
import * as Swagger from "./infra/web/config/swagger";
import * as Database from "./infra/database/";
import * as Cors from "./infra/web/config/cors";
import * as Schema from "./infra/web/config/schema";
import * as ErrorHandler from "./infra/web/config/error-handler";
import * as Routes from "./infra/web/routes";
import { registerSyncRoutes } from "./sync/routes";
import { createSyncStore } from "./sync/store";

export async function createApp(env: NodeJS.ProcessEnv = process.env) {
  const app = fastify({ bodyLimit: 256 * 1024 });
  const url = env.TURSO_DATABASE_URL?.trim();
  const token = env.TURSO_AUTH_TOKEN?.trim();
  const localSyncUrl = env.DT_MONEY_SYNC_SQLITE_URL?.trim();

  if (url || token || localSyncUrl || env.RENDER === "true") {
    if (url && localSyncUrl) throw new Error("Configure um único banco de sincronização.");
    if (url && (!token || !/^(libsql|https):\/\//.test(url))) {
      throw new Error("Configure uma URL libSQL segura e um token Turso.");
    }
    if ((localSyncUrl && (!localSyncUrl.startsWith("file:") || env.RENDER === "true")) ||
        (!url && !localSyncUrl) || (token && !url)) {
      throw new Error("Configuração incompleta do banco de sincronização.");
    }
    const secret = env.APP_SECRET_KEY;
    if (!secret || secret.length < 32) throw new Error("Configure APP_SECRET_KEY com pelo menos 32 caracteres.");

    const client = createClient({ url: url || localSyncUrl!, authToken: token });
    app.addHook("onClose", async () => client.close());
    try {
      const authenticate = await registerHostedAuth(app, client, secret);
      const store = createSyncStore(client);
      await store.initialize();
      registerSyncRoutes(app, { store, authenticate });
      registerBackupRoutes(app, store, env);
      app.get("/sync/backup-status", async (request, reply) => {
        if (!(await authenticate(request, reply))) return;
        return { lastBackupDate: await store.lastBackupDate() };
      });
    } catch (error) {
      await app.close();
      throw error;
    }
  } else {
    ErrorHandler.configure(app);
    Schema.configure(app);
    await Swagger.configure(app);
    await Cors.register(app);
    await Database.connect();
    Routes.register(app);
  }

  app.get("/health", async () => ({ status: "ok" }));
  return app;
}

async function start() {
  const port = Number(process.env.PORT || "3001");
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error("PORT inválida.");
  const host = process.env.DT_MONEY_HOST || (process.env.PORT ? "0.0.0.0" : "127.0.0.1");
  const app = await createApp();
  console.info(`DT Money ouvindo em ${await app.listen({ port, host })}`);
  for (const signal of ["SIGTERM", "SIGINT"] as const) {
    process.once(signal, () => {
      void app.close().catch(() => { process.exitCode = 1; });
    });
  }
}

if (require.main === module) {
  start().catch(() => {
    console.error("Não foi possível iniciar o DT Money. Confira a configuração do serviço e do banco.");
    process.exitCode = 1;
  });
}
