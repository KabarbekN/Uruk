# Bounded Frontend Performance Acceptance

## Current Status (2026-09-03)

**Acceptance remains open: 2k pan and selection are still red. The complete
10k progressive desktop/mobile suite now passes.** No 60 FPS claim. The deployed image at `http://127.0.0.1:8088` is
`sha256:90d0632697609bc913d13ddd14aafeba83200255657914a2f2df58df77b3aa5a`.
The latest runtime candidate below was built locally and measured on production
Vite preview `http://127.0.0.1:5177`, NOT rebuilt/deployed as a Docker image.
Its canvas asset is `CanvasPage-BSoMUzQS.js`; a later diff-baseline fix is not
included in that asset build. Preview was stopped after the 10k checkpoint.

### Corrected Measurement Method

CDP established that Playwright's `retain-on-failure` trace incurs recursive
DOM snapshot collection during the run, not just after failure. Sampled
`visitNode` / `captureSnapshot` frames matched installed Playwright 1.62.1
`playwright-core/lib/coreBundle.js` lines 14656ff. The traced 2k profile spent
about 15.7 seconds sampled in snapshot collection. Performance suites now disable
Playwright tracing and scope role locators to toolbars/panels; functional traces
remain enabled. CDP diagnostics are separate from acceptance and add overhead.
Original artifacts are preserved in `output/playwright/baseline-90d/` and the
controlled trace-off comparison in `output/playwright/baseline-90d-trace-off/`.

Unchanged limits: ready <35,000 ms; longest task and 100 ms heartbeat delay
<1,500 ms; each measured control sequence <3,000 ms. Data, geometry and gates
were not reduced. All 2k geometry checks require 2,000 fully visible nodes;
the candidate additionally explicitly asserts all 4,200 edge elements.

| Trace-off desktop measurement | Deployed 90d, 1k | Deployed 90d, 2k | Local staged candidate, 2k |
| --- | ---: | ---: | ---: |
| Ready, ms | 5,668 | 6,447 | 9,290 |
| Longest task, ms | 579 | **2,886** | 627 |
| Heartbeat delay, ms | 879.1 | **3,800.1** | 619.7 |
| Lock/unlock, ms | 342 | 2,148 | 1,353 |
| Pan, ms | 136 | **4,121** | **4,041** |
| Minimap, ms | 406 | 1,816 | 1,126 |
| Selection and close, ms | not measured | **5,088** | **3,227** |
| Zoom/fit, ms | 205 | 1,387 | 1,542 |

The staged report is preserved in `output/playwright/staged-first/`.
Candidate renderer JS heap after interactions was about 137 MB versus 415 MB
on trace-off 90d; event listeners 24,396 versus 103,166. These are single-run CDP
counters without forced GC, include detached objects, and exclude process RSS,
GPU memory and worker heaps. They are not a memory-leak conclusion or SLA.

### Applied Changes And Residual Diagnosis

Already deployed in 90d: finite complete saved layouts skip unnecessary ELK;
explicit Arrange, partial layouts and ownership retain the real worker. Layout
PUT is bounded to 2,000 entries with full-replacement semantics, protecting
current nodes and known pins. An over-capacity protected union stays local and
does not submit an invalid/destructive snapshot.

Local candidate: cancellable node/edge installation across frame/task yields;
stable presentation object identities and React Flow callbacks; scoped Zustand
subscriptions; persistence only after complete geometry. All 68 unit/RTL tests,
TypeScript and ESLint passed before the later diff edit. Diff default now selects
the latest eligible completed run strictly before the target, with manual choices
preserved and no invented baseline response field; its 3 focused RTL tests pass.

`profile-2000-staged-pan-desktop` CDP timeline reports ordinary pan inclusive
Layerize 987 ms, HitTest 443 ms, FunctionCall 214 ms and Paint 16 ms; inclusive
durations must not be summed as disjoint work. Test-only `will-change: transform`
worsened pan from 3,917 to 4,652 ms and Layerize to 3,011 ms, so it was NOT added
to runtime. A separate four-dispatch/paint diagnostic with a test-only edge
hit-testing experiment timed out at 120 seconds; it produced no final CDP report
and provides no evidence of an improvement. That CSS is also NOT in runtime.
All observed initial 2k nodes were compact, with no transient full-detail mount.

