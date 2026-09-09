import { z } from 'zod';
import { request } from '../../shared/api/client';
import type { components } from '../../shared/api/generated';

const attributes = z.record(z.unknown());
const count = z.number().int().nonnegative();
const nanos = z.string().regex(/^\d{1,20}$/);
export const runtimeSchema = z.object({
  analysisRunId: z.string().uuid(),
  spans: z
    .array(
      z.object({
        id: z.string().uuid(),
        traceId: z.string(),
        spanId: z.string(),
        parentSpanId: z.string().nullable(),
        name: z.string(),
        kind: z.number().int().min(0).max(5),
        startNanos: nanos,
        endNanos: nanos,
        attributes,
        resourceAttributes: attributes,
        scope: attributes,
        status: attributes,
        events: z.array(attributes),
        links: z.array(attributes),
        matchedNodeId: z.string().uuid().nullable(),
        matchStatus: z.enum([
          'MATCHED_STABLE_KEY',
          'MATCHED_SYMBOL',
          'UNRESOLVED',
          'AMBIGUOUS',
        ]),
        createdAt: z.string(),
      }),
    )
    .max(500),
  summary: z.object({
    totalSpans: count,
    traceCount: count,
    matchedSpans: count,
    unresolvedSpans: count,
    observedNodes: count,
    notObservedNodes: count,
    coverageScope: z.literal('UPLOADED_SPANS_ONLY'),
    wholeApplicationCoverageKnown: z.literal(false),
    notObservedMeaning: z.literal('NOT_OBSERVED_IN_UPLOADED_TRACES'),
  }),
  observedPaths: z
    .array(
      z.object({
        sourceNodeId: z.string().uuid(),
        targetNodeId: z.string().uuid(),
      }),
    )
    .max(500),
  pathsTruncated: z.boolean(),
  offset: count,
  limit: z.number().int().min(1).max(500),
  hasMore: z.boolean(),
}) satisfies z.ZodType<components['schemas']['RuntimeSnapshot']>;
export const ingestSchema = z.object({
  status: z.literal('INGESTED'),
  acceptedSpans: count,
  duplicateSpans: count,
  matchedSpans: count,
  unresolvedSpans: count,
  coverageScope: z.literal('UPLOADED_SPANS_ONLY'),
}) satisfies z.ZodType<components['schemas']['RuntimeIngestion']>;

export const MAX_TRACE_BYTES = 1_048_576;
export const PAGE_SIZE = 50;
export const runtimeApi = {
  get: (id: string, offset: number, signal?: AbortSignal) =>
    request(
      `/analysis-runs/${encodeURIComponent(id)}/runtime?limit=${PAGE_SIZE}&offset=${offset}`,
      runtimeSchema,
      { signal },
    ),
  ingest: (id: string, body: string) =>
    request(
      `/analysis-runs/${encodeURIComponent(id)}/runtime/traces`,
      ingestSchema,
      { method: 'POST', body },
    ),
};

// Subtract before conversion: Unix nanosecond timestamps exceed Number precision.
export function durationMilliseconds(start: string, end: string): string {
  const delta = BigInt(end) - BigInt(start);
  if (delta < 0n) return '\u2014';
  return `${delta / 1_000_000n}.${(delta % 1_000_000n).toString().padStart(6, '0')}`;
}
