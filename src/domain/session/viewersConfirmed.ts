/**
 * Red LIVE means viewers can see the picture (owner, 2026-11-26: "build"; the realtime nudge spec §3a). When the
 * phone's upload connects, the plate reads GOING LIVE until something confirms the viewers' picture: the server's
 * programme phase `live` (capture-beat.v1's `delivery` facts) or the phone's own playlist check seeing new frames
 * (native's `delivery: ok`, DeliveryWatch), whichever comes first. Unconfirmed 60 s after the upload connected, the
 * plate reads LIVE with a warning line. A broadcast with no source of confirmation reads LIVE at once, as before.
 *
 * A projection, not an authority (AGENTS §2): native owns the session and both facts; this only remembers, per
 * broadcast, that one of them was seen. Pure; the hook that folds it over native's snapshots holds the latch.
 */

/** How long after the upload connected the plate waits for a confirmation before it reads LIVE with the warning. */
export const UNCONFIRMED_AFTER_MS = 60_000;

/** What one snapshot says about the viewers' picture. */
export type ViewersFacts = {
  /** The held broadcast's sid; null with none held. */
  readonly sid: string | null;
  /** The upload is connected and frames advance: native's publishing or degraded. */
  readonly onAir: boolean;
  /** The server's programme phase is `live`. */
  readonly programmeLive: boolean;
  /** The phone's playlist check saw the viewers' playlist move (`delivery: ok`). */
  readonly playlistFresh: boolean;
};

/** The broadcast the latch is about, and whether its viewers' picture was ever confirmed. */
export type ViewersLatch = { readonly sid: string | null; readonly confirmed: boolean };

export const NO_LATCH: ViewersLatch = { sid: null, confirmed: false };

const confirms = (facts: ViewersFacts): boolean =>
  facts.onAir && (facts.programmeLive || facts.playlistFresh);

/**
 * The latch, one step. Confirmed stays confirmed for the broadcast: a later frozen or reconnecting programme is the
 * existing Degraded and Reconnecting machinery's, not this rule's. A new sid, or none, starts again. Unchanged facts
 * return the same latch, so a reader comparing by identity sees no change.
 */
export function latchViewers(prev: ViewersLatch, facts: ViewersFacts): ViewersLatch {
  const kept = prev.sid === facts.sid && prev.confirmed;
  const confirmed = kept || confirms(facts);
  return prev.sid === facts.sid && prev.confirmed === confirmed
    ? prev
    : { sid: facts.sid, confirmed };
}

/** What the plate and the line show of a broadcast that is going out: Going live, LIVE, or LIVE with the warning. */
export type AirStage = 'goingLive' | 'live' | 'unconfirmed';

export type AirStageInput = {
  /** The upload is connected and frames advance (publishing or degraded): the only time the stage is not LIVE. */
  readonly onAir: boolean;
  readonly confirmed: boolean;
  /** Something could confirm this broadcast: a playback URL to check, or the server's programme phase. */
  readonly hasSource: boolean;
  /** The upload connected {@link UNCONFIRMED_AFTER_MS} or more ago ({@link pastConfirmWindow}). */
  readonly late: boolean;
};

/**
 * Whether the time since the upload connected (native's `sinceEpochMs` to its report; null off air) has run past the
 * wait. A boolean, so a reader re-renders once when it turns, never every second (AGENTS §8).
 */
export const pastConfirmWindow = (onAirMs: number | null): boolean =>
  onAirMs !== null && onAirMs >= UNCONFIRMED_AFTER_MS;

/** Owner, 2026-11-26: confirmed, or nothing that could confirm it, is LIVE; unconfirmed is Going live for 60 s. */
export function airStage({ onAir, confirmed, hasSource, late }: AirStageInput): AirStage {
  if (!onAir || confirmed || !hasSource) return 'live';
  return late ? 'unconfirmed' : 'goingLive';
}
