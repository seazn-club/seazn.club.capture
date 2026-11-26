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
  /** The upload connected {@link UNCONFIRMED_AFTER_MS} or more ago ({@link pastConfirmWindow}). */
  readonly late: boolean;
};

/**
 * The broadcast the latch is about, whether its viewers' picture was ever confirmed, and whether the latch first saw
 * it already past the 60 s mark (`inherited`): a cold-start rejoin, a JS reload or a late JS start into a broadcast
 * whose confirmation, if any, the latch never saw.
 */
export type ViewersLatch = {
  readonly sid: string | null;
  readonly confirmed: boolean;
  readonly inherited: boolean;
};

export const NO_LATCH: ViewersLatch = { sid: null, confirmed: false, inherited: false };

const confirms = (facts: ViewersFacts): boolean =>
  facts.onAir && (facts.programmeLive || facts.playlistFresh);

/**
 * The latch, one step. Confirmed stays confirmed for the broadcast: a later frozen or reconnecting programme is the
 * existing Degraded and Reconnecting machinery's, not this rule's. A new sid, or none, starts again. Unchanged facts
 * return the same latch, so a reader comparing by identity sees no change.
 */
export function latchViewers(prev: ViewersLatch, facts: ViewersFacts): ViewersLatch {
  const same = prev.sid === facts.sid;
  const confirmed = (same && prev.confirmed) || confirms(facts);
  // The controller's ruling (PR #78 review, 2026-11-26): a broadcast first seen past the mark is not warned about.
  const inherited = same ? prev.inherited : facts.sid !== null && facts.late;
  return same && prev.confirmed === confirmed && prev.inherited === inherited
    ? prev
    : { sid: facts.sid, confirmed, inherited };
}

/** What the plate and the line show of a broadcast that is going out: Going live, LIVE, or LIVE with the warning. */
export type AirStage = 'goingLive' | 'live' | 'unconfirmed';

export type AirStageInput = {
  /** The upload is connected and frames advance (publishing or degraded): the only time the stage is not LIVE. */
  readonly onAir: boolean;
  readonly confirmed: boolean;
  /** The latch first saw this broadcast past the 60 s mark (`ViewersLatch.inherited`): LIVE, with no warning. */
  readonly inherited: boolean;
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

/**
 * Owner, 2026-11-26: confirmed, or nothing that could confirm it, is LIVE; unconfirmed is Going live for 60 s. A
 * broadcast the latch first saw past the mark is LIVE with no warning (the controller's ruling, PR #78 review): the
 * latch cannot know it was never confirmed, and real trouble is the Degraded and Reconnecting machinery's.
 */
export function airStage(input: AirStageInput): AirStage {
  const { onAir, confirmed, inherited, hasSource, late } = input;
  if (!onAir || confirmed || !hasSource) return 'live';
  if (inherited) return 'live';
  return late ? 'unconfirmed' : 'goingLive';
}
