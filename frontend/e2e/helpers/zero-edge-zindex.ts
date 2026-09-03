// Serialized into the test page via evaluateHandle. No production imports or styles.
export function createZeroEdgeZIndexProbe(surface: Element) {
  const root = surface.querySelector('.react-flow__edges');
  if (!root) throw new Error('Missing React Flow edge container');
  const entries = new Map<
    SVGSVGElement,
    { edge: Element; original: string; priority: string; applied: boolean }
  >();
  let active = false;
  const restoreOne = (
    svg: SVGSVGElement,
    entry: { original: string; priority: string; applied: boolean },
  ) => {
    // Do not overwrite a later nonzero value written by React Flow.
    if (entry.applied && svg.style.getPropertyValue('z-index') === 'auto')
      svg.style.setProperty('z-index', entry.original, entry.priority);
    entry.applied = false;
  };
  const sync = (svg: SVGSVGElement) => {
    const entry = entries.get(svg);
    if (!entry || !active) return;
    if (svg.style.getPropertyValue('z-index') !== 'auto') entry.applied = false;
    if (entry.edge.classList.contains('selected')) {
      restoreOne(svg, entry);
      return;
    }
    if (
      !entry.applied &&
      svg.style.getPropertyValue('z-index') === '0' &&
      svg.style.getPropertyPriority('z-index') === entry.priority
    ) {
      entry.applied = true;
      svg.style.setProperty('z-index', 'auto', entry.priority);
    }
  };
  const observer = new MutationObserver((records) => {
    const changed = new Set<SVGSVGElement>();
    for (const record of records) {
      if (!(record.target instanceof Element)) continue;
      const svg = record.target.closest('svg');
      if (svg instanceof SVGSVGElement && entries.has(svg)) changed.add(svg);
    }
    changed.forEach(sync);
  });
  const inspect = () => ({
    active,
    candidates: entries.size,
    overridden: [...entries].filter(
      ([svg, entry]) =>
        entry.applied && svg.style.getPropertyValue('z-index') === 'auto',
    ).length,
    selectedOverridden: [...entries].filter(
      ([svg, entry]) =>
        entry.edge.classList.contains('selected') &&
        svg.style.getPropertyValue('z-index') === 'auto',
    ).length,
  });
  const restore = () => {
    active = false;
    observer.disconnect();
    entries.forEach((entry, svg) => restoreOne(svg, entry));
    entries.clear();
    return inspect();
  };
  return {
    enable() {
      if (active) throw new Error('Zero-edge diagnostic is already active');
      active = true;
      for (const svg of root.querySelectorAll<SVGSVGElement>(':scope > svg')) {
        const edge = svg.querySelector(':scope > .react-flow__edge');
        if (
          !edge ||
          edge.classList.contains('selected') ||
          svg.style.getPropertyValue('z-index') !== '0'
        )
          continue;
        entries.set(svg, {
          edge,
          original: svg.style.getPropertyValue('z-index'),
          priority: svg.style.getPropertyPriority('z-index'),
          applied: false,
        });
        sync(svg);
      }
      // A narrow observer, never a global :has() selector or a per-frame scan.
      observer.observe(root, {
        subtree: true,
        attributes: true,
        attributeFilter: ['class', 'style'],
      });
      return inspect();
    },
    inspect,
    restore,
  };
}
