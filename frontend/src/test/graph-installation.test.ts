import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  EDGE_INSTALL_BATCH,
  installGraph,
  NODE_INSTALL_BATCH,
} from '../features/semantic-canvas/install-graph';

beforeEach(() => {
  vi.useFakeTimers();
  vi.stubGlobal('requestAnimationFrame', (callback: FrameRequestCallback) =>
    setTimeout(() => callback(performance.now()), 16),
  );
  vi.stubGlobal('cancelAnimationFrame', (id: number) => clearTimeout(id));
});
afterEach(() => {
  vi.useRealTimers();
});

describe('cancellable graph installation', () => {
  it('installs every 2k node and 4200 edge once, yielding bounded batches and completing after the last paint opportunity', () => {
    const nodes = Array.from({ length: 2000 }, (_, id) => ({ id }));
    const edges = Array.from({ length: 4200 }, (_, id) => ({ id }));
    const installedNodes: typeof nodes = [];
    const installedEdges: typeof edges = [];
    const completed = vi.fn();
    installGraph(
      nodes,
      edges,
      (batch) => {
        expect(batch.length).toBeLessThanOrEqual(NODE_INSTALL_BATCH);
        installedNodes.push(...batch);
      },
      (batch) => {
        expect(installedNodes).toHaveLength(2000);
        expect(batch.length).toBeLessThanOrEqual(EDGE_INSTALL_BATCH);
        installedEdges.push(...batch);
        expect(completed).not.toHaveBeenCalled();
      },
      completed,
    );
    expect(installedNodes).toHaveLength(NODE_INSTALL_BATCH);
    expect(installedEdges).toHaveLength(0);
    expect(completed).not.toHaveBeenCalled();
    vi.runAllTimers();
    expect(installedNodes).toEqual(nodes);
    expect(installedEdges).toEqual(edges);
    expect(new Set(installedNodes).size).toBe(2000);
    expect(new Set(installedEdges).size).toBe(4200);
    expect(completed).toHaveBeenCalledOnce();
    expect(vi.getTimerCount()).toBe(0);
  });

  it.each(['frame', 'timer'] as const)(
    'cancels a pending %s without appending or publishing incomplete geometry',
    (phase) => {
      const append = vi.fn();
      const complete = vi.fn();
      const cancel = installGraph(
        Array.from({ length: 500 }, (_, id) => id),
        [],
        append,
        vi.fn(),
        complete,
      );
      if (phase === 'timer') vi.advanceTimersToNextTimer();
      cancel();
      vi.runAllTimers();
      expect(append).toHaveBeenCalledOnce();
      expect(complete).not.toHaveBeenCalled();
      expect(vi.getTimerCount()).toBe(0);
    },
  );

  it('cancels a partially installed edge set without declaring it complete', () => {
    const complete = vi.fn();
    const appendEdges = vi.fn();
    const cancel = installGraph(
      [1],
      Array.from({ length: 1000 }, (_, id) => id),
      vi.fn(),
      appendEdges,
      complete,
    );
    while (appendEdges.mock.calls.length === 0) vi.advanceTimersToNextTimer();
    cancel();
    vi.runAllTimers();
    expect(appendEdges).toHaveBeenCalledOnce();
    expect(complete).not.toHaveBeenCalled();
  });

  it('retains synchronous installation for small projections', () => {
    const complete = vi.fn();
    const nodes = vi.fn();
    const edges = vi.fn();
    installGraph([1, 2, 3], [1, 2], nodes, edges, complete);
    expect(nodes).toHaveBeenCalledWith([1, 2, 3]);
    expect(edges).toHaveBeenCalledWith([1, 2]);
    expect(complete).toHaveBeenCalledOnce();
    expect(vi.getTimerCount()).toBe(0);
  });
});
