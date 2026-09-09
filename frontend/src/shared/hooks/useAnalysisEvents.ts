import { useEffect, useState } from 'react';
import { useQueryClient } from '@tanstack/react-query';
import { eventSchema } from '../api/schemas';
import type { AnalysisEvent } from '../api/types';
import { SseParser } from '../lib/sse';
import { authHeaders } from '../lib/auth';

export function useAnalysisEvents(runId: string, active: boolean) {
  const client = useQueryClient();
  const [events, setEvents] = useState<AnalysisEvent[]>([]);
  const [connection, setConnection] = useState<
    'connecting' | 'live' | 'reconnecting' | 'invalid' | 'closed'
  >('connecting');
  useEffect(() => {
    setEvents([]);
  }, [runId]);
  useEffect(() => {
    if (!active) {
      setConnection('closed');
      return;
    }
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;
    let lastId = '';
    let lastSequence = -1;
    let attempt = 0;
    let lastRefresh = 0;
    setConnection('connecting');
    const refresh = () => {
      void client.invalidateQueries({ queryKey: ['run', runId] });
      void client.invalidateQueries({ queryKey: ['runs'] });
      void client.invalidateQueries({ queryKey: ['canvas', runId] });
      void client.invalidateQueries({ queryKey: ['controllers', runId] });
    };
    const connect = async () => {
      try {
        const response = await fetch(
          `/api/v1/analysis-runs/${encodeURIComponent(runId)}/events`,
          {
            credentials: 'same-origin',
            headers: {
              Accept: 'text/event-stream',
              ...authHeaders(),
              ...(lastId ? { 'Last-Event-ID': lastId } : {}),
            },
            signal: controller.signal,
          },
        );
        if (
          !response.ok ||
          !response.body ||
          !response.headers.get('content-type')?.includes('text/event-stream')
        )
          throw new Error('Stream unavailable');
        setConnection('live');
        const reader = response.body.getReader();
        const decoder = new TextDecoder();
        const parser = new SseParser((packet) => {
          let payload: unknown;
          try {
            payload = JSON.parse(packet.data);
          } catch {
            setConnection('invalid');
            return;
          }
          const parsed = eventSchema.safeParse(payload);
          if (!parsed.success) {
            setConnection('invalid');
            return;
          }
          const event = parsed.data;
          lastId = packet.id ?? String(event.sequenceNumber);
          if (event.sequenceNumber <= lastSequence) return;
          lastSequence = event.sequenceNumber;
          attempt = 0;
          setConnection('live');
          setEvents((previous) => [...previous, event].slice(-200));
          if (
            Date.now() - lastRefresh > 1000 ||
            /analysis\.(completed|failed|cancelled)|canvas\.ready/.test(
              event.type,
            )
          ) {
            lastRefresh = Date.now();
            refresh();
          }
        });
        try {
          while (!controller.signal.aborted) {
            const { value, done } = await reader.read();
            if (done) break;
            parser.push(decoder.decode(value, { stream: true }));
          }
        } finally {
          reader.releaseLock();
        }
      } catch {
        /* Polling continues independently while the stream reconnects. */
      }
      if (!controller.signal.aborted) {
        setConnection('reconnecting');
        timer = setTimeout(
          () => void connect(),
          Math.min(15000, 1000 * 2 ** attempt++),
        );
      }
    };
    void connect();
    return () => {
      controller.abort();
      clearTimeout(timer);
    };
  }, [runId, active, client]);
  return { events, connection };
}
