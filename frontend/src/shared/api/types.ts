import type { components, operations } from './generated';

export type Project = components['schemas']['Project'];
export type ProjectInput = components['schemas']['ProjectInput'];
export type RepositoryInput = components['schemas']['RepositoryInput'] & {
  personalAccessToken?: string;
};
export type AnalysisRun = components['schemas']['AnalysisRun'];
export type AnalysisInput = components['schemas']['AnalysisInput'];
export type AnalysisEvent = components['schemas']['AnalysisEvent'];
export type SemanticNode = components['schemas']['SemanticNode'] & {
  aiLabel?: string;
  aiSubtitle?: string;
  originalLabel?: string;
  originalSubtitle?: string;
};
export interface ScenarioMember {
  depth: number;
  role: string;
  id: string;
  kind: string;
  label: string;
  subtitle?: string | null;
  stableKey: string;
  properties?: Record<string, unknown>;
}

export interface LlmEnrichment {
  id: string;
  title: string;
  description: string;
  category: string;
  businessPurpose?: string;
  actors?: string[];
  businessSteps?: string[];
  businessRules?: string[];
  errorScenarios?: string[];
  claims?: Array<{ factId: string; field: string; value: string }>;
  ambiguities?: string[];
  result?: {
    title?: string;
    businessPurpose?: string;
    actors?: string[];
    businessSteps?: string[];
    businessRules?: string[];
    errorScenarios?: string[];
    category?: string;
    [key: string]: unknown;
  };
  trustStatus?: string;
  createdAt?: string;
}

export interface ControllerEndpoint {
  id: string;
  label: string;
  method?: string | null;
  path?: string | null;
  methodName?: string | null;
  hasAi?: boolean | null;
  aiTitle?: string | null;
  aiDescription?: string | null;
  aiResult?: {
    title?: string | null;
    businessPurpose?: string | null;
    actors?: string[] | null;
    businessSteps?: string[] | null;
    businessRules?: string[] | null;
    errorScenarios?: string[] | null;
    category?: string | null;
  } | null;
  scenarioId?: string | null;
  stepCount?: number | null;
  sideEffectCount?: number | null;
  unresolvedCount?: number | null;
  pathOrderKnown?: boolean | null;
  storyTruncated?: boolean | null;
  storyStatus?: 'DISCOVERED' | 'STATIC_READY' | 'ANALYZING' | 'READY' | null;
}

export interface ControllerGroup {
  controllerKey: string;
  controllerName: string;
  packageName: string;
  filePath: string;
  endpointCount: number;
  endpoints: ControllerEndpoint[];
}

export interface EnrichmentProgress {
  total: number;
  ready: number;
  processing: number;
  failed: number;
  percentage: number;
  activeModel?: string;
}

export interface SemanticSearchResult {
  endpointId: string;
  score: number;
  reason: string;
  allReasons?: string[];
  endpoint: ControllerEndpoint;
}

export type SemanticNodeDetail = components['schemas']['SemanticNodeDetail'] & {
  scenarioMembers?: ScenarioMember[];
  enrichment?: LlmEnrichment | null;
};
export type SemanticEdge = components['schemas']['SemanticEdge'];
export type CanvasProjection = Omit<
  components['schemas']['CanvasProjection'],
  'nodes'
> & {
  nodes: SemanticNode[];
};
export type CanvasView = components['schemas']['CanvasView'];
export type CanvasLayout = components['schemas']['CanvasLayout'];
export type CanvasParams = NonNullable<
  operations['getCanvas']['parameters']['query']
>;
export type Evidence = components['schemas']['Evidence'] & {
  enclosingDeclaration?: string;
  enclosingSnippet?: string | null;
  enclosingStartLine?: number;
  enclosingEndLine?: number;
  highlightStartLine?: number;
  highlightEndLine?: number;
};
export type ReviewInput = components['schemas']['ReviewInput'];
export type SemanticChange = components['schemas']['SemanticChange'];
export type Settings = components['schemas']['Settings'];
export type SettingsInput = components['schemas']['SettingsInput'];
export type InspectionData = components['schemas']['InspectionData'];
export type Problem = components['schemas']['Problem'];

export interface InstalledModel {
  name: string;
  sizeBytes: number;
  modifiedAt: string;
}

export interface CatalogModel {
  id: string;
  name: string;
  providerType: string;
  parameterSize: string;
  requiredRamBytes: number;
  description: string;
  category?: string;
  bestFor?: string;
  hardwareReqs?: string;
  isRecommended: boolean;
  isInstalled: boolean;
}

export interface HardwareProfile {
  osName: string;
  osArch: string;
  cpuCores: number;
  totalRamBytes: number;
  freeRamBytes: number;
  gpuName: string | null;
  gpuVramBytes: number;
  ollamaAvailable: boolean;
  ollamaUrl: string;
  activeModel?: string;
  isLocalActive?: boolean;
  installedModels: InstalledModel[];
  catalog: CatalogModel[];
  recommendedModelId: string;
  recommendationReason: string;
}

export interface TestAccessResponse {
  connected: boolean;
  branches: string[];
  defaultBranch: string;
}

export interface PullProgressEvent {
  status: string;
  total: number;
  completed: number;
  percent: number;
  error?: string;
}

export interface OAuthStatusResponse {
  githubConfigured: boolean;
  githubClientId: string;
  gitlabConfigured: boolean;
  gitlabClientId: string;
}

export interface ResolvedRepository {
  provider: string;
  owner: string;
  name: string;
  fullName: string;
  normalizedUrl: string;
  visibility: string;
  hasAccess: boolean;
  defaultBranch: string;
  branches: string[];
  errorMessage?: string | null;
}

export interface RemoteRepositorySummary {
  id: string;
  name: string;
  fullName: string;
  url: string;
  defaultBranch: string;
  isPrivate: boolean;
  description: string;
  language?: string;
}