### Complete 10k Progressive Verification

The quiet production-preview command `playwright test e2e/progressive-graph.spec.ts
--project desktop --project mobile` completed **2 passed in 7.4 minutes** on the
same staged bundle, without further CSS/runtime changes. Playwright DOM tracing
was off; all pre-existing gates remained unchanged.

| Measurement | Desktop 1440 x 960 | Mobile 390 x 844 |
| --- | ---: | ---: |
| Discovered nodes / completed roots | 10,000 / 20 | 10,000 / 20 |
| Initial 250-node ready, ms | 5,598 | 5,242 |
| Slowest 500-node root ready, ms | 10,502 | 9,390 |
| Longest task, ms | 343 | 353 |
| Maximum heartbeat delay, ms | 571.3 | 562.5 |
| Slowest measured control, ms | 479 | 296 |
| Largest actual response, nodes / edges | 500 / 1,050 | 500 / 1,050 |
| Retained pins / rejected layout saves | 20 / 0 | 20 / 0 |
| Renderer JS heap at root 20, MB | 55.0 | 36.2 |

Reports: `output/playwright/progressive-10k-{desktop,mobile}.json`; screenshots
at roots 1, 10 and 20 use the same prefix. Original interrupted desktop screenshot
is retained as `progressive-10k-desktop-1-interrupted-90d.png`.
The strict test server accepted all twenty full-replacement layout PUTs per
viewport; entry counts reached 2,000 at root four and never exceeded the cap.
Every current projection position and all previously pinned roots survived.
Real ELK workers ran for new layouts with no worker failure or page error.

The test-only graph respects LOD caps 80/120/250/500, depth <=5 and edges <=2,000.
It explores 10k cumulatively, never returns/renders 10k at once, and does not claim
real backend/analyzer acceptance. The independent 2k renderer exceeds backend
projection caps intentionally. Mobile is Chrome viewport/touch emulation, not a
physical-device benchmark. The heap caveats above apply; no forced GC or soak
test was performed. No production fixture is used.

### Read-Only Stacking Investigation

Installed `@xyflow/react` 12.11.6 `dist/esm/index.js:3031` creates a separate
`svg` with inline z-index per edge, and `dist/style.css:151` makes these SVGs
absolute with visible overflow. The flat fixture defaults to zero; selected
nodes elevate, while the public default `elevateEdgesOnSelect` is false. Nonzero
edge order can still come from ownership parents. `Viewport` already updates its
transform directly, without a React render per pan frame.

