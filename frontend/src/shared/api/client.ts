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
};

export type InspectionResource =
  | 'coverage'
  | 'diagnostics'
  | 'analyzer-plan'
  | 'quarantined-facts'
  | 'unresolved-symbols';
