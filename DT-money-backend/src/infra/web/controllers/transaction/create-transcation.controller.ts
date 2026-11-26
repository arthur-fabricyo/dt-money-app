import { FastifyReply, FastifyRequest } from "fastify";
import { CreateTransactionUseCase } from "../../../../domain/transaction/use-cases/create-transaction.use-case";
import { CreateTranscationParams } from "../../../../domain/transaction/repositoryInterface/transaction-repository.interface";

export class CreateTransactionController {
  private createTransactionUseCase: CreateTransactionUseCase;

  constructor() {
    this.createTransactionUseCase = new CreateTransactionUseCase();
  }

  execute = async (
    request: FastifyRequest<{ Body: CreateTranscationParams }>,
    reply: FastifyReply
  ) => {
    const body = request.body;
    const userId = request.user.id;
    const metadata = [body.importId, body.occurredAt, body.bank, body.method, body.dateSource];
    if (metadata.some(Boolean) && (
      !metadata.every(Boolean) || ![1, 2].includes(body.typeId) ||
      (body.method !== "pix" && body.typeId !== 2) || !body.description?.trim() ||
      !Number.isFinite(body.value) || body.value <= 0 ||
      !Number.isSafeInteger(Math.round(body.value * 100)) ||
      Math.abs(body.value * 100 - Math.round(body.value * 100)) > 1e-7
    )) {
      return reply.status(422).send({ message: "Metadados de importação inválidos." });
    }
    const transaction = await this.createTransactionUseCase.execute({
      ...body,
      userId,
    });

    reply.status(200).send(transaction);
  };
}
