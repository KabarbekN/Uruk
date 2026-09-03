export type StreamPacket = { event: string; data: string; id?: string };

// Lines may be split across arbitrary transport chunks, including CRLF boundaries.
export class SseParser {
  private buffer = '';
  private data: string[] = [];
  private event = 'message';
  private id: string | undefined;
  constructor(private onPacket: (packet: StreamPacket) => void) {}
  push(chunk: string) {
    this.buffer += chunk;
    let newline: number;
    while ((newline = this.buffer.indexOf('\n')) !== -1) {
      const line = this.buffer.slice(0, newline).replace(/\r$/, '');
      this.buffer = this.buffer.slice(newline + 1);
      this.line(line);
    }
  }
  private line(line: string) {
    if (line === '') {
      if (this.data.length)
        this.onPacket({
          event: this.event,
          data: this.data.join('\n'),
          id: this.id,
        });
      this.data = [];
      this.event = 'message';
      return;
    }
    if (line.startsWith(':')) return;
    const colon = line.indexOf(':');
    const field = colon < 0 ? line : line.slice(0, colon);
    const value = colon < 0 ? '' : line.slice(colon + 1).replace(/^ /, '');
    if (field === 'data') this.data.push(value);
    if (field === 'event') this.event = value;
    if (field === 'id' && !value.includes('\0')) this.id = value;
  }
}
