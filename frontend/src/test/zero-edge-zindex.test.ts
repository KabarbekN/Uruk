import { afterEach, describe, expect, it } from 'vitest';
import { createZeroEdgeZIndexProbe } from '../../e2e/helpers/zero-edge-zindex';

const cleanups: (() => void)[] = [];
function fixture() {
  const surface = document.createElement('div');
  surface.innerHTML = `
    <div class="react-flow__edges">
      <svg style="z-index: 0;"><g class="react-flow__edge"><path d="M0 0 L20 0" marker-end="url(#arrow)" /></g></svg>
      <svg style="z-index: 0;"><g class="react-flow__edge selected" /></svg>
      <svg style="z-index: 1000;"><g class="react-flow__edge" /></svg>
      <svg><defs><marker id="arrow" /></defs></svg>
    </div>
    <div class="react-flow__node selected" style="z-index: 1000; transform: translate(20px, 30px);"></div>`;
  document.body.append(surface);
  const probe = createZeroEdgeZIndexProbe(surface);
  const svg = surface.querySelector('svg')!;
  const edge = svg.querySelector('g')!;
  cleanups.push(() => {
    probe.restore();
    surface.remove();
  });
  return { surface, svg, edge, probe };
}
afterEach(() => cleanups.splice(0).forEach((cleanup) => cleanup()));
const mutations = async () => {
  await Promise.resolve();
  await Promise.resolve();
};

describe('test-only reversible default-zero edge z-index probe', () => {
  it('changes only unselected inline-zero SVGs and restores exact markup', () => {
    const { surface, probe } = fixture();
    const before = surface.innerHTML;
    expect(probe.enable()).toMatchObject({
      candidates: 1,
      overridden: 1,
      selectedOverridden: 0,
    });
    expect(
      surface.querySelector('.react-flow__node')?.getAttribute('style'),
    ).toBe('z-index: 1000; transform: translate(20px, 30px);');
    expect(surface.querySelectorAll('svg')[1]!.style.zIndex).toBe('0');
    expect(surface.querySelectorAll('svg')[2]!.style.zIndex).toBe('1000');
    probe.restore();
    expect(surface.innerHTML).toBe(before);
    expect(probe.restore()).toMatchObject({ active: false, overridden: 0 });
  });

  it('restores zero on selection and preserves later nonzero elevation on cleanup', async () => {
    const { svg, edge, probe } = fixture();
    probe.enable();
    edge.classList.add('selected');
    await mutations();
    expect(svg.style.zIndex).toBe('0');
    expect(probe.inspect().selectedOverridden).toBe(0);
    edge.classList.remove('selected');
    await mutations();
    expect(svg.style.zIndex).toBe('auto');
    svg.style.zIndex = '2000';
    await mutations();
    expect(svg.style.zIndex).toBe('2000');
    probe.restore();
    expect(svg.style.zIndex).toBe('2000');
  });

  it('retains hit paths, markers, click listeners and original declaration priority', () => {
    const { svg, edge, probe } = fixture();
    svg.style.setProperty('z-index', '0', 'important');
    const before = svg.outerHTML;
    let clicks = 0;
    edge.addEventListener('click', () => {
      clicks++;
    });
    probe.enable();
    edge
      .querySelector('path')!
      .dispatchEvent(new MouseEvent('click', { bubbles: true }));
    expect(clicks).toBe(1);
    expect(svg.style.getPropertyPriority('z-index')).toBe('important');
    probe.restore();
    expect(svg.outerHTML).toBe(before);
  });
});
