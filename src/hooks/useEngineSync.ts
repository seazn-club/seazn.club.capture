import { useEffect, useRef, useState } from 'react';
import { selectBroadcastHeld, selectEngineStatus } from '@/hooks/engineSelectors';
import { useLanguage } from '@/hooks/useLanguage';
import { usePorts } from '@/hooks/usePorts';

/**
 * Keeps native's copy of the operator's choices equal to JS's (amendment §2;
 * P3): the automatic switch (A4), the notification's language, and the capture
 * choices (spec 2026-11-24-capture-tuning §2). A
 * reconciler, not a relay: it compares the snapshot's `pairing.automatic`
 * with the store on every change of either, so a `pair` sent before the store
 * loaded, or a switch flipped while a snapshot was in flight, still converges.
 */
export function useEngineSync(): void {
  const loaded = useStreamSettingsLoaded();
  useAutomaticSync(loaded);
  useTuningSync(loaded);
  useLanguageSync();
}

/** Before the read the store holds the defaults: sending against them would undo native's copy of a saved choice. */
function useStreamSettingsLoaded(): boolean {
  const { streamSettings } = usePorts();
  const [loaded, setLoaded] = useState(false);
  useEffect(() => {
    let alive = true;
    void streamSettings.load().then(() => alive && setLoaded(true));
    return () => {
      alive = false;
    };
  }, [streamSettings]);
  return loaded;
}

/**
 * Native ignores `setAutomatic` only in Ended, which `sync` skips, so an
 * unequal pair can never send on every snapshot: one intent per change.
 */
function useAutomaticSync(loaded: boolean): void {
  const { engine, streamSettings, logger } = usePorts();
  useEffect(() => {
    if (!loaded) return undefined;
    const sync = () => {
      const { pairing, state } = engine.getSnapshot();
      const wanted = streamSettings.getSnapshot().automatic;
      if (pairing === null || state.kind === 'ended' || pairing.automatic === wanted) return;
      logger.info('intent.setAutomatic', { action: wanted ? 'on' : 'off' });
      engine.send({ kind: 'setAutomatic', on: wanted });
    };
    const offEngine = engine.subscribe(sync);
    const offSettings = streamSettings.subscribe(sync);
    sync();
    return () => {
      offEngine();
      offSettings();
    };
  }, [loaded, engine, streamSettings, logger]);
}

/**
 * Spec 2026-11-24-capture-tuning §2. Native takes `setMatchSound` in every paired phase but Ended, on air included;
 * it ignores `setConstantBitrate` while a broadcast is held, so that one waits here until none is (Settings says
 * "After the broadcast"). Either way an unequal pair never sends on every snapshot: one intent per change.
 */
function useTuningSync(loaded: boolean): void {
  const { engine, streamSettings, logger } = usePorts();
  useEffect(() => {
    if (!loaded) return undefined;
    // Each reads the snapshot afresh: a send can report back before the next one runs (the fake does, at once).
    const paired = () => {
      const snapshot = engine.getSnapshot();
      return snapshot.pairing === null || snapshot.state.kind === 'ended' ? null : snapshot;
    };
    const syncMatchSound = () => {
      const on = streamSettings.getSnapshot().matchSound;
      if ((paired()?.pairing?.matchSound ?? on) === on) return;
      logger.info('intent.setMatchSound', { action: on ? 'on' : 'off' });
      engine.send({ kind: 'setMatchSound', on });
    };
    const syncConstantBitrate = () => {
      const on = streamSettings.getSnapshot().constantBitrate;
      const held = paired();
      if (held === null || held.pairing?.constantBitrate === on || selectBroadcastHeld(held))
        return;
      logger.info('intent.setConstantBitrate', { action: on ? 'on' : 'off' });
      engine.send({ kind: 'setConstantBitrate', on });
    };
    const sync = () => {
      syncMatchSound();
      syncConstantBitrate();
    };
    const offEngine = engine.subscribe(sync);
    const offSettings = streamSettings.subscribe(sync);
    sync();
    return () => {
      offEngine();
      offSettings();
    };
  }, [loaded, engine, streamSettings, logger]);
}

/** P3, updated by the amendment: the notification follows the operator's pick while paired or live. */
function useLanguageSync(): void {
  const { engine, logger } = usePorts();
  const { lang } = useLanguage();
  // What native holds, or will hold: the next `pair` carries the current pick
  // (usePairing), so a pick made while idle is known too (review M11).
  const known = useRef<string | null>(null);
  useEffect(() => {
    const previous = known.current;
    known.current = lang;
    if (previous === null || previous === lang) return;
    const status = selectEngineStatus(engine.getSnapshot());
    if (status !== 'paired' && status !== 'live') return;
    logger.info('intent.setLanguage', { action: lang });
    engine.send({ kind: 'setLanguage', language: lang });
  }, [lang, engine, logger]);
}
