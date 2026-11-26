import { FastifySchema } from "fastify";
import S from "fluent-json-schema";

const body = S.object()
  .prop("typeId", S.number().required())
  .prop("categoryId", S.number().required())
  .prop("value", S.number().required())
  .prop("description", S.string())
  .prop("importId", S.string().pattern("^[a-f0-9]{64}$"))
  .prop("occurredAt", S.string().format("date-time"))
  .prop("bank", S.string().enum(["nubank", "santander"]))
  .prop("method", S.string().enum(["pix", "credit", "debit", "unknown"]))
  .prop("dateSource", S.string().enum(["alert", "posted"]));

const successResponse = S.ref("Transaction#");

export const createTransactionSchema: FastifySchema = {
  tags: ["Transaction"],
  body,
  security: [{ bearerAuth: [] }],
  response: {
    200: successResponse,
    401: {
      $ref: "Unauthorized#",
    },
    422: {
      $ref: "UnprocessableEntity#",
    },
    500: {
      $ref: "ServerError#",
    },
  },
};
