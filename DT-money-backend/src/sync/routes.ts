import type { FastifyInstance, FastifyReply, FastifyRequest } from "fastify";
import { syncRequestSchema } from "./contract";
import type { SyncStore } from "./store";

interface SyncRouteOptions {
  store: SyncStore;
  authenticate: (request: FastifyRequest, reply: FastifyReply) => Promise<string | undefined>;
}

export function registerSyncRoutes(server: FastifyInstance, { store, authenticate }: SyncRouteOptions): void {
  server.post("/sync", { bodyLimit: 512 * 1024 }, async (request, reply) => {
    const account = await authenticate(request, reply);
    if (reply.sent) return reply;
    if (!account) return reply.code(401).send({ message: "Sessão inválida." });
    const input = syncRequestSchema.safeParse(request.body);
    if (!input.success) return reply.code(400).send({ message: "Lote de sincronização inválido." });
    try {
      return reply.send(await store.sync(account, input.data));
    } catch {
      request.log.error("Sync persistence failed");
      return reply.code(503).send({ message: "Sincronização indisponível." });
    }
  });
}
