import type { SemanticNode } from '../../shared/api/types';
import type { Filters } from './store';

export function sourceLayer(node: SemanticNode): string {
  const explicit =
    node.properties.sourceLayer ??
    node.properties.layer ??
    node.properties.ownership;
  if (typeof explicit === 'string') return explicit;
  return (
    node.badges.find((badge) =>
      ['FRONTEND', 'BACKEND', 'DATABASE', 'EXTERNAL_SYSTEM'].includes(badge),
    ) ?? 'UNASSIGNED'
  );
}
export function property(
  node: SemanticNode,
  key: 'analyzer' | 'component' | 'framework',
): string {
  const value = node.properties[key] ?? node.properties[`${key}Id`];
  return typeof value === 'string' ? value : '';
}
export function matchesFilters(node: SemanticNode, filters: Filters): boolean {
  const badges = new Set(node.badges);
  if (
    filters.onlyUnreviewed &&
    !['UNREVIEWED', 'NEEDS_REVIEW', 'STALE'].includes(node.reviewStatus)
  )
    return false;
  if (filters.lowConfidence && node.confidence >= 0.7) return false;
  if (
    filters.unresolved &&
    !node.kind.includes('UNRESOLVED') &&
    !badges.has('UNRESOLVED') &&
    node.properties.resolved !== false
  )
    return false;
  if (
    filters.withoutTests &&
    (!/RULE|CONSTRAINT/.test(node.kind) ||
      badges.has('TESTED') ||
      node.properties.hasTests === true ||
      Number(node.properties.testCount ?? 0) > 0 ||
      !(
        node.properties.hasTests === false ||
        node.properties.testCount === 0 ||
        badges.has('UNTESTED') ||
        badges.has('WITHOUT_TESTS')
      ))
  )
    return false;
  if (
    filters.authRules &&
    !/AUTHORIZATION|AUTHENTICATION|OWNERSHIP_CHECK|TENANT_CHECK/.test(node.kind)
  )
    return false;
  if (
    filters.businessRules &&
    !/BUSINESS_RULE|BUSINESS_CONSTRAINT/.test(node.kind)
  )
    return false;
  if (
    filters.dbWrites &&
    !/DATA_WRITE|DATABASE_WRITE|PERSISTENCE/.test(node.kind)
  )
    return false;
  if (
    filters.externalCalls &&
    !/EXTERNAL_CALL|EXTERNAL_SYSTEM|SIDE_EFFECT/.test(node.kind)
  )
    return false;
  if (
    filters.llmEnriched &&
    !badges.has('LLM_ENRICHED') &&
    node.properties.llmEnriched !== true
  )
    return false;
  if (filters.sourceLayer && sourceLayer(node) !== filters.sourceLayer)
    return false;
  for (const key of ['analyzer', 'component', 'framework'] as const)
    if (filters[key] && property(node, key) !== filters[key]) return false;
  return true;
}
