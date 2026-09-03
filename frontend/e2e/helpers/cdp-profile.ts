import type { Page, TestInfo } from '@playwright/test';
import { mkdir, writeFile } from 'node:fs/promises';

type ProfileProbe = {
  phase: string;
  marks: { phase: string; at: number }[];
  longTasks: { phase: string; at: number; duration: number }[];
  heartbeat: { phase: string; at: number; delay: number }[];
  frameGaps: { phase: string; at: number; duration: number }[];
  domSamples: {
    phase: string;
    at: number;
    compact: number;
    full: number;
    edges: number;
    labels: number;
    liveElements: number;
  }[];
};
declare global {
  interface Window {
    sbmGraphProfile: ProfileProbe;
  }
}

export async function profileMark(page: Page, phase: string) {
  await page.evaluate((phase) => {
    window.sbmGraphProfile.phase = phase;
    window.sbmGraphProfile.marks.push({ phase, at: performance.now() });
    performance.mark(`sbm:${phase}`);
  }, phase);
}

export async function startGraphProfile(
  page: Page,
  info: TestInfo,
  name: string,
) {
  await page.addInitScript(() => {
    localStorage.setItem('sbm.locale', 'en');
    const probe: ProfileProbe = {
      phase: 'navigation',
      marks: [],
      longTasks: [],
      heartbeat: [],
      frameGaps: [],
      domSamples: [],
    };
    window.sbmGraphProfile = probe;
    const NativeWorker = window.Worker;
    window.Worker = class extends NativeWorker {
      constructor(url: string | URL, options?: WorkerOptions) {
        super(url, options);
        if (String(url).includes('layout.worker')) {
          probe.marks.push({ phase: 'worker-created', at: performance.now() });
          performance.mark('sbm:worker-created');
          this.addEventListener('message', () => {
            probe.phase = 'install-layout';
            probe.marks.push({ phase: probe.phase, at: performance.now() });
            performance.mark('sbm:install-layout');
          });
        }
      }
    };
    if (PerformanceObserver.supportedEntryTypes.includes('longtask')) {
      new PerformanceObserver((entries) => {
        for (const entry of entries.getEntries())
          probe.longTasks.push({
            phase: probe.phase,
            at: entry.startTime,
            duration: entry.duration,
          });
      }).observe({ type: 'longtask', buffered: true });
    }
    let heartbeat = performance.now();
    setInterval(() => {
      const now = performance.now();
      probe.heartbeat.push({
        phase: probe.phase,
        at: now,
        delay: Math.max(0, now - heartbeat - 100),
      });
      heartbeat = now;
    }, 100);
    let previousFrame = performance.now();
    const frame = (now: number) => {
      if (now - previousFrame > 50)
        probe.frameGaps.push({
          phase: probe.phase,
          at: now,
          duration: now - previousFrame,
        });
      previousFrame = now;
      requestAnimationFrame(frame);
    };
    requestAnimationFrame(frame);
    let lastGeometry = '';
    new MutationObserver(() => {
      const compact = document.querySelectorAll(
        '.semantic-node.compact',
      ).length;
      const full = document.querySelectorAll(
        '.semantic-node:not(.compact)',
      ).length;
      const edges = document.querySelectorAll('.react-flow__edge').length;
      const labels = document.querySelectorAll(
        '.react-flow__edge-textwrapper',
      ).length;
      const signature = `${compact}:${full}:${edges}:${labels}`;
      if (lastGeometry !== signature) {
        lastGeometry = signature;
        probe.domSamples.push({
          phase: probe.phase,
          at: performance.now(),
          compact,
          full,
          edges,
          labels,
          liveElements: document.getElementsByTagName('*').length,
        });
      }
    }).observe(document, {
      subtree: true,
      childList: true,
      attributes: true,
      attributeFilter: ['class'],
    });
  });
  const cdp = await page.context().newCDPSession(page);
  await mkdir('output/playwright', { recursive: true });
  await cdp.send('Profiler.enable');
  await cdp.send('Profiler.setSamplingInterval', { interval: 1000 });
  await cdp.send('Profiler.start');
  await cdp.send('Tracing.start', {
    categories:
      'devtools.timeline,v8,blink.user_timing,disabled-by-default-devtools.timeline',
    transferMode: 'ReturnAsStream',
  });
  return async (details: Record<string, unknown>) => {
    const { profile } = await cdp.send('Profiler.stop');
    const complete = new Promise<string>((resolve) =>
      cdp.once('Tracing.tracingComplete', (event) => resolve(event.stream!)),
    );
    await cdp.send('Tracing.end');
    const handle = await complete;
    let trace = '';
    while (true) {
      const part = await cdp.send('IO.read', { handle });
      trace += part.base64Encoded
        ? Buffer.from(part.data, 'base64').toString('utf8')
        : part.data;
      if (part.eof) break;
    }
    await cdp.send('IO.close', { handle });
    const probe = await page.evaluate(() => window.sbmGraphProfile);
    const selfTimes = new Map<number, number>();
    for (const [index, id] of (profile.samples ?? []).entries())
      selfTimes.set(
        id,
        (selfTimes.get(id) ?? 0) + (profile.timeDeltas?.[index] ?? 0),
      );
    const hotFrames = profile.nodes
      .map((node) => ({
        ...node.callFrame,
        selfMs: (selfTimes.get(node.id) ?? 0) / 1000,
      }))
      .filter((frame) => frame.selfMs > 0)
      .sort((a, b) => b.selfMs - a.selfMs)
      .slice(0, 80);
    const report = {
      ...details,
      measuredAt: new Date().toISOString(),
      baseURL: info.project.use.baseURL,
      probe,
      hotFrames,
      caveat:
        'Instrumented diagnostic, not an acceptance run. CDP CPU sampling and tracing add overhead; unchanged performance gates run separately.',
    };
    await mkdir('output/playwright', { recursive: true });
    const prefix = `output/playwright/${name}-${info.project.name}`;
    await writeFile(`${prefix}.cpuprofile`, JSON.stringify(profile));
    await writeFile(`${prefix}-trace.json`, trace);
    await writeFile(`${prefix}.json`, JSON.stringify(report, null, 2));
    await info.attach(name, {
      body: JSON.stringify(report, null, 2),
      contentType: 'application/json',
    });
    await cdp.detach();
    return report;
  };
}
