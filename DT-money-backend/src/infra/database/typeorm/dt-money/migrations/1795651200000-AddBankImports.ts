import { MigrationInterface, QueryRunner } from "typeorm";

export class AddBankImports1795651200000 implements MigrationInterface {
  async up(query: QueryRunner): Promise<void> {
    await query.query("ALTER TABLE transactions ADD COLUMN import_id TEXT");
    await query.query("ALTER TABLE transactions ADD COLUMN occurred_at DATETIME");
    await query.query("ALTER TABLE transactions ADD COLUMN bank TEXT");
    await query.query("ALTER TABLE transactions ADD COLUMN method TEXT");
    await query.query("ALTER TABLE transactions ADD COLUMN date_source TEXT");
    await query.query("ALTER TABLE transactions ADD COLUMN category_source TEXT");
    await query.query("CREATE UNIQUE INDEX transaction_user_import ON transactions(user_id, import_id)");
    await query.query(`
      INSERT INTO transaction_categories (id, name)
      SELECT CASE WHEN EXISTS (SELECT 1 FROM transaction_categories WHERE id = 9001)
                  THEN (SELECT MAX(id) + 1 FROM transaction_categories)
                  ELSE 9001 END, 'Sem categoria'
      WHERE NOT EXISTS (SELECT 1 FROM transaction_categories WHERE name = 'Sem categoria')
    `);
  }

  async down(query: QueryRunner): Promise<void> {
    await query.query("DROP INDEX transaction_user_import");
    for (const name of ["import_id", "occurred_at", "bank", "method", "date_source", "category_source"]) {
      await query.query(`ALTER TABLE transactions DROP COLUMN ${name}`);
    }
  }
}
