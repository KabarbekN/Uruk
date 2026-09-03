import { useEffect, useState } from 'react';
import { ChevronLeft, ChevronRight } from 'lucide-react';
import type { SemanticNode } from '../../shared/api/types';
import { useT } from '../../shared/lib/i18n';
import { IconButton, Status } from '../../shared/ui';

export function NodeList({
  nodes,
  selectedId,
  onSelect,
}: {
  nodes: SemanticNode[];
  selectedId?: string;
  onSelect: (node: SemanticNode) => void;
}) {
  const { t, label } = useT();
  const [page, setPage] = useState(0);
  const pages = Math.max(1, Math.ceil(nodes.length / 50));
  const active = Math.min(page, pages - 1);
  useEffect(() => setPage(0), [nodes]);
  return (
    <>
      <div className="node-table table-wrap">
        <table>
          <thead>
            <tr>
              <th>{t('node')}</th>
              <th>{t('kind')}</th>
              <th>{t('confidence')}</th>
              <th>{t('evidence')}</th>
              <th>{t('reviewStatus')}</th>
            </tr>
          </thead>
          <tbody>
            {nodes.slice(active * 50, (active + 1) * 50).map((node) => (
              <tr
                key={node.id}
                className={selectedId === node.id ? 'selected' : ''}
              >
                <td>
                  <button className="table-link" onClick={() => onSelect(node)}>
                    {node.label}
                  </button>
                  <small className="muted">{node.subtitle}</small>
                </td>
                <td>{label(node.kind)}</td>
                <td>{Math.round(node.confidence * 100)}%</td>
                <td>{node.evidenceCount}</td>
                <td>
                  <Status value={node.reviewStatus} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {pages > 1 && (
        <div className="pagination">
          <IconButton
            icon={ChevronLeft}
            label={t('previousPage')}
            disabled={active === 0}
            onClick={() => setPage(active - 1)}
          />
          <span aria-live="polite">
            {active + 1} / {pages}
          </span>
          <IconButton
            icon={ChevronRight}
            label={t('nextPage')}
            disabled={active + 1 === pages}
            onClick={() => setPage(active + 1)}
          />
        </div>
      )}
    </>
  );
}
