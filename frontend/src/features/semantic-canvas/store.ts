import { create } from 'zustand';
import type { CanvasLayout, CanvasView } from '../../shared/api/types';

export type Selection = { kind: 'nodes' | 'edges'; id: string; label: string };
export type FilterFlag =
  | 'onlyUnreviewed'
  | 'lowConfidence'
  | 'unresolved'
  | 'withoutTests'
  | 'authRules'
  | 'businessRules'
  | 'dbWrites'
  | 'externalCalls'
  | 'llmEnriched'
  | 'onlyChanged';
export type Filters = Partial<Record<FilterFlag, boolean>> & {
  sourceLayer?: string;
  analyzer?: string;
  component?: string;
  framework?: string;
};
type CanvasState = {
  view: CanvasView;
  depth: number;
  detail: number;
  minConfidence: number;
  search: string;
  rootNodeId?: string;
  filters: Filters;
  selected: Selection | null;
  drawerWidth: number;
  layouts: Record<string, CanvasLayout>;
  pins: Record<string, string[]>;
  setView: (view: CanvasView) => void;
  setSearch: (search: string) => void;
  setDepth: (depth: number) => void;
  setDetail: (detail: number) => void;
  setConfidence: (value: number) => void;
  setRoot: (rootNodeId?: string) => void;
  setFilters: (filters: Filters) => void;
  select: (selected: Selection | null) => void;
  setDrawerWidth: (width: number) => void;
  rememberLayout: (scope: string, layout: CanvasLayout) => void;
  togglePin: (scope: string, id: string) => void;
};
export const useCanvasStore = create<CanvasState>((set) => ({
  view: 'BUSINESS',
  depth: 2,
  detail: 2,
  minConfidence: 0,
  search: '',
  filters: {},
  selected: null,
  drawerWidth: 500,
  layouts: {},
  pins: {},
  setView: (view) => set({ view, selected: null, rootNodeId: undefined }),
  setSearch: (search) => set({ search }),
  setDepth: (depth) => set({ depth }),
  setDetail: (detail) => set({ detail }),
  setConfidence: (minConfidence) => set({ minConfidence }),
  setRoot: (rootNodeId) => set({ rootNodeId }),
  setFilters: (filters) => set({ filters }),
  select: (selected) => set({ selected }),
  setDrawerWidth: (drawerWidth) => set({ drawerWidth }),
  rememberLayout: (scope, layout) =>
    set((state) => ({ layouts: { ...state.layouts, [scope]: layout } })),
  togglePin: (scope, id) =>
    set((state) => {
      const pins = state.pins[scope] ?? [];
      return {
        pins: {
          ...state.pins,
          [scope]: pins.includes(id)
            ? pins.filter((item) => item !== id)
            : [...pins, id],
        },
      };
    }),
}));
