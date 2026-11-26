import { Brackets, EntityManager, Repository } from "typeorm";
import { Transaction } from "../entities/Transaction";
import { DtMoneyDataSource } from "../data-source";
import {
  CreateTranscationParams,
  GetTransactionsParams,
  TransactionRepositoryInterface,
  TransactionTotalResponse,
  UpdateTransactionParams,
} from "../../../../../domain/transaction/repositoryInterface/transaction-repository.interface";
import { DatabaseError } from "../../../../../shared/errors/database.error";
import { Paginated } from "../../../../../interfaces/paginated";
import { NotFoundError } from "../../../../../shared/errors/not-found.error";
import { TransactionCategory } from "../entities/TransactionCategory";

// SQLite uses one connection in this data source. Keep writes outside another
// request's read transaction.
let transactionQueue = Promise.resolve();
function serialized<T>(operation: () => Promise<T>): Promise<T> {
  const result = transactionQueue.then(operation);
  transactionQueue = result.then(() => undefined, () => undefined);
  return result;
}

export class TransactionRepository implements TransactionRepositoryInterface {
  private transactionRepository: Repository<Transaction>;

  constructor(manager = DtMoneyDataSource.manager) {
    this.transactionRepository = manager.getRepository(Transaction);
  }

  readSnapshot(params: GetTransactionsParams) {
    return serialized(() => DtMoneyDataSource.transaction(async (manager: EntityManager) => {
      const repository = new TransactionRepository(manager);
      const page = await repository.getTransactions(params);
      const totalTransactions = await repository.getTransactionTotals(params);
      const scoped = manager.getRepository(Transaction).createQueryBuilder("movement")
        .withDeleted()
        .where("movement.userId = :userId", { userId: params.userId });
      const latest = await scoped.clone().select("COALESCE(MAX(movement.id), 0)", "id").getRawOne();
      const foundImportIds = params.probeImportIds?.length
        ? await scoped
          .select("movement.importId", "importId")
          .andWhere("movement.importId IN (:...ids)", { ids: params.probeImportIds })
          .getRawMany<{ importId: string }>()
        : [];
      return {
        ...page,
        totalTransactions,
        latestTransactionId: Number(latest.id),
        foundImportIds: foundImportIds.map((row) => row.importId),
      };
    }));
  }

  async createTransaction(
    params: CreateTranscationParams
  ): Promise<Transaction> {
    try {
      return await serialized(async () => {
        const { occurredAt, ...fields } = params;
        if (!fields.importId) return this.transactionRepository.save(fields);

        const identity = { userId: fields.userId, importId: fields.importId };
        const existing = await this.transactionRepository.findOne({ where: identity, withDeleted: true });
        if (existing) return existing;

        await this.transactionRepository.createQueryBuilder()
          .insert().values({ ...fields, occurredAt: new Date(occurredAt) })
          .orIgnore().execute();
        const created = await this.transactionRepository.findOne({ where: identity, withDeleted: true });
        if (!created) throw new Error("O lançamento importado não foi gravado.");
        return created;
      });
    } catch (error) {
      throw new DatabaseError("Falha ao criar transação", error);
    }
  }

  async deleteTransaction(transactionId: number): Promise<void> {
    try {
      await serialized(() => this.transactionRepository.softDelete(transactionId));
    } catch (error) {
      throw new DatabaseError("Falha ao excluir a transação", error);
    }
  }

  async findById(id: number): Promise<Transaction> {
    try {
      const transaction = await this.transactionRepository.findOne({
        where: { id },
      });

      if (!transaction) {
        throw new NotFoundError(`Transação com ID ${id} não encontrada`);
      }

      return transaction;
    } catch (error) {
      throw new DatabaseError("Falha ao buscar a transação por ID", error);
    }
  }

  async updateTransaction(params: UpdateTransactionParams): Promise<void> {
    try {
      const { id, userId, categoryId, ...fields } = params;
      await serialized(() => this.transactionRepository.createQueryBuilder().update()
        .set({
          ...fields,
          ...(categoryId === undefined ? {} : {
            categoryId,
            categorySource: () => "CASE WHEN import_id IS NOT NULL AND category_id <> :categoryId THEN 'manual' ELSE category_source END",
          }),
        })
        .where("id = :id AND user_id = :userId AND deleted_at IS NULL", { id, userId })
        .setParameter("categoryId", categoryId)
        .execute());
    } catch (error) {
      throw new DatabaseError("Falha ao atualizar a transação", error);
    }
  }

  updateImportCategory(userId: number, importId: string, categoryId: number, source: "manual" | "model") {
    return serialized(async () => {
      const catalog = this.transactionRepository.manager.getRepository(TransactionCategory);
      const category = await catalog.findOneBy({ id: categoryId });
      if (!category) return null;
      const query = this.transactionRepository.createQueryBuilder().update()
        .set({ categoryId, categorySource: source })
        .where("user_id = :userId AND import_id = :importId AND deleted_at IS NULL", { userId, importId });
      if (source === "model") {
        if (category.name === "Sem categoria") return null;
        const uncategorized = await catalog.findOneByOrFail({ name: "Sem categoria" });
        query.andWhere("category_id = :unset AND category_source IS NOT 'manual'", { unset: uncategorized.id });
      }
      await query.execute();
      return this.transactionRepository.findOne({ where: { userId, importId }, withDeleted: true });
    });
  }

