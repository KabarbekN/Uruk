import { beforeEach, describe, expect, it, vi } from 'vitest';
import { api, ApiError, queryString } from '../shared/api/client';
import {
  changeSchema,
  evidenceSchema,
  projectionSchema,
} from '../shared/api/schemas';
import { useSessionAuth } from '../shared/lib/auth';
import { SseParser, type StreamPacket } from '../shared/lib/sse';
import {
  matchesFilters,
  sourceLayer,
} from '../features/semantic-canvas/filters';
import { evidence, projection, rule } from './fixtures';

beforeEach(() => useSessionAuth.getState().setToken(''));
describe('API boundary', () => {
  it('rejects malformed success responses instead of substituting an empty project list', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response(JSON.stringify({ projects: [] }))),
    );
    await expect(api.projects()).rejects.toMatchObject({ reason: 'contract' });
  });
  it('preserves structured errors and correlation IDs', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            detail: 'Project is archived',
            correlationId: 'request-123',
          }),
          { status: 403 },
        ),
      ),
    );
    await expect(api.projects()).rejects.toMatchObject({
      status: 403,
      problem: { detail: 'Project is archived', correlationId: 'request-123' },
    });
  });
  it('reports a failed connection as a network error', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockRejectedValue(new TypeError('fetch failed')),
    );
    await expect(api.projects()).rejects.toBeInstanceOf(ApiError);
    await expect(api.projects()).rejects.toMatchObject({ reason: 'network' });
  });
  it('keeps credentials in memory and only sends them when configured', async () => {
    const fetchMock = vi
      .fn()
      .mockImplementation(() => Promise.resolve(new Response('[]')));
    vi.stubGlobal('fetch', fetchMock);
    await api.projects();
    expect(fetchMock.mock.calls[0]?.[1].headers).not.toHaveProperty(
      'Authorization',
    );
    useSessionAuth.getState().setToken('session-test-token');
    await api.projects();
    expect(fetchMock.mock.calls[1]?.[1].headers.Authorization).toBe(
      'Bearer session-test-token',
    );
    expect(Object.values(localStorage)).not.toContain('session-test-token');
  });
  it('accepts empty mutation responses and encodes IDs and search parameters', async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', fetchMock);
    await expect(api.cancelRun('run/one')).resolves.toBeUndefined();
    expect(fetchMock.mock.calls[0]?.[0]).toBe(
      '/api/v1/analysis-runs/run%2Fone/cancel',
    );
    expect(
      queryString({ search: 'order & total', depth: 2, rootNodeId: undefined }),
    ).toBe('?search=order+%26+total&depth=2');
  });
  it('treats only missing layout as an unsaved layout; other failures propagate', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response(null, { status: 404 })),
    );
    await expect(api.layout('run', 'BUSINESS')).resolves.toBeNull();
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(new Response(null, { status: 500 })),
    );
    await expect(api.layout('run', 'BUSINESS')).rejects.toMatchObject({
      status: 500,
    });
  });
  it('validates evidence ranges and preserves explicit source expiry', () => {
    expect(
      evidenceSchema.parse({
        ...evidence,
        sourceAvailable: false,
        snippet: null,
      }).sourceAvailable,
    ).toBe(false);
    expect(evidenceSchema.safeParse({ ...evidence, endLine: 1 }).success).toBe(
      false,
    );
    expect(
      projectionSchema.safeParse({
        ...projection,
        nodes: [{ ...rule, confidence: 1.5 }],
      }).success,
    ).toBe(false);
    expect(
      changeSchema.safeParse({
        id: 'c',
        changeType: 'MODIFIED',
        subjectStableKey: 'r',
        impactType: 'THRESHOLD',
        beforeEvidenceIds: [],
        afterEvidenceIds: [],
        confidence: 1,
      }).success,
    ).toBe(false);
  });
});

describe('SSE transport framing', () => {
  it('handles named and unnamed messages, fragmented CRLF and multi-line data', () => {
    const packets: StreamPacket[] = [];
    const parser = new SseParser((packet) => packets.push(packet));
    parser.push(': keepalive\r\n\r\nid: 7\r\nevent: analyzer.progress\r');
    parser.push('\ndata: {"progress":\r\ndata: 50}\r\n\r\n');
    parser.push('id: 8\ndata: {"progress":100}\n\n');
    expect(packets).toEqual([
      { event: 'analyzer.progress', id: '7', data: '{"progress":\n50}' },
      { event: 'message', id: '8', data: '{"progress":100}' },
    ]);
  });
  it('does not dispatch incomplete frames or comment-only heartbeats', () => {
    const sink = vi.fn();
    const parser = new SseParser(sink);
    parser.push(': keepalive\n\ndata: {"progress":30}\n');
    expect(sink).not.toHaveBeenCalled();
    parser.push('\n');
    expect(sink).toHaveBeenCalledOnce();
  });
});

describe('projection filters', () => {
  it('preserves uncertainty, source layer, and review state without inferring ownership', () => {
    expect(
      matchesFilters({ ...rule, confidence: 0.5 }, { lowConfidence: true }),
    ).toBe(true);
    expect(
      matchesFilters(
        { ...rule, reviewStatus: 'CONFIRMED' },
        { onlyUnreviewed: true },
      ),
    ).toBe(false);
    expect(
      matchesFilters(
        { ...rule, reviewStatus: 'STALE' },
        { onlyUnreviewed: true },
      ),
    ).toBe(true);
    expect(sourceLayer({ ...rule, properties: {}, badges: [] })).toBe(
      'UNASSIGNED',
    );
  });
  it('only includes rules without recorded tests in the test-gap filter', () => {
    expect(matchesFilters(rule, { withoutTests: true })).toBe(true);
    expect(
      matchesFilters({ ...rule, properties: {} }, { withoutTests: true }),
    ).toBe(false);
    expect(
      matchesFilters({ ...rule, badges: ['TESTED'] }, { withoutTests: true }),
    ).toBe(false);
    expect(
      matchesFilters({ ...rule, kind: 'ENDPOINT' }, { withoutTests: true }),
    ).toBe(false);
  });
});
