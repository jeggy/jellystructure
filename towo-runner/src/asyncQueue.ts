/**
 * A minimal async push queue used as a session's `prompt` (an `AsyncIterable<SDKUserMessage>`) so
 * the underlying Claude process stays alive indefinitely, ready for more turns via push(), instead
 * of exiting once a single-shot string prompt's first turn completes.
 *
 * Confirmed live (2026-08-11) that this is necessary, not just tidier: `Query.streamInput()` called
 * from an independent async context (a WS message handler arriving seconds after the session went
 * idle) either throws "ProcessTransport is not ready for writing" or silently produces no response --
 * it only reliably works when called synchronously within the same tick as the generator's own
 * message processing, which "send a message to an idle session" can never guarantee. Streaming input
 * from the start (this queue) keeps the process genuinely alive across a real multi-second gap,
 * confirmed with a real Claude call landing after a 5s delay.
 */
export class AsyncQueue<T> {
  private items: T[] = [];
  private resolvers: ((value: IteratorResult<T>) => void)[] = [];

  push(item: T): void {
    const resolver = this.resolvers.shift();
    if (resolver) {
      resolver({ value: item, done: false });
    } else {
      this.items.push(item);
    }
  }

  async *[Symbol.asyncIterator](): AsyncGenerator<T> {
    while (true) {
      const queued = this.items.shift();
      if (queued !== undefined) {
        yield queued;
        continue;
      }
      const result = await new Promise<IteratorResult<T>>((resolve) => this.resolvers.push(resolve));
      if (result.done) return;
      yield result.value;
    }
  }
}
