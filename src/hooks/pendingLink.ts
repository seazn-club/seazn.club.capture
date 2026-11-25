import type { Mode } from '@/domain/mode/Mode';

/**
 * An opened Open in app link waiting for Home (spec 2026-11-25 §6), as the text a scan of the same code would have read.
 * `raw` carries a credential and is `''` for a link the app could not read, which Home shows as the foreign panel a bad
 * scan gets. `mode` is the tile a scan would have been tapped from.
 */
export type PendingLink = { readonly mode: Mode; readonly raw: string };

/**
 * The one slot a link waits in between the root hook and Home: in memory only, never stored, never logged. A later link
 * replaces an earlier one that nobody has taken (the operator tapped twice; the last is the one they mean), and it is
 * taken exactly once. `subscribe` tells Home a link arrived while it was already up.
 */
export type PendingLinkSlot = {
  put(link: PendingLink): void;
  take(): PendingLink | null;
  subscribe(listener: () => void): () => void;
  /** Empties the slot, for tests and for a restart of the root navigator. */
  clear(): void;
};

export function createPendingLink(): PendingLinkSlot {
  let slot: PendingLink | null = null;
  const listeners = new Set<() => void>();
  return {
    put: (link) => {
      slot = link;
      for (const listener of [...listeners]) listener();
    },
    take: () => {
      const link = slot;
      slot = null;
      return link;
    },
    subscribe: (listener) => {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
    clear: () => {
      slot = null;
    },
  };
}

/** The app's one slot: the root hook puts, Home takes. A module value, since both are hooks with no shared parent state. */
export const pendingLink: PendingLinkSlot = createPendingLink();
