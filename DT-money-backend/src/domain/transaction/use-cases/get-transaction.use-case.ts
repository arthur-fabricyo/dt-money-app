import { TransactionRepository } from "../../../infra/database/typeorm/dt-money/repositories/transaction.repository";
import {
  GetTransactionsParams,
} from "../repositoryInterface/transaction-repository.interface";

export class GetTransactionsUseCase {
  private transactionRepository: TransactionRepository;

  constructor() {
    this.transactionRepository = new TransactionRepository();
  }

  async execute(params: GetTransactionsParams) {
    if (params.dateBasis || params.probeImportIds) {
      return this.transactionRepository.readSnapshot(params);
    }
    const [transactions, totalTransactions] = await Promise.all([
      this.transactionRepository.getTransactions(params),
      this.transactionRepository.getTransactionTotals(params),
    ]);
    return { ...transactions, totalTransactions };
  }
}
