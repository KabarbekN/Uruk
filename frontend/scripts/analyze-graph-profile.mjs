import { readFile } from 'node:fs/promises';
import process from 'node:process';
import { log } from 'node:console';

const prefix = process.argv[2];
if (!prefix) throw new Error('Provide the profile artifact prefix');
const profile = JSON.parse(await readFile(`${prefix}.cpuprofile`, 'utf8'));
const report = JSON.parse(await readFile(`${prefix}.json`, 'utf8'));
const { traceEvents } = JSON.parse(
  await readFile(`${prefix}-trace.json`, 'utf8'),
);
const nodes = new Map(profile.nodes.map((node) => [node.id, node]));
const parents = new Map(
  profile.nodes.flatMap((node) =>
    (node.children ?? []).map((id) => [id, node.id]),
  ),
);
const buckets = {};
const selfTimes = new Map();
for (const [index, id] of (profile.samples ?? []).entries()) {
  const duration = (profile.timeDeltas?.[index] ?? 0) / 1000;
  selfTimes.set(id, (selfTimes.get(id) ?? 0) + duration);
  const stack = [];
  let current = id;
  while (nodes.has(current)) {
    stack.push(nodes.get(current).callFrame);
    current = parents.get(current);
  }
  const leaf = stack[0];
  const bucket = stack.some((frame) => frame.functionName === 'captureSnapshot')
    ? 'Playwright trace captureSnapshot'
    : stack.some((frame) => frame.url.includes('/assets/'))
      ? 'Application JS incl React/ReactFlow'
      : leaf.functionName === '(program)'
        ? 'Native/program (see timeline)'
        : leaf.functionName === '(idle)'
          ? 'Idle'
          : leaf.functionName === '(garbage collector)'
            ? 'GC'
            : 'Other injected/browser JS';
  buckets[bucket] = (buckets[bucket] ?? 0) + duration;
}
const marker = traceEvents.find((event) => event.name === 'sbm:graph-ready');
const mark = report.probe.marks.find((mark) => mark.phase === 'graph-ready');
const origin = marker.ts - mark.at * 1000;
const main = traceEvents.filter(
  (event) =>
    event.pid === marker.pid && event.tid === marker.tid && event.ph === 'X',
);
const timeline = {};
const phases = {};
for (const event of main) {
  const row = (timeline[event.name] ??= { count: 0, inclusiveMs: 0, maxMs: 0 });
  row.count++;
  row.inclusiveMs += event.dur / 1000;
  row.maxMs = Math.max(row.maxMs, event.dur / 1000);
  const at = (event.ts - origin) / 1000;
  const phase =
    report.probe.marks.findLast((mark) => mark.at <= at)?.phase ?? 'navigation';
  const phaseRow = ((phases[phase] ??= {})[event.name] ??= {
    count: 0,
    inclusiveMs: 0,
    maxMs: 0,
  });
  phaseRow.count++;
  phaseRow.inclusiveMs += event.dur / 1000;
  phaseRow.maxMs = Math.max(phaseRow.maxMs, event.dur / 1000);
}
const hotApplicationFrames = profile.nodes
  .filter((node) => node.callFrame.url.includes('/assets/'))
  .map((node) => ({ ...node.callFrame, selfMs: selfTimes.get(node.id) ?? 0 }))
  .sort((a, b) => b.selfMs - a.selfMs)
  .slice(0, 20);
log(
  JSON.stringify(
    {
      sampledTimeMs: buckets,
      phasesInclusiveNotAdditive: Object.fromEntries(
        Object.entries(phases).map(([name, rows]) => [
          name,
          Object.fromEntries(
            Object.entries(rows).filter(([event]) =>
              [
                'FunctionCall',
                'Layout',
                'Paint',
                'PrePaint',
                'UpdateLayoutTree',
                'HitTest',
                'Layerize',
                'EventDispatch',
                'Commit',
              ].includes(event),
            ),
          ),
        ]),
      ),
      timelineInclusiveNotAdditive: Object.fromEntries(
        Object.entries(timeline)
          .sort((a, b) => b[1].inclusiveMs - a[1].inclusiveMs)
          .slice(0, 24),
      ),
      longestEvents: main
        .filter((event) => event.dur > 100000)
        .sort((a, b) => b.dur - a.dur)
        .slice(0, 15)
        .map((event) => ({
          name: event.name,
          atMs: (event.ts - origin) / 1000,
          durationMs: event.dur / 1000,
          args: event.args,
        })),
      hotApplicationFrames,
      domSamples: report.probe.domSamples,
    },
    null,
    2,
  ),
);
