import type {
  AnalysisRun,
  CanvasProjection,
  Evidence,
  Project,
  SemanticNode,
} from '../shared/api/types';

// Explicit test fixtures. Production source never imports this module.
export const project: Project = {
  id: '10000000-0000-4000-8000-000000000001',
  name: 'Order platform',
  description: 'Order processing',
  repositoryPath: 'fixtures/spring-order-service',
  createdAt: '2026-09-02T10:00:00Z',
};
export const run: AnalysisRun = {
  id: '20000000-0000-4000-8000-000000000001',
  projectId: project.id,
  status: 'SUCCEEDED',
  revision: 'b7d641c9f050',
  createdAt: '2026-09-02T10:01:00Z',
  finishedAt: '2026-09-02T10:02:00Z',
  progress: 100,
  currentStage: 'canvas.ready',
};
export const rule: SemanticNode = {
  id: '30000000-0000-4000-8000-000000000002',
  stableKey: 'rule:minimum-order',
  kind: 'BUSINESS_RULE',
  label: 'Minimum order amount',
  subtitle: 'An order must total at least 500.',
  confidence: 0.98,
  supportLevel: 'PROVEN',
  evidenceCount: 1,
  reviewStatus: 'UNREVIEWED',
  badges: ['BACKEND'],
  properties: {
    sourceLayer: 'BACKEND',
    threshold: 500,
    operator: '>=',
    hasTests: false,
  },
};
export const projection: CanvasProjection = {
  nodes: [
    {
      ...rule,
      id: '30000000-0000-4000-8000-000000000001',
      stableKey: 'endpoint:POST:/orders',
      kind: 'ENDPOINT',
      label: 'Create order',
      subtitle: 'POST /api/orders',
    },
    rule,
    {
      ...rule,
      id: '30000000-0000-4000-8000-000000000003',
      stableKey: 'db:orders',
      kind: 'DATABASE_WRITE',
      label: 'Persist order',
      subtitle: 'orders',
      badges: ['DATABASE'],
      properties: { sourceLayer: 'DATABASE' },
    },
  ],
  edges: [
    {
      id: 'edge-1',
      source: '30000000-0000-4000-8000-000000000001',
      target: rule.id,
      kind: 'GUARDED_BY',
      label: 'requires',
      confidence: 1,
      evidenceCount: 1,
    },
    {
      id: 'edge-2',
      source: rule.id,
      target: '30000000-0000-4000-8000-000000000003',
      kind: 'PRECEDES',
      label: 'then',
      confidence: 1,
      evidenceCount: 1,
    },
  ],
  truncated: false,
  totalNodes: 3,
};
export const evidence: Evidence = {
  id: 'evidence-1',
  filePath: 'src/main/java/OrderService.java',
  startLine: 84,
  endLine: 86,
  snippet: 'if (total < 500) {\n  throw new OrderRejectedException();\n}',
  snippetHash: 'sha256:fixture',
  analyzerId: 'java-spring',
  analyzerVersion: '1.0.0',
  imageDigest: 'sha256:analyzer-fixture',
  origin: 'STATIC',
  verified: true,
  sourceAvailable: true,
};
