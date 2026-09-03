import type {
  CanvasLayout,
  CanvasProjection,
  SemanticNode,
} from '../../src/shared/api/types';

export const PROJECTION_LIMITS = [80, 120, 250, 500] as const;
export const EDGE_LIMIT = 2000;
const DOMAINS = [
  'Orders',
  'Payments',
  'Inventory',
  'Shipping',
  'Invoicing',
  'Returns',
  'Refunds',
  'Customers',
  'Catalog',
  'Pricing',
  'Promotions',
  'Subscriptions',
  'Procurement',
  'Suppliers',
  'Warehousing',
  'Tax',
  'Fraud checks',
  'Notifications',
  'Settlement',
  'Reconciliation',
];
const WORKFLOWS = [
  'Create',
  'Validate',
  'Approve',
  'Reserve',
  'Confirm',
  'Amend',
  'Cancel',
  'Retry',
  'Reconcile',
  'Expire',
  'Escalate',
  'Audit',
  'Schedule',
  'Release',
  'Notify',
  'Archive',
  'Restore',
  'Import',
  'Export',
];
const STEPS = [
  ['ENDPOINT', 'Accept request'],
  ['SECURITY_RULE', 'Authorize responsible team'],
  ['BUSINESS_RULE', 'Validate account status'],
  ['BUSINESS_RULE', 'Require mandatory fields'],
  ['BUSINESS_RULE', 'Validate effective date'],
  ['DB_READ', 'Load current record'],
  ['BUSINESS_RULE', 'Reject duplicate operation'],
  ['BUSINESS_RULE', 'Check minimum amount'],
  ['BUSINESS_RULE', 'Check maximum exposure'],
  ['BUSINESS_RULE', 'Check currency compatibility'],
  ['BUSINESS_RULE', 'Require active contract'],
  ['BUSINESS_RULE', 'Check available balance'],
  ['BUSINESS_RULE', 'Check workflow state'],
  ['BUSINESS_RULE', 'Apply approval threshold'],
  ['BUSINESS_RULE', 'Require independent approval'],
  ['DB_READ', 'Resolve regional policy'],
  ['BUSINESS_RULE', 'Apply retention policy'],
  ['BUSINESS_RULE', 'Compute permitted transition'],
  ['DB_WRITE', 'Persist business state'],
  ['DB_WRITE', 'Record audit entry'],
  ['EXTERNAL_CALL', 'Notify partner system'],
  ['BUSINESS_RULE', 'Classify retryable failure'],
  ['DB_WRITE', 'Schedule reconciliation'],
  ['EVENT', 'Publish business event'],
  ['BUSINESS_RULE', 'Return operation outcome'],
] as const;

export type ScalableGraph = ReturnType<typeof createScalableGraph>;

