import { z } from 'zod';
import type {
  AnalysisEvent,
  AnalysisRun,
  CanvasLayout,
  CanvasProjection,
  Evidence,
  InspectionData,
  Project,
  SemanticChange,
  SemanticNode,
  SemanticNodeDetail,
  Settings,
} from './types';

const id = z.string().min(1);
const confidence = z.number().min(0).max(1);
const fields = z.record(z.unknown());
export const projectSchema: z.ZodType<Project> = z.object({
  id,
  name: z.string(),
  description: z.string(),
  repositoryPath: z.string(),
  createdAt: z.string(),
});
export const runSchema: z.ZodType<AnalysisRun> = z.object({
  id,
  projectId: id,
  status: z.string(),
  revision: z.string().nullable(),
  createdAt: z.string(),
  finishedAt: z.string().nullable(),
  progress: z.number().min(0).max(100),
  currentStage: z.string().nullable(),
});
export const eventSchema: z.ZodType<AnalysisEvent> = z.object({
  sequenceNumber: z.number().int().nonnegative(),
  type: z.string(),
  message: z.string(),
  progress: z.number().min(0).max(100),
  createdAt: z.string(),
});
const nodeObject = z.object({
  id,
  stableKey: id,
  kind: z.string(),
  label: z.string(),
  subtitle: z.string().nullable(),
  confidence,
  supportLevel: z.string(),
  evidenceCount: z.number().int().nonnegative(),
  reviewStatus: z.string(),
  badges: z.array(z.string()),
  properties: fields,
});
export const nodeSchema: z.ZodType<SemanticNode> = nodeObject;
export const detailSchema: z.ZodType<SemanticNodeDetail> = nodeObject.extend({
  facts: z.array(z.unknown()).optional(),
  scenarios: z.array(z.unknown()).optional(),
  assertions: z.array(z.unknown()).optional(),
  enrichments: z.array(z.unknown()).optional(),
  reviews: z.array(z.unknown()).optional(),
});
export const projectionSchema: z.ZodType<CanvasProjection> = z.object({
  nodes: z.array(nodeSchema),
  edges: z.array(
    z.object({
      id,
      source: id,
      target: id,
      kind: z.string(),
      label: z.string().nullable(),
      confidence,
      evidenceCount: z.number().int().nonnegative(),
    }),
  ),
  truncated: z.boolean(),
  totalNodes: z.number().int().nonnegative(),
});
export const layoutSchema: z.ZodType<CanvasLayout> = z.object({
  positions: z.record(
    z.object({ x: z.number().finite(), y: z.number().finite() }),
  ),
  viewport: z.object({
    x: z.number().finite(),
    y: z.number().finite(),
    zoom: z.number().min(0.01).max(10),
  }),
});
export const evidenceSchema: z.ZodType<Evidence> = z
  .object({
    id,
    filePath: z.string(),
    startLine: z.number().int().positive(),
    endLine: z.number().int().positive(),
    snippet: z.string().nullable(),
    snippetHash: z.string().nullable(),
    analyzerId: z.string(),
    analyzerVersion: z.string().nullable(),
    imageDigest: z.string().nullable(),
    origin: z.string(),
    verified: z.boolean(),
    sourceAvailable: z.boolean().optional(),
  })
  .refine((value) => value.endLine >= value.startLine, {
    message: 'Invalid evidence line range',
  });
const jsonValue = z.union([
  z.string(),
  z.number(),
  z.boolean(),
  z.null(),
  fields,
  z.array(z.unknown()),
]);
export const changeSchema: z.ZodType<SemanticChange> = z.object({
  id,
  changeType: z.string(),
  subjectStableKey: z.string(),
  impactType: z.string(),
  before: jsonValue,
  after: jsonValue,
  beforeEvidenceIds: z.array(z.string()),
  afterEvidenceIds: z.array(z.string()),
  confidence,
});
export const inspectionSchema: z.ZodType<InspectionData> = z.union([
  fields,
  z.array(z.unknown()),
]);
export const settingsSchema: z.ZodType<Settings> = z.object({
  aiMode: z.string(),
  remoteAllowed: z.boolean(),
  authMode: z.string(),
  retentionDays: z.number().int().positive(),
  capabilities: z.union([z.array(z.string()), fields]),
});
