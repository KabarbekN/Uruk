import { memo } from 'react';
import { Handle, Position, type Node, type NodeProps } from '@xyflow/react';
import {
  Braces,
  Database,
  Diamond,
  FileCode2,
  Globe,
  Pin,
  ShieldCheck,
  Workflow,
} from 'lucide-react';
import type { SemanticNode as SemanticEntity } from '../../shared/api/types';
import { useT } from '../../shared/lib/i18n';
import { Badge } from '../../shared/ui';

export type SemanticFlowNode = Node<
  { entity: SemanticEntity; pinned: boolean; compact?: boolean },
  'semantic'
>;
export const SemanticNode = memo(
  function SemanticNode({ data, selected }: NodeProps<SemanticFlowNode>) {
    const { entity, pinned, compact } = data;
    const { label, t } = useT();
    const kind = entity.kind;
    const tone = /AUTH|SECURITY|ROLE/.test(kind)
      ? 'security'
      : /DATA|DATABASE|TABLE|COLUMN|SQL/.test(kind)
        ? 'data'
        : /RULE|CONSTRAINT|VALIDATION|DECISION/.test(kind)
          ? 'rule'
          : /EXTERNAL|SIDE_EFFECT/.test(kind)
            ? 'external'
            : 'technical';
    const Icon =
      tone === 'security'
        ? ShieldCheck
        : tone === 'data'
          ? Database
          : tone === 'rule'
            ? Diamond
            : tone === 'external'
              ? Globe
              : /SCENARIO|ENDPOINT/.test(kind)
                ? Workflow
                : /METHOD|CLASS/.test(kind)
                  ? FileCode2
                  : Braces;
    // A server claim of PROVEN is never promoted visually when it has no evidence.
    const supported = entity.evidenceCount > 0;
    if (compact)
      return (
        <div
          className={`semantic-node compact ${tone} ${selected ? 'selected' : ''}`}
          title={entity.label}
        >
          <Handle type="target" position={Position.Left} />
          <strong>{entity.label}</strong>
          <span
            className={`confidence-dot ${entity.confidence < 0.7 || !supported ? 'low' : ''}`}
          >
            {Math.round(entity.confidence * 100)}%
          </span>
          <Handle type="source" position={Position.Right} />
        </div>
      );
    return (
      <div className={`semantic-node ${tone} ${selected ? 'selected' : ''}`}>
        <Handle type="target" position={Position.Left} />
        <header>
          <span className="node-kind">
            <Icon size={14} />
            {label(kind)}
          </span>
          {pinned && <Pin size={13} aria-label={t('pin')} />}
        </header>
        <strong title={entity.label}>{entity.label}</strong>
        <p title={entity.subtitle ?? ''}>{entity.subtitle}</p>
        <footer>
          <span
            className={`confidence-dot ${entity.confidence < 0.7 || !supported ? 'low' : ''}`}
          >
            {Math.round(entity.confidence * 100)}%
          </span>
          <span className="node-evidence" title={t('evidenceCount')}>
            <FileCode2 size={12} />
            {entity.evidenceCount}
          </span>
          <Badge
            tone={
              !supported || entity.supportLevel !== 'PROVEN'
                ? 'warning'
                : 'positive'
            }
          >
            {supported ? label(entity.supportLevel) : t('unverified')}
          </Badge>
        </footer>
        <Handle type="source" position={Position.Right} />
      </div>
    );
  },
  (previous, next) =>
    previous.data === next.data && previous.selected === next.selected,
);