  async getTransactionTotals({
    userId,
    filters,
    searchText,
    dateBasis,
  }: GetTransactionsParams): Promise<TransactionTotalResponse> {
    try {
      const query = this.transactionRepository
        .createQueryBuilder("transaction")
        .leftJoinAndSelect("transaction.type", "type")
        .leftJoinAndSelect("transaction.category", "category")
        .select([
          "COALESCE(SUM(CASE WHEN transaction.typeId = 1 THEN transaction.value ELSE 0 END), 0) AS totalRevenue",
          "COALESCE(SUM(CASE WHEN transaction.typeId = 2 THEN transaction.value ELSE 0 END), 0) AS totalExpense",
          "COALESCE(SUM(CASE WHEN transaction.typeId = 1 THEN transaction.value ELSE 0 END), 0) - COALESCE(SUM(CASE WHEN transaction.typeId = 2 THEN transaction.value ELSE 0 END), 0) AS total",
        ])
        .where("transaction.userId = :userId", { userId });
      const date = dateBasis === "movement"
        ? "COALESCE(transaction.occurredAt, transaction.createdAt)"
        : "transaction.createdAt";
      if (filters?.from) {
        query.andWhere(`${date} >= :from`, {
          from: filters.from,
        });
      }

      if (filters?.to) {
        query.andWhere(`${date} <= :to`, {
          to: filters.to,
        });
      }

      if (filters?.categoryIds?.length) {
        query.andWhere("category.id IN (:...categoryIds)", {
          categoryIds: filters.categoryIds,
        });
      }

      if (filters?.typeId) {
        query.andWhere("transaction.typeId = :typeId", {
          typeId: filters.typeId,
        });
      }

      if (searchText) {
        query.andWhere(
          new Brackets((qb) => {
            qb.orWhere("transaction.value LIKE :searchText", {
              searchText: `%${searchText}%`,
            })
              .orWhere("type.name LIKE :searchText", {
                searchText: `%${searchText}%`,
              })
              .orWhere("category.name LIKE :searchText", {
                searchText: `%${searchText}%`,
              })
              .orWhere("transaction.description LIKE :searchText", {
                searchText: `%${searchText}%`,
              });
          })
        );
      }

      const result = await query.getRawOne();

      return {
        revenue: Number(result.totalRevenue),
        expense: Number(result.totalExpense),
        total: Number(result.total),
      };
    } catch (error) {
      throw new DatabaseError("Falha ao calcular totais das transações", error);
    }
  }

  async getTransactions({
    userId,
    pagination,
    filters,
    searchText,
    sort,
    dateBasis,
  }: GetTransactionsParams): Promise<Paginated<Transaction>> {
    try {
      let totalRows = 0;
      let totalPages = 0;
      let page = 0;
      let perPage = 0;
      let transactions: Transaction[] = [];

      const query = this.transactionRepository
        .createQueryBuilder("transaction")
        .leftJoinAndSelect("transaction.type", "type")
        .leftJoinAndSelect("transaction.category", "category");

      const date = dateBasis === "movement"
        ? "COALESCE(transaction.occurredAt, transaction.createdAt)"
        : "transaction.createdAt";
      if (dateBasis === "movement") {
        query.addSelect(date, "movement_date");
        query.addOrderBy("movement_date", "DESC");
        query.addOrderBy("transaction.id", "DESC");
      } else if (sort?.id) {
        query.addOrderBy(
          "transaction.id",
          sort.id.toUpperCase() as "ASC" | "DESC"
        );
      } else {
        query.addOrderBy("transaction.id", "DESC");
      }

      query.where("transaction.userId = :userId", { userId });

      if (searchText) {
        query.andWhere(
          new Brackets((qb) => {
            qb.orWhere("transaction.value LIKE :searchText", {
              searchText: `%${searchText}%`,
            })
              .orWhere("type.name LIKE :searchText", {
                searchText: `%${searchText}%`,
              })
              .orWhere("category.name LIKE :searchText", {
                searchText: `%${searchText}%`,
              })
              .orWhere("transaction.description LIKE :searchText", {
                searchText: `%${searchText}%`,
              });
          })
        );
      }

      if (filters?.from) {
        query.andWhere(`${date} >= :from`, {
          from: filters.from,
        });
      }

      if (filters?.to) {
        query.andWhere(`${date} <= :to`, {
          to: filters.to,
        });
      }

      if (!dateBasis && filters?.to && !filters.from) {
        query.andWhere("transaction.createdAt >= :to", { to: filters.to });
      }

      if (filters?.categoryIds?.length) {
        query.andWhere("category.id IN (:...categoryIds)", {
          categoryIds: filters.categoryIds,
        });
      }

      if (filters?.typeId) {
        query.andWhere("type.id = :typeId", {
          typeId: filters.typeId,
        });
      }

      if (pagination) {
        const skip = (pagination.page - 1) * pagination.perPage;
        const take = pagination.perPage;

        query.skip(skip).take(take);

        const result = await query.getManyAndCount();

        transactions = result[0];
        totalRows = result[1];
        totalPages = Math.ceil(totalRows / pagination.perPage);
        page = pagination.page;
        perPage = pagination.perPage;
      } else {
        transactions = await query.getMany();
      }
      return {
        data: transactions,
        totalRows,
        totalPages,
        page,
        perPage,
      };
    } catch (error) {
      throw new DatabaseError("Falha ao buscar transações finançeiras", error);
    }
  }
}
