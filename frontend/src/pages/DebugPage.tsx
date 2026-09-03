import { useState } from 'react';
import { useParams } from 'react-router-dom';
import { Bug, FileWarning, Gauge, ListTree, Unplug } from 'lucide-react';
import type { InspectionResource } from '../shared/api/client';
import { useRun } from '../shared/api/queries';
import { useT, type TranslationKey } from '../shared/lib/i18n';
import { ErrorState, Loading, PageHeader } from '../shared/ui';
import { AnalysisProgress } from '../features/analysis-progress/AnalysisProgress';
import { InspectionPanel } from '../features/analyzer-debug/InspectionPanel';

const tabs = [
  { resource: 'analyzer-plan', key: 'analyzerPlan', icon: ListTree },
  { resource: 'coverage', key: 'coverage', icon: Gauge },
  { resource: 'diagnostics', key: 'diagnostics', icon: Bug },
  { resource: 'quarantined-facts', key: 'quarantined', icon: FileWarning },
  { resource: 'unresolved-symbols', key: 'unresolvedSymbols', icon: Unplug },
] satisfies {
  resource: InspectionResource;
  key: TranslationKey;
  icon: typeof Bug;
}[];

export default function DebugPage() {
  const { analysisRunId = '' } = useParams();
  const { t } = useT();
  const run = useRun(analysisRunId);
  const [resource, setResource] = useState<InspectionResource>('analyzer-plan');
  if (run.isPending) return <Loading />;
  if (run.isError)
    return <ErrorState error={run.error} retry={() => void run.refetch()} />;
  return (
    <div className="page">
      <PageHeader
        title={t('debug')}
        eyebrow={run.data.revision?.slice(0, 12) || analysisRunId.slice(0, 8)}
      />
      <AnalysisProgress run={run.data} />
      <div className="view-tabs" role="tablist" aria-label={t('debug')}>
        {tabs.map((tab) => (
          <button
            key={tab.resource}
            type="button"
            role="tab"
            aria-selected={resource === tab.resource}
            onClick={() => setResource(tab.resource)}
          >
            <tab.icon size={15} />
            {t(tab.key)}
          </button>
        ))}
      </div>
      <InspectionPanel
        key={`${analysisRunId}:${resource}`}
        runId={analysisRunId}
        resource={resource}
      />
    </div>
  );
}
