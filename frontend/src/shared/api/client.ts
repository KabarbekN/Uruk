import { z } from 'zod';
import { authHeaders } from '../lib/auth';
import {
  changeSchema,
  detailSchema,
  evidenceSchema,
  inspectionSchema,
  layoutSchema,
  nodeSchema,
  projectSchema,
  projectionSchema,
  runSchema,
  settingsSchema,
  hardwareProfileSchema,
  testAccessSchema,
  oauthStatusSchema,
  resolvedRepositorySchema,
  remoteRepositorySummarySchema,
} from './schemas';
import type {
  AnalysisInput,
  CanvasLayout,
  CanvasParams,
  CanvasView,
  Problem,
  ProjectInput,
  RepositoryInput,
  ReviewInput,
  SettingsInput,
} from './types';

export class ApiError extends Error {
  constructor(
    public status: number,
    public problem: Problem = {},
    public reason: 'http' | 'network' | 'contract' = 'http',
  ) {
    super(problem.detail || problem.title || `HTTP ${status}`);
    this.name = 'ApiError';
  }
}

export function queryString(
  params: Record<string, string | number | boolean | undefined>,
): string {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params))
    if (value !== undefined && value !== '') query.set(key, String(value));
  return query.size ? `?${query.toString()}` : '';
}

export async function request<T>(
  path: string,
  schema: z.ZodType<T>,
  init: RequestInit = {},
): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`/api/v1${path}`, {
      credentials: 'same-origin',
      ...init,
      headers: {
        Accept: 'application/json',
        ...authHeaders(),
        ...(init.body ? { 'Content-Type': 'application/json' } : {}),
        ...init.headers,
      },
    });
  } catch (error) {
    if (error instanceof Error && error.name === 'AbortError') throw error;
    throw new ApiError(0, {}, 'network');
  }
  if (!response.ok) {
    const data: unknown = await response.json().catch(() => null);
    const parsed = z
      .object({
        title: z.string().optional(),
        detail: z.string().optional(),
        errorCode: z.string().optional(),
        correlationId: z.string().optional(),
      })
      .safeParse(data);
    throw new ApiError(response.status, parsed.success ? parsed.data : {});
  }
  const text = await response.text();
  let data: unknown;
  try {
    data = text ? JSON.parse(text) : undefined;
  } catch {
    throw new ApiError(response.status, {}, 'contract');
  }
  const parsed = schema.safeParse(data);
  if (!parsed.success)
    throw new ApiError(
      response.status,
      {
        detail: parsed.error.issues
          .map((issue) => `${issue.path.join('.')}: ${issue.message}`)
          .slice(0, 3)
          .join('; '),
      },
      'contract',
    );
  return parsed.data;
}

const encoded = encodeURIComponent;
const json = (method: string, body?: unknown): RequestInit => ({
  method,
  ...(body === undefined ? {} : { body: JSON.stringify(body) }),
});
const acknowledgement = z.unknown();

