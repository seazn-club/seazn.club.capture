import { useCallback, useEffect, useRef, useState } from 'react';
import { usePorts } from '@/hooks/usePorts';
import { useTurnCardShowing } from '@/hooks/useTurnCard';

/** Owner's call, 2026-09-12 (AGENTS §6): one length for Go live and Stop. */
export const HOLD_MS = 3000;

export type Hold = { readonly holding: boolean; pressIn(): void; pressOut(): void };

/**
 * A press held for HOLD_MS acts once, when the finger LIFTS (owner, 2026-11-25,
 * option A: acts on release after a full hold). At HOLD_MS the hold is only
 * marked full and the fill stays full; the act waits for the release. Acting
 * under the finger unmounted the control (Stop) with the touch still down, so
 * React Native's responder went stale and swallowed the next tap on Home.
 * Once full, a release always acts: a completed hold cannot be cancelled by
 * lifting. A release before then, disabling, unmounting, the turn card
 * covering it or the app leaving the foreground, while running or full,
 * abandons it with no act; a second press while one is running or full is
 * ignored, and a second release acts nothing, so it can never act twice. The
 * hold does what was pressed: the `onHeld` of the render the finger landed on.
 *
 * Leaving the foreground (a call, the lock, Home) cancels at once, in the
 * port's own callback (ruling M5): Android keeps JS timers paused while away,
 * so a pending hold would otherwise be marked full on the return with no
 * finger down, and a stale release could act.
 */
export function useHold(onHeld: () => void, enabledByOwner: boolean): Hold {
  // Ruling N4: under the turn card nothing acts, as if disabled. Read on every
  // render, never behind `&&`: a skipped hook changes the order (the phone's
  // "change in the order of Hooks called by HoldAction", 2026-10-05).
  const turnCard = useTurnCardShowing();
  const enabled = enabledByOwner && !turnCard;
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const phase = useRef<'idle' | 'running' | 'full'>('idle');
  const pressed = useRef<(() => void) | null>(null);
  const [holding, setHolding] = useState(false);
  const cancel = useCallback(() => {
    if (timer.current !== null) clearTimeout(timer.current);
    timer.current = null;
    phase.current = 'idle';
    setHolding(false);
  }, []);
  const pressIn = useCallback(() => {
    if (!enabled || phase.current !== 'idle') return;
    phase.current = 'running';
    pressed.current = onHeld;
    setHolding(true);
    timer.current = setTimeout(() => {
      timer.current = null;
      phase.current = 'full';
    }, HOLD_MS);
  }, [enabled, onHeld]);
  const pressOut = useCallback(() => {
    const act = phase.current === 'full' ? pressed.current : null;
    cancel();
    act?.();
  }, [cancel]);
  useAbandon(enabled, cancel);
  return { holding, pressIn, pressOut };
}

/** Abandons a hold once disabled, the moment the app leaves (M5), and on unmount. */
function useAbandon(enabled: boolean, cancel: () => void): void {
  const { foreground } = usePorts();
  useEffect(() => {
    if (!enabled) cancel();
  }, [enabled, cancel]);
  useEffect(() => foreground.subscribeBackground(cancel), [foreground, cancel]);
  useEffect(() => cancel, [cancel]);
}