// Test transport only: twenty domains, each with 1 context + 5 entities + 19 * 26 workflow nodes.
export function createScalableGraph() {
  const nodes: SemanticNode[] = [];
  const edges: CanvasProjection['edges'] = [];
  const contexts: SemanticNode[] = [];
  const members = new Map<string, SemanticNode[]>();
  const children = new Map<string, string[]>();
  const addNode = (domain: string, kind: string, title: string) => {
    const index = nodes.length;
    const node: SemanticNode = {
      id: `00000000-0000-4000-8000-${String(index + 1).padStart(12, '0')}`,
      stableKey: `scale:${domain.toLowerCase().replaceAll(' ', '-')}:${index}`,
      kind,
      label: `${domain}: ${title}`,
      subtitle: `Operational policy for ${domain.toLowerCase()} with an explicit owner and revision`,
      confidence: 0.7 + (index % 29) / 100,
      supportLevel: 'SUPPORTED',
      evidenceCount: 0,
      reviewStatus: index % 17 === 0 ? 'NEEDS_REVIEW' : 'UNREVIEWED',
      badges: [],
      properties: {
        component: domain,
        sourceLayer:
          kind.startsWith('DB_') || kind === 'DATA_ENTITY'
            ? 'DATABASE'
            : 'BACKEND',
        hasTests: index % 5 !== 0,
        owner: `${domain} operations`,
      },
    };
    nodes.push(node);
    return node;
  };
  const connect = (
    source: SemanticNode,
    target: SemanticNode,
    kind: string,
  ) => {
    edges.push({
      id: `scale-edge-${edges.length}`,
      source: source.id,
      target: target.id,
      kind,
      label: kind.toLowerCase().replaceAll('_', ' '),
      confidence: Math.min(source.confidence, target.confidence),
      evidenceCount: 0,
    });
    const adjacent = children.get(source.id) ?? [];
    adjacent.push(target.id);
    children.set(source.id, adjacent);
  };
  for (const domain of DOMAINS) {
    const start = nodes.length;
    const context = addNode(domain, 'BUSINESS_CONTEXT', 'Business context');
    contexts.push(context);
    const entities = [
      'Record',
      'Contract',
      'Policy',
      'Audit entry',
      'Outbox event',
    ].map((name) => {
      const entity = addNode(domain, 'DATA_ENTITY', name);
      connect(context, entity, 'OWNS');
      return entity;
    });
    for (const action of WORKFLOWS) {
      const scenario = addNode(
        domain,
        'BUSINESS_SCENARIO',
        `${action} workflow`,
      );
      connect(context, scenario, 'CONTAINS');
      let previous: SemanticNode | undefined;
      for (const [kind, title] of STEPS) {
        const step = addNode(domain, kind, `${action}: ${title}`);
        connect(scenario, step, 'CONTAINS');
        if (previous) connect(previous, step, 'PRECEDES');
        if (kind === 'DB_READ' || kind === 'DB_WRITE')
          connect(
            step,
            entities[kind === 'DB_READ' ? 2 : 0]!,
            kind === 'DB_READ' ? 'READS' : 'WRITES',
          );
        previous = step;
      }
    }
    members.set(context.id, nodes.slice(start));
  }
  const byId = new Map(nodes.map((node) => [node.id, node]));
  return { nodes, edges, contexts, members, children, byId };
}

export function boundedProjection(
  graph: ScalableGraph,
  params: URLSearchParams,
): CanvasProjection {
  const detail = Number(params.get('levelOfDetail') ?? 2);
  const depth = Number(params.get('depth') ?? 2);
  if (
    !Number.isInteger(detail) ||
    detail < 0 ||
    detail > 3 ||
    !Number.isInteger(depth) ||
    depth < 1 ||
    depth > 5
  )
    throw new Error('The fixture only accepts the production LOD/depth bounds');
  const root = params.get('rootNodeId');
  const seeds = root ? [root] : graph.contexts.map((context) => context.id);
  if (seeds.some((id) => !graph.byId.has(id)))
    throw new Error('Unknown fixture root');
  const visited = new Set(seeds);
  let frontier = seeds;
  for (let level = 0; level < depth; level++) {
    const next: string[] = [];
    for (const id of frontier) {
      for (const child of graph.children.get(id) ?? []) {
        if (!visited.has(child)) {
          visited.add(child);
          next.push(child);
        }
      }
    }
    frontier = next;
  }
  const search = (params.get('search') ?? '').toLowerCase();
  const candidates = [...visited]
    .map((id) => graph.byId.get(id)!)
    .filter((node) => node.label.toLowerCase().includes(search));
  const nodes = candidates.slice(0, PROJECTION_LIMITS[detail]);
  const ids = new Set(nodes.map((node) => node.id));
  const edges = graph.edges.filter(
    (edge) => ids.has(edge.source) && ids.has(edge.target),
  );
  return {
    nodes,
    edges: edges.slice(0, EDGE_LIMIT),
    totalNodes: graph.nodes.length,
    truncated: candidates.length > nodes.length || edges.length > EDGE_LIMIT,
  };
}

// Deliberately exceeds server projection limits: isolated renderer stress, never a server-flow fixture.
export function rendererStressProjection(
  graph: ScalableGraph,
): CanvasProjection {
  const nodes = graph.nodes.slice(0, 2000);
  const ids = new Set(nodes.map((node) => node.id));
  return {
    nodes,
    edges: graph.edges.filter(
      (edge) => ids.has(edge.source) && ids.has(edge.target),
    ),
    totalNodes: nodes.length,
    truncated: false,
  };
}

export function overviewLayout(nodes: SemanticNode[]): CanvasLayout {
  return {
    positions: Object.fromEntries(
      nodes.map((node, index) => [
        node.stableKey,
        { x: (index % 50) * 284, y: Math.floor(index / 50) * 170 },
      ]),
    ),
    viewport: { x: 50, y: 35, zoom: 0.1 },
  };
}
