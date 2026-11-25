import type { IncomingLinkPort } from '@/services/devicePorts';

export type FakeIncomingLink = IncomingLinkPort & {
  /** A link arrives, as the phone would hand it over: every current subscriber hears it. */
  deliver(url: string): void;
  /** How many listeners are subscribed now. */
  readonly listeners: number;
};

/** A laptop's incoming links: nothing arrives until a test `deliver`s one, and nothing is deduplicated. */
export function createFakeIncomingLink(): FakeIncomingLink {
  const listeners = new Set<(url: string) => void>();
  return {
    subscribe: (listener) => {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
    deliver: (url) => {
      for (const listener of [...listeners]) listener(url);
    },
    get listeners() {
      return listeners.size;
    },
  };
}
