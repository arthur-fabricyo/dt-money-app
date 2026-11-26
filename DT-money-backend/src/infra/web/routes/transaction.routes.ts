import { FastifyInstance } from "fastify";
import S from "fluent-json-schema";
import { TransactionRepository } from "../../database/typeorm/dt-money/repositories/transaction.repository";
import { CreateTransactionController } from "../controllers/transaction/create-transcation.controller";
import { CheckAuthtenticationMiddleware } from "../middlewares/check-authentication";
import { createTransactionSchema } from "./schemas/transactions/create-transaction";
import { deleteTransactionSchema } from "./schemas/transactions/delete-transaction";
import { DeleteTransactionController } from "../controllers/transaction/delete-transcation.controller";
import { UpdateTransactionController } from "../controllers/transaction/update-transcation.controller";
import { updateTransactionSchema } from "./schemas/transactions/update-transaction";
import { GetTransactionController } from "../controllers/transaction/get-transcation.controller";
import { getTransactionSchema } from "./schemas/transactions/get-transaction";
import { getTransactionDataSchema } from "./schemas/transactions/get-transaction-data";
import { GetTransactionData } from "../controllers/transaction/get-transaction-data.controller";

export const configure = (fastify: FastifyInstance) => {
  const createTransaction = new CreateTransactionController();
  const deleteTransaction = new DeleteTransactionController();
  const updateTransaction = new UpdateTransactionController();
  const getTransaction = new GetTransactionController();
  const getTransactionData = new GetTransactionData();
  const checkAuthenticated = new CheckAuthtenticationMiddleware();

  fastify.patch<{
    Params: { importId: string };
    Body: { categoryId: number; source: "model" | "manual" };
  }>("/transaction/imports/:importId/category", {
    preHandler: [checkAuthenticated.execute],
    schema: {
      params: S.object().prop("importId", S.string().pattern("^[a-f0-9]{64}$").required()),
      body: S.object()
        .prop("categoryId", S.integer().minimum(1).required())
        .prop("source", S.string().enum(["model", "manual"]).required()),
      response: { 200: S.ref("Transaction#") },
    },
  }, async (request, reply) => {
    const repository = new TransactionRepository();
    const { categoryId, source } = request.body;
    const transaction = await repository.updateImportCategory(
      request.user.id, request.params.importId, categoryId, source
    );
    if (!transaction) return reply.code(422).send({ message: "Importação ou categoria inválida." });
    return reply.send(transaction);
  });

  fastify.route({
    url: "/transaction/categories",
    method: "get",
    handler: getTransactionData.execute,
    preHandler: [checkAuthenticated.execute],
    schema: getTransactionDataSchema,
  });

  fastify.route({
    url: "/transaction",
    method: "get",
    handler: getTransaction.execute,
    preHandler: [checkAuthenticated.execute],
    schema: getTransactionSchema,
  });

  fastify.route({
    url: "/transaction",
    method: "post",
    handler: createTransaction.execute,
    preHandler: [checkAuthenticated.execute],
    schema: createTransactionSchema,
  });

  fastify.route({
    url: "/transaction/:id",
    method: "delete",
    handler: deleteTransaction.execute,
    preHandler: [checkAuthenticated.execute],
    schema: deleteTransactionSchema,
  });

  fastify.route({
    url: "/transaction",
    method: "put",
    handler: updateTransaction.execute,
    preHandler: [checkAuthenticated.execute],
    schema: updateTransactionSchema,
  });
};
