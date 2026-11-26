import { FastifyReply, FastifyRequest } from "fastify";
import jwt from "jsonwebtoken";
import { UserTypeormRepository } from "../../database/typeorm/dt-money/repositories/user.repository";

export class CheckAuthtenticationMiddleware {
  private authRepository: UserTypeormRepository;

  constructor() {
    this.authRepository = new UserTypeormRepository();
  }

  execute = async (request: FastifyRequest, reply: FastifyReply) => {
    const authorizationHeader = request.headers?.authorization;

    if (!authorizationHeader) {
      return reply.code(401).send({ message: "Sessão inválida." });
    }

    const [, token] = authorizationHeader.split(" ");

    if (!token || token === "") {
      return reply.code(401).send({ message: "Sessão inválida." });
    }

    let email: string;
    try {
      const payload = jwt.verify(token, process.env.APP_SCRETET_KEY);
      if (typeof payload === "string" || typeof payload.email !== "string") {
        throw new Error("Invalid token claims");
      }
      email = payload.email;
    } catch {
      return reply.code(401).send({ message: "Sessão expirada ou inválida." });
    }

    try {
      const user = await this.authRepository.findByEmail(email);

      request.user = user;
    } catch (error) {
      throw new Error("Falha ao buscar usuário");
    }
  };
}
