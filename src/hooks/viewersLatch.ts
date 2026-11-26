import { useCallback, useSyncExternalStore } from 'react';
import { latchViewers, NO_LATCH, type ViewersLatch } from '@/domain/session/viewersConfirmed';
import type { CaptureEnginePort } from '@/engine/CaptureEnginePort';
import { viewersFactsOf } from '@/hooks/engineSelectors';
import { useEngine } from '@/hooks/useCaptureEngine';

/**
 * The viewers' picture latch (owner, 2026-11-26), folded over every snapshot native sends: `latchViewers` is the rule,
 * this only holds its last value. One per engine, born with the engine at the composition root (`chooseEngine`) and
 * subscribed for the engine's life, so a
 * confirmation seen while no screen is drawn (Settings over the viewfinder, a locked phone) is still kept, and the
 * viewfinder and Settings' plate read one latch. The fold is idempotent, so a read folds the current snapshot too and
 * never trails the listener it races.
 *
 * Not a second state machine (AGENTS §2): native's state and both facts are untouched; this remembers, per broadcast
 * sid, that native once reported one of them. A process death forgets it along with native's own session.
 */
const LATCHES = new WeakMap<CaptureEnginePort, () => ViewersLatch>();

export function viewersLatchOf(engine: CaptureEnginePort): () => ViewersLatch {
  const held = LATCHES.get(engine);
  if (held !== undefined) return held;
  let latch = NO_LATCH;
  const read = (): ViewersLatch => {
    latch = latchViewers(latch, viewersFactsOf(engine.getSnapshot()));
    return latch;
  };
  engine.subscribe(read);
  read();
  LATCHES.set(engine, read);
  return read;
}

/** Whether the held broadcast's viewers' picture was confirmed: a boolean, so a 1 Hz tick re-renders nobody. */
export function useViewersConfirmed(): boolean {
  const engine = useEngine();
  const read = viewersLatchOf(engine);
  const confirmed = useCallback(() => read().confirmed, [read]);
  return useSyncExternalStore(engine.subscribe, confirmed);
}

/** Whether the latch first saw the held broadcast past the 60 s mark (`ViewersLatch.inherited`). A boolean. */
export function useViewersInherited(): boolean {
  const engine = useEngine();
  const read = viewersLatchOf(engine);
  const inherited = useCallback(() => read().inherited, [read]);
  return useSyncExternalStore(engine.subscribe, inherited);
}