export const api = {
  projects: (signal?: AbortSignal) =>
    request('/projects', z.array(projectSchema), { signal }),
  createProject: (body: ProjectInput) =>
    request('/projects', projectSchema, json('POST', body)),
  updateProject: (id: string, body: ProjectInput) =>
    request(`/projects/${encoded(id)}`, projectSchema, json('PATCH', body)),
  deleteProject: (id: string) =>
    request(`/projects/${encoded(id)}`, acknowledgement, json('DELETE')),
  addRepository: (id: string, body: RepositoryInput) =>
    request(
      `/projects/${encoded(id)}/repositories`,
      z.object({ id: z.string(), url: z.string(), branch: z.string() }),
      json('POST', body),
    ),
  runs: (projectId: string, signal?: AbortSignal) =>
    request(
      `/projects/${encoded(projectId)}/analysis-runs`,
      z.array(runSchema),
      { signal },
    ),
  startRun: (projectId: string, body: AnalysisInput) =>
    request(
      `/projects/${encoded(projectId)}/analysis-runs`,
      runSchema,
      json('POST', body),
    ),
  run: (id: string, signal?: AbortSignal) =>
    request(`/analysis-runs/${encoded(id)}`, runSchema, { signal }),
  cancelRun: (id: string) =>
    request(
      `/analysis-runs/${encoded(id)}/cancel`,
      acknowledgement,
      json('POST'),
    ),
  canvas: (id: string, params: CanvasParams, signal?: AbortSignal) =>
    request(
      `/analysis-runs/${encoded(id)}/canvas${queryString(params)}`,
      projectionSchema,
      { signal },
    ),
  layout: async (id: string, view: CanvasView, signal?: AbortSignal) => {
    try {
      return await request(
        `/analysis-runs/${encoded(id)}/layout${queryString({ view })}`,
        layoutSchema,
        { signal },
      );
    } catch (error) {
      if (error instanceof ApiError && error.status === 404) return null;
      throw error;
    }
  },
  saveLayout: (id: string, view: CanvasView, body: CanvasLayout) =>
    request(
      `/analysis-runs/${encoded(id)}/layout${queryString({ view })}`,
      acknowledgement,
      json('PUT', body),
    ),
  node: (id: string, signal?: AbortSignal) =>
    request(`/semantic/nodes/${encoded(id)}`, detailSchema, { signal }),
  evidence: (kind: 'nodes' | 'edges', id: string, signal?: AbortSignal) =>
    request(
      `/semantic/${kind}/${encoded(id)}/evidence`,
      z.array(evidenceSchema),
      { signal },
    ),
  review: (id: string, body: ReviewInput) =>
    request(
      `/semantic/nodes/${encoded(id)}/reviews`,
      acknowledgement,
      json('POST', body),
    ),
  reviewQueue: (id: string, signal?: AbortSignal) =>
    request(`/projects/${encoded(id)}/review-queue`, z.array(nodeSchema), {
      signal,
    }),
  diff: (
    projectId: string,
    fromAnalysisRun: string,
    toAnalysisRun: string,
    signal?: AbortSignal,
  ) =>
    request(
      `/projects/${encoded(projectId)}/semantic-diff${queryString({ fromAnalysisRun, toAnalysisRun })}`,
      z.array(changeSchema),
      { signal },
    ),
  inspect: (id: string, resource: InspectionResource, signal?: AbortSignal) =>
    request(`/analysis-runs/${encoded(id)}/${resource}`, inspectionSchema, {
      signal,
    }),
  settings: (signal?: AbortSignal) =>
    request('/settings', settingsSchema, { signal }),
  saveSettings: (body: SettingsInput) =>
    request('/settings', acknowledgement, json('PUT', body)),
  testRepositoryAccess: (data: {
    url: string;
    token?: string;
    personalAccessToken?: string;
    credentialsReference?: string;
  }) =>
    request(
      '/repositories/test-access',
      testAccessSchema,
      json('POST', {
        url: data.url,
        token: data.token ?? data.personalAccessToken,
        credentialsReference: data.credentialsReference,
      }),
    ),
  getSystemResources: (signal?: AbortSignal) =>
    request('/system/resources', hardwareProfileSchema, { signal }),
  selectAiModel: (data: {
    model: string;
    providerType: string;
    apiKey?: string;
    baseUrl?: string;
  }) => request('/system/ai/select', acknowledgement, json('POST', data)),
  getOAuthStatus: (signal?: AbortSignal) =>
    request('/auth/oauth/status', oauthStatusSchema, { signal }),
  configureOAuth: (data: {
    provider: string;
    clientId: string;
    clientSecret: string;
  }) => request('/auth/oauth/config', acknowledgement, json('POST', data)),
  startGithubDeviceFlow: () =>
    request(
      '/auth/oauth/device/github/start',
      z.object({
        deviceCode: z.string(),
        userCode: z.string(),
        verificationUri: z.string(),
        expiresIn: z.number(),
        interval: z.number(),
      }),
      json('POST', {}),
    ),
  pollGithubDeviceFlow: (deviceCode: string) =>
    request(
      '/auth/oauth/device/github/poll',
      z.object({
        status: z.string(),
        token: z.string().nullable().optional(),
        username: z.string().nullable().optional(),
        error: z.string().nullable().optional(),
        interval: z.number().nullable().optional(),
      }),
      json('POST', { deviceCode }),
    ),
  resolveRepository: (data: { url: string; token?: string }) =>
    request(
      '/git/repositories/resolve',
      resolvedRepositorySchema,
      json('POST', data),
    ),
  fetchRemoteRepositories: (params: {
    provider?: string;
    token?: string;
    page?: number;
    perPage?: number;
  }) =>
    request(
      `/git/repositories${queryString(params)}`,
      z.array(remoteRepositorySummarySchema),
    ),
  getControllers: (runId: string, signal?: AbortSignal) =>
    request(
      `/analysis-runs/${encoded(runId)}/controllers`,
      z.array(
        z.object({
          controllerKey: z.string(),
          controllerName: z.string(),
          packageName: z.string(),
          filePath: z.string(),
          endpointCount: z.number(),
          endpoints: z.array(
            z.object({
              id: z.string(),
              label: z.string(),
              method: z.string().nullable().optional(),
              path: z.string().nullable().optional(),
              methodName: z.string().nullable().optional(),
              hasAi: z.boolean().nullable().optional(),
              aiTitle: z.string().nullable().optional(),
              aiDescription: z.string().nullable().optional(),
              aiResult: z
                .object({
                  title: z.string().nullable().optional(),
                  businessPurpose: z.string().nullable().optional(),
                  actors: z.array(z.string()).nullable().optional(),
                  businessSteps: z.array(z.string()).nullable().optional(),
                  businessRules: z.array(z.string()).nullable().optional(),
                  errorScenarios: z.array(z.string()).nullable().optional(),
                  category: z.string().nullable().optional(),
                })
                .passthrough()
                .nullable()
                .optional(),
              scenarioId: z.string().nullable().optional(),
              stepCount: z.number().int().nonnegative().nullable().optional(),
              sideEffectCount: z.number().int().nonnegative().nullable().optional(),
              unresolvedCount: z.number().int().nonnegative().nullable().optional(),
              pathOrderKnown: z.boolean().nullable().optional(),
              storyTruncated: z.boolean().nullable().optional(),
              storyStatus: z
                .enum(['DISCOVERED', 'STATIC_READY', 'ANALYZING', 'READY'])
                .nullable()
                .optional(),
            }),
          ),
        }),
      ),
      { signal },
    ),
  enrichNode: (nodeId: string, signal?: AbortSignal) =>
    request(
      `/semantic/nodes/${encoded(nodeId)}/enrich`,
      z.record(z.unknown()),
      { ...json('POST', {}), signal },
    ),
  enrichAll: (runId: string, signal?: AbortSignal) =>
    request(
      `/analysis-runs/${encoded(runId)}/enrich-all`,
      z.record(z.unknown()),
      { ...json('POST', {}), signal },
    ),
  enrichController: (
    runId: string,
    controllerKey: string,
    signal?: AbortSignal,
  ) =>
    request(
      `/analysis-runs/${encoded(runId)}/controllers/${encodeURIComponent(controllerKey)}/enrich`,
      z.record(z.unknown()),
      { ...json('POST', {}), signal },
    ),
  cancelEnrichment: (runId: string, signal?: AbortSignal) =>
    request(
      `/analysis-runs/${encoded(runId)}/cancel-enrichment`,
      z.record(z.unknown()),
      { ...json('POST', {}), signal },
    ),
  enrichPipeline: (runId: string, endpointId: string, signal?: AbortSignal) =>
    request(
      `/analysis-runs/${encoded(runId)}/scenarios/${encoded(endpointId)}/enrich-pipeline`,
      z.record(z.unknown()),
      { ...json('POST', {}), signal },
    ),
  getEnrichmentProgress: (runId: string, signal?: AbortSignal) =>
    request(
      `/analysis-runs/${encoded(runId)}/enrichment-progress`,
      z.object({
        total: z.number(),
        ready: z.number(),
        processing: z.number(),
        failed: z.number(),
        percentage: z.number(),
        activeModel: z.string().optional(),
      }),
      { signal },
    ),
  semanticSearch: (runId: string, query: string, signal?: AbortSignal) =>
    request(
      `/analysis-runs/${encoded(runId)}/semantic-search`,
      z.array(
        z.object({
          endpointId: z.string(),
          score: z.number(),
          reason: z.string(),
          allReasons: z.array(z.string()).optional(),
          endpoint: z.record(z.unknown()),
        }),
      ),
      { ...json('POST', { query }), signal },
    ),
};

export type InspectionResource =
  | 'coverage'
  | 'diagnostics'
  | 'analyzer-plan'
  | 'quarantined-facts'
  | 'unresolved-symbols';
