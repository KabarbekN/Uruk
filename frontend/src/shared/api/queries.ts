import { useQuery } from '@tanstack/react-query';
import { api } from './client';

export const terminalStatuses = new Set([
  'SUCCEEDED',
  'SUCCESS',
  'COMPLETED',
  'PARTIALLY_SUCCEEDED',
  'FAILED',
  'CANCELLED',
  'CANCELED',
]);
export const isTerminal = (status: string) =>
  terminalStatuses.has(status.toUpperCase());
export const useProjects = () =>
  useQuery({
    queryKey: ['projects'],
    queryFn: ({ signal }) => api.projects(signal),
  });
export const useRuns = (projectId?: string) =>
  useQuery({
    queryKey: ['runs', projectId],
    queryFn: ({ signal }) => api.runs(projectId!, signal),
    enabled: Boolean(projectId),
    refetchInterval: (query) =>
      query.state.data?.some((run) => !isTerminal(run.status)) ? 3000 : false,
  });
export const useRun = (id?: string) =>
  useQuery({
    queryKey: ['run', id],
    queryFn: ({ signal }) => api.run(id!, signal),
    enabled: Boolean(id),
    refetchInterval: (query) =>
      query.state.data && !isTerminal(query.state.data.status) ? 3000 : false,
  });
