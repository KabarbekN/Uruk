export const NODE_INSTALL_BATCH = 128;
export const EDGE_INSTALL_BATCH = 256;

export function installGraph<N, E>(
  nodes: N[],
  edges: E[],
  appendNodes: (nodes: N[]) => void,
  appendEdges: (edges: E[]) => void,
  complete: () => void,
): () => void {
  if (nodes.length < 500 && edges.length < 1000) {
    appendNodes(nodes);
    appendEdges(edges);
    complete();
    return () => {};
  }
  let cancelled = false;
  let nodeOffset = 0;
  let edgeOffset = 0;
  let frame: number | undefined;
  let timer: ReturnType<typeof setTimeout> | undefined;
  const next = () => {
    // The timer runs after the animation frame, allowing React commits to paint.
    frame = requestAnimationFrame(() => {
      frame = undefined;
      timer = setTimeout(step, 0);
    });
  };
  const step = () => {
    timer = undefined;
    if (cancelled) return;
    if (nodeOffset < nodes.length) {
      appendNodes(nodes.slice(nodeOffset, nodeOffset + NODE_INSTALL_BATCH));
      nodeOffset += NODE_INSTALL_BATCH;
      next();
    } else if (edgeOffset < edges.length) {
      appendEdges(edges.slice(edgeOffset, edgeOffset + EDGE_INSTALL_BATCH));
      edgeOffset += EDGE_INSTALL_BATCH;
      next();
    } else complete();
  };
  step();
  return () => {
    cancelled = true;
    if (frame !== undefined) cancelAnimationFrame(frame);
    clearTimeout(timer);
  };
}