Proposed next bounded diagnostic, not a runtime change: compare baseline against
`z-index:auto` only on unselected, original-default-zero edge SVGs in large flat
compact mode. Preserve selected/nonzero SVGs, all node elevation, paths, markers,
hit targets and events; avoid blanket overrides, `will-change` and global `:has()`.
This is a hypothesis, not a proven removal of all contexts: the
[SVG2 rendering model](https://www.w3.org/TR/SVG2/render.html#EstablishingStackingContex)
also requires an outermost SVG to establish a stacking context. Context counts
are not equivalent to GPU-layer counts.

`ViewportPortal`, `BaseEdge` and `getSmoothStepPath` are supported building blocks,
but there is no public shared-SVG EdgeRenderer switch. Custom `edgeTypes` still
receive the per-edge SVG wrapper. A shared-layer fallback would therefore own
keyboard/multi-selection, hit paths, marker definitions, visibility and z-order
integration, beyond a CSS fix; it requires explicit review before implementation.
No broad renderer or CSS override was implemented during this investigation.

Remaining: measured residual native interaction fix, focused 2k acceptance,
consolidated Docker build, parent scan and real E2E (including the later diff fix).
Everything below is historical and does not supersede this checkpoint.

## Historical Status (2026-09-02)

Recorded 2026-09-02 for the earlier c3698e image, superseded by the status above.

- Unit/RTL: 18/18 passed. TypeScript build and ESLint passed.
- Final Docker-image bundle: 6/6 functional Playwright scenarios passed.
- Final Docker-image bundle: both 1,000-node / 2,000-edge scenarios completed
  layout, geometry, screenshots, and controls, but **failed the strict 1,500 ms
  heartbeat-delay gate**. Do not describe this final performance run as green.
- An earlier production-bundle run passed all 8 scenarios. Timings varied
  substantially between runs; host contention is a plausible contributor, not
  an established sole cause. No timing limits were increased to make tests pass.

## Historical Image Measurements

Chrome 152.0.7977.65 on Windows, one browser worker, production assets extracted
from the rebuilt Docker image and served locally by Vite preview. These are
single-run observations, not percentiles or a throughput/SLA guarantee.

| Measurement                           | Desktop 1440 x 960 | Mobile 390 x 844 |
| ------------------------------------- | -----------------: | ---------------: |
| Navigation to rendered graph          |           20.034 s |         19.464 s |
| ELK worker lifetime to result         |           14.182 s |         13.524 s |
| Longest main-thread task              |           1,437 ms |         1,183 ms |
| Maximum 100 ms heartbeat delay        |       **2,361 ms** |     **2,195 ms** |
| Slowest measured control sequence     |           2,318 ms |         2,159 ms |
| Node-list opening                     |             770 ms |           827 ms |
| Nodes rendered at overview zoom       |                233 |               77 |
| Valid, nonoverlapping saved positions |              1,000 |            1,000 |
| Renderer JS heap after graph          |           35.0 MiB |         27.3 MiB |
| Renderer JS heap after list/controls  |           16.4 MiB |         33.2 MiB |

The fixture is a connected 20-layer graph with 50 nodes per layer, 1,900
next-layer edges, and 100 skip-layer edges. All 1,000 nodes and 2,000 edges enter
the real API client, Zod validation, ELK worker, and React Flow. Viewport culling
limits DOM work; it does not delete graph data. Evidence is not prefetched.
The list displays 50 rows per page, with all 20 pages accessible.

Unchanged acceptance limits: ready within 35 seconds; individual long tasks and
heartbeat delays below 1,500 ms; measured control sequences below 3,000 ms.
Final failures were the heartbeat gate only. Zoom, pan, lock/unlock, minimap,
filter controls during layout, layout persistence, list pagination, finite
coordinates, nonoverlap, and page overflow checks passed.

## Fixes And Earlier Measurements

The initial network-simplex layout exceeded the existing 30-second worker
timeout. Large projections now use ELK LONGEST_PATH layering, BRANDES_KOEPF
placement, and thoroughness 1, at 500 nodes or 1,000 edges. Small-map settings are
unchanged. These are supported [ELK layout options](https://eclipse.dev/elk/reference/algorithms/org-eclipse-elk-layered.html).

The first successful layout exposed a 1,914 ms task / 2,719 ms heartbeat delay
on desktop and a mobile minimap that covered its own toggle. Subsequent fixes:

- Compact node content and defer edge labels below 0.45 zoom for large maps;
  full details return when zoomed in, with unchanged node dimensions and labels.
- Stabilize node data and the layout worker lifecycle; fit known ELK dimensions
  before mounting nodes, avoiding a second initial rendering pass.
- Page the node list at 50 rows, and preserve readable mobile columns through
  scrolling inside the table panel.
- Move the minimap above the bottom control bar; verify nonintersecting bounds.

Before the final mobile table-width presentation adjustment, the production
bundle passed 8/8 browser scenarios at 16:28 UTC:

| Measurement             |  Desktop |  Mobile |
| ----------------------- | -------: | ------: |
| Graph ready             |  7.315 s | 7.503 s |
| Worker to result        |  4.863 s | 5.303 s |
| Longest task            |   692 ms |  567 ms |
| Maximum heartbeat delay | 1,007 ms |  921 ms |

The final image run at 16:38-16:40 UTC is the authoritative final result above;
the faster earlier run is retained for comparison, not substituted for it.

## Caveats

- Mobile is viewport/touch emulation on the same desktop CPU, not a physical
  phone. No explicit CPU or network throttle; the host was not isolated from
  other work. A quiet-host repeated run is still needed for stable acceptance.
- Worker time includes module loading and message transfer, not just ELK CPU.
  Ready time includes navigation, API interception, rendering, and filter-control
  exercise while layout runs. Control timings include Playwright actionability
  and animation-frame waits, not instrumented input-event latency percentiles.
- CDP JavaScript heap samples exclude worker heaps, process RSS, GPU memory,
  and native allocations. No forced GC was used; detached DOM awaiting GC can
  affect samples. These figures are neither total memory nor a leak/soak test.
- This is test-only synthetic data, not analyzer or backend acceptance. The
  2,000-node interaction target and 10,000-node progressive-loading benchmark
  remain unverified. No 60 FPS claim is made.

## Image And Reproduction

Measured tag: `semanticmap/frontend:0.1.0`.
Measured image ID: `sha256:c3698e14136450c2082e557ae95d50b9b005101fb66b758dd3618bfa88a80326`.
Runtime: `nginx:1.30.4-alpine3.24-slim`, pinned to the official multi-platform
digest `sha256:ddde39c6e51f02fde7410c2e9c234cf2d0a4c7bdbbe176aeb37d8ad7ab4eb58c`.
Version selection was checked against [official image metadata](https://raw.githubusercontent.com/docker-library/official-images/master/library/nginx)
and the [nginx stable download](https://nginx.org/en/download.html).
The isolated `nginx -t` and healthcheck executable check passed. Vulnerability
scanning is owned by the parent; this report makes no zero-vulnerability claim.
The existing running frontend container was not restarted.

```sh
cd frontend
pnpm test
pnpm exec playwright test e2e/workspace.spec.ts
pnpm exec playwright test e2e/large-graph.spec.ts
```

For production measurements, build first, run `pnpm preview --port 5174`, and set
`PLAYWRIGHT_BASE_URL=http://127.0.0.1:5174`. This run used
`pnpm preview --outDir output/docker-dist --port 5174`, where `output/docker-dist`
was copied from the final image's `/usr/share/nginx/html` using a stopped temporary
container. That container was removed. Test code and fixtures do not ship in the
runtime bundle. The broad `pnpm test:e2e` command includes both functional and
strict performance cases, so machine-dependent performance failures remain visible.

Artifacts (generated locally; ignored by source control):

- [Desktop metrics](output/playwright/large-graph-desktop.json)
- [Mobile metrics](output/playwright/large-graph-mobile.json)
- [Desktop overview](output/playwright/large-graph-desktop.png)
- [Mobile overview](output/playwright/large-graph-mobile.png)
- [Desktop detail](output/playwright/large-graph-detail-desktop.png)
- [Mobile detail](output/playwright/large-graph-detail-mobile.png)
- [Desktop list](output/playwright/large-graph-list-desktop.png)
- [Mobile list](output/playwright/large-graph-list-mobile.png)

## Runtime Security Rebuild

Rebuilt 2026-09-02 as `semanticmap/frontend:0.1.0`, image ID
`sha256:7cf030da2e4b7093769797eafb2967dca5b84c4d6b872f68810ac9de5ddea31c`.
The parent scan's 18 HIGH/CRITICAL package matches were all for `libcrypto3`
and `libssl3` at `3.5.7-r0`, with fixes at `3.5.8-r0`. The runtime now runs
`apk upgrade --no-cache`; offline inventory confirms both at `3.5.8-r0`.
The official base digest remains pinned. Its main nginx configuration is copied
back after the upgrade because Alpine's replacement package changes the include
context and otherwise breaks the existing site configuration.

Verification passed: `nginx -t`; HTTP 200 for the index, SPA route, application
script, and ELK worker; and the built-in container healthcheck after startup.
All 32 application files are SHA-256-identical to the measured image. The only
static-tree difference is removal of nginx's unused stock `50x.html` page.
The isolated, network-disconnected smoke container was removed. The parent's
running stack was not changed.

No UI, ELK, API contract, or performance-limit changes were made for this rebuild.
The measurements and failed heartbeat gates above remain authoritative; no
performance rerun was performed. Parent-owned rescan and container recreation
remain required. Package verification is not a zero-vulnerability claim.
