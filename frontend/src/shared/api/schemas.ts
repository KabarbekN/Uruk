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
  HardwareProfile,
  TestAccessResponse,
  ResolvedRepository,
  RemoteRepositorySummary,
  LlmEnrichment,
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
  aiLabel: z.string().optional(),
  aiSubtitle: z.string().optional(),
  originalLabel: z.string().optional(),
  originalSubtitle: z.string().optional(),
  confidence,
  supportLevel: z.string(),
  evidenceCount: z.number().int().nonnegative(),
  reviewStatus: z.string(),
  badges: z.array(z.string()),
  properties: fields,
});
export const nodeSchema: z.ZodType<SemanticNode> = nodeObject;
const scenarioMemberSchema = z.object({
  depth: z.number().int().nonnegative(),
  role: z.string(),
  id: z.string(),
  kind: z.string(),
  label: z.string(),
  subtitle: z.string().nullable().optional(),
  stableKey: z.string(),
  properties: fields.optional(),
});
export const enrichmentSchema: z.ZodType<LlmEnrichment> = z.object({
  id: z.string(),
  title: z.string(),
  description: z.string(),
  category: z.string(),
  claims: z
    .array(
      z.object({
        factId: z.string(),
        field: z.string(),
        value: z.string(),
      }),
    )
    .optional(),
  ambiguities: z.array(z.string()).optional(),
  result: z.record(z.unknown()).optional(),
  trustStatus: z.string().optional(),
  createdAt: z.string().optional(),
});
export const detailSchema: z.ZodType<SemanticNodeDetail> = nodeObject.extend({
  facts: z.array(z.unknown()).optional(),
  scenarios: z.array(z.unknown()).optional(),
  scenarioMembers: z.array(scenarioMemberSchema).optional(),
  assertions: z.array(z.unknown()).optional(),
  enrichments: z.array(z.unknown()).optional(),
  enrichment: enrichmentSchema.nullable().optional(),
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
  pinnedStableKeys: z.array(z.string().max(2048)).max(2000).optional(),
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
    enclosingDeclaration: z.string().optional(),
    enclosingSnippet: z.string().nullable().optional(),
    enclosingStartLine: z.number().int().positive().optional(),
    enclosingEndLine: z.number().int().positive().optional(),
    highlightStartLine: z.number().int().positive().optional(),
    highlightEndLine: z.number().int().positive().optional(),
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

export const testAccessSchema: z.ZodType<TestAccessResponse> = z.object({
  connected: z.boolean(),
  branches: z.array(z.string()),
  defaultBranch: z.string(),
});

export const hardwareProfileSchema: z.ZodType<HardwareProfile> = z.object({
  osName: z.string(),
  osArch: z.string(),
  cpuCores: z.number(),
  totalRamBytes: z.number(),
  freeRamBytes: z.number(),
  gpuName: z.string().nullable(),
  gpuVramBytes: z.number(),
  ollamaAvailable: z.boolean(),
  ollamaUrl: z.string(),
  activeModel: z.string().optional(),
  isLocalActive: z.boolean().optional(),
  installedModels: z.array(
    z.object({
      name: z.string(),
      sizeBytes: z.number(),
      modifiedAt: z.string(),
    }),
  ),
  catalog: z.array(
    z.object({
      id: z.string(),
      name: z.string(),
      providerType: z.string(),
      parameterSize: z.string(),
      requiredRamBytes: z.number(),
      description: z.string(),
      category: z.string().optional(),
      bestFor: z.string().optional(),
      hardwareReqs: z.string().optional(),
      isRecommended: z.boolean(),
      isInstalled: z.boolean(),
    }),
  ),
  recommendedModelId: z.string(),
  recommendationReason: z.string(),
});

export const oauthStatusSchema = z.object({
  githubConfigured: z.boolean(),
  githubClientId: z.string(),
  gitlabConfigured: z.boolean(),
  gitlabClientId: z.string(),
});

export const resolvedRepositorySchema: z.ZodType<ResolvedRepository> = z.object({
  provider: z.string(),
  owner: z.string(),
  name: z.string(),
  fullName: z.string(),
  normalizedUrl: z.string(),
  visibility: z.string(),
  hasAccess: z.boolean(),
  defaultBranch: z.string(),
  branches: z.array(z.string()),
  errorMessage: z.string().nullable().optional(),
});

export const remoteRepositorySummarySchema: z.ZodType<RemoteRepositorySummary> = z.object({
  id: z.string(),
  name: z.string(),
  fullName: z.string(),
  url: z.string(),
  defaultBranch: z.string(),
  isPrivate: z.boolean(),
  description: z.string(),
  language: z.string().optional(),
});
