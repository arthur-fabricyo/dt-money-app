import { z } from "zod";

const id = z.string().min(1).max(160).regex(/^[a-zA-Z0-9_.:-]+$/);
const revision = z.number().int().nonnegative().safe();
const categoryData = z.object({
  name: z.string().min(1).max(60).refine((name) => Boolean(name.trim())),
  color: z.string().regex(/^#[0-9a-fA-F]{6}$/),
  description: z.string().max(240),
  archived: z.boolean(),
}).strict();
const transactionData = z.object({
  description: z.string().max(500),
  cents: z.number().int().positive().safe(),
  typeId: z.union([z.literal(1), z.literal(2)]),
  categoryId: id,
  occurredAt: z.string().max(40).datetime({ offset: true }),
  importId: id.nullish(),
}).strict();

const mutationFields = {
  mutationId: id,
  id,
  baseRevision: revision,
};

export const syncRequestSchema = z.object({
  generation: id.nullable(),
  cursor: revision,
  mutations: z.array(z.discriminatedUnion("entity", [
    z.object({ ...mutationFields, entity: z.literal("category"), data: categoryData.nullable() }).strict(),
    z.object({ ...mutationFields, entity: z.literal("transaction"), data: transactionData.nullable() }).strict(),
  ])).max(100),
}).strict();

export type SyncRequest = z.infer<typeof syncRequestSchema>;
export type Mutation = SyncRequest["mutations"][number];
export type Entity = Mutation["entity"];
export type CategoryData = z.infer<typeof categoryData>;
export type TransactionData = z.infer<typeof transactionData>;
export type EntityData = CategoryData | TransactionData;

export type Change = {
  entity: Entity;
  id: string;
  revision: number;
  data: EntityData | null;
};

export interface Acknowledgement {
  mutationId: string;
  status: "applied" | "conflict";
  change: Change;
}

export interface SyncResponse {
  generation: string;
  cursor: number;
  reset: boolean;
  hasMore: boolean;
  changes: Change[];
  acknowledgements: Acknowledgement[];
}

export const UNCATEGORIZED = "uncategorized";
