import { z } from 'zod';
import type { CanvasLayout, SemanticNode } from '../../src/shared/api/types';

// Mirrors CanvasLayoutController validation and full-replacement PUT semantics.
// Keep this bound independent of the client constant so a client regression fails.
const coordinate = z.number().finite().min(-1e7).max(1e7);
const layoutSchema = z.object({
  pinnedStableKeys: z.array(z.string().max(2048)).max(2000).default([]),
  positions: z
    .record(z.string().max(2048), z.object({ x: coordinate, y: coordinate }))
    .refine((positions) => Object.keys(positions).length <= 2000),
  viewport: z.object({
    x: coordinate,
    y: coordinate,
    zoom: z.number().finite().min(0.01).max(10),
  }),
});

export function createLayoutServer(
  nodes: Pick<SemanticNode, 'stableKey'>[],
  initial: CanvasLayout = { positions: {}, viewport: { x: 0, y: 0, zoom: 1 } },
) {
  const members = new Set(nodes.map((node) => node.stableKey));
  let saved = structuredClone(initial);
  const accepted: CanvasLayout[] = [];
  const rejected: string[] = [];
  const get = (): CanvasLayout =>
    structuredClone({
      positions: Object.fromEntries(
        Object.entries(saved.positions)
          .filter(([key]) => members.has(key))
          .sort(([a], [b]) => a.localeCompare(b))
          .slice(0, 2000),
      ),
      viewport: saved.viewport,
      pinnedStableKeys: (saved.pinnedStableKeys ?? []).filter((key) =>
        members.has(key),
      ),
    });
  return {
    get,
    accepted,
    rejected,
    put(body: unknown) {
      const parsed = layoutSchema.safeParse(body);
      const detail = !parsed.success
        ? 'Invalid layout or more than 2000 positions'
        : Object.keys(parsed.data.positions).some((key) => !members.has(key))
          ? 'Layout contains a node outside this analysis'
          : parsed.data.pinnedStableKeys.some(
                (key) => !(key in parsed.data.positions),
              ) ||
              new Set(parsed.data.pinnedStableKeys).size !==
                parsed.data.pinnedStableKeys.length
            ? 'Pinned keys must be unique members of the saved positions'
            : null;
      if (detail || !parsed.success) {
        rejected.push(detail!);
        return { status: 400, json: { detail: detail! } };
      }
      saved = structuredClone(parsed.data);
      accepted.push(structuredClone(saved));
      return { status: 200, json: get() };
    },
  };
}
