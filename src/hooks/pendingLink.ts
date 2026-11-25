import type { Mode } from '@/domain/mode/Mode';

/**
 * An opened Open in app link waiting for Home (spec 2026-11-25 §6), as the text a scan of the same code would have read.
 * `raw` carries a credential and is `''` for a link the app could not read, which Home shows as the foreign panel a bad
 * scan gets. `mode` is the tile a scan would have been tapped from.
 */
export type PendingLink = { readonly mode: Mode; readonly raw: string };

/** A link nobody took in this long is stale: the operator has moved on, and an old link never acts by itself. */
export const LINK_TTL_MS = 10_000;

/**
 * The one slot a link waits in between the root hook and Home: in memory only, never stored, never logged. A later link
 * replaces an earlier one that nobody has taken (the operator tapped twice; the last is the one they mean), and it is
 * taken exactly once, and only within `LINK_TTL_MS` of arriving. `subscribe` tells Home a link arrived while it was
 * already up.
 *
 * A link also claims the foreground return it causes: Android fires the `url` event from `onNewIntent`, before
 * `AppState` says `active`, so without the claim the reopen gate would settle that return, see a paired phone and send
 * the app back to Live Stream. `claimsReturn` is asked on each return (as `ScanFlight.appReturned` is) and is true once.
 */
export type PendingLinkSlot = {
  put(link: PendingLink): void;
  take(): PendingLink | null;
  /** True once, for the first foreground return within `LINK_TTL_MS` of a link arriving: the gate skips that return. */
  claimsReturn(): boolean;
  subscribe(listener: () => void): () => void;
  /** Empties the slot and the claim: the app moved on its own (the gate went somewhere, a broadcast was held). */
  clear(): void;
};

export function createPendingLink(now: () => number = () => Date.now()): PendingLinkSlot {
  let slot: { readonly link: PendingLink; readonly at: number } | null = null;
  let claimedAt: number | null = null;
  const listeners = new Set<() => void>();
  return {
    put: (link) => {
      slot = { link, at: now() };
      claimedAt = slot.at;
      for (const listener of [...listeners]) listener();
    },
    take: () => {
      const held = slot;
      slot = null;
      return held !== null && now() - held.at <= LINK_TTL_MS ? held.link : null;
    },
    claimsReturn: () => {
      const at = claimedAt;
      claimedAt = null;
      return at !== null && now() - at <= LINK_TTL_MS;
    },
    subscribe: (listener) => {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
    clear: () => {
      slot = null;
      claimedAt = null;
    },
  };
}

/** The app's one slot: the root hook puts, Home takes. A module value, since both are hooks with no shared parent state. */
export const pendingLink: PendingLinkSlot = createPendingLink();
