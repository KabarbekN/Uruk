import type { components, operations } from './generated';

export type Project = components['schemas']['Project'];
export type ProjectInput = components['schemas']['ProjectInput'];
export type RepositoryInput = components['schemas']['RepositoryInput'];
export type AnalysisRun = components['schemas']['AnalysisRun'];
export type AnalysisInput = components['schemas']['AnalysisInput'];
export type AnalysisEvent = components['schemas']['AnalysisEvent'];
export type SemanticNode = components['schemas']['SemanticNode'];
export type SemanticNodeDetail = components['schemas']['SemanticNodeDetail'];
export type SemanticEdge = components['schemas']['SemanticEdge'];
export type CanvasProjection = components['schemas']['CanvasProjection'];
export type CanvasView = components['schemas']['CanvasView'];
export type CanvasLayout = components['schemas']['CanvasLayout'];
export type CanvasParams = NonNullable<
  operations['getCanvas']['parameters']['query']
>;
export type Evidence = components['schemas']['Evidence'];
export type ReviewInput = components['schemas']['ReviewInput'];
export type SemanticChange = components['schemas']['SemanticChange'];
export type Settings = components['schemas']['Settings'];
export type SettingsInput = components['schemas']['SettingsInput'];
export type InspectionData = components['schemas']['InspectionData'];
export type Problem = components['schemas']['Problem'];
