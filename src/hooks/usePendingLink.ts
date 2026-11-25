import { useEffect } from 'react';
import type { Mode } from '@/domain/mode/Mode';
import { pendingLink } from '@/hooks/pendingLink';
import { ignoreUnexpected, isReady, type SetPanel } from '@/hooks/useCodeCheck';
import type { SetStatusKey } from '@/hooks/useHome';
import { usePorts } from '@/hooks/usePorts';

/**
 * Home takes the link the root hook parked and handles it as a scan's result (Open in app, spec 2026-11-25 §9), under a
 * scan flight of its own as a pasted code is, so it never races a scan that is up or a code being saved: a link that
 * arrives then waits and goes on when the flight comes down (`ScanFlight.onIdle`), and the mode store must be ready,
 * as for a tile tap (R12). The slot is read when Home mounts and whenever a link arrives. Like a scan, it clears the
 * panel, the status line and the notices before it acts.
 */
export function usePendingLink(
  handleRecognised: (tapped: Mode, raw: string) => Promise<void>,
  setPanel: SetPanel,
  setStatusKey: SetStatusKey,
): void {
  const { scanFlight, modeStore, endedNotice } = usePorts();
  useEffect(() => {
    const drain = (): void => {
      if (scanFlight.active() || !isReady(modeStore)) return;
      const link = pendingLink.take();
      if (link === null) return;
      if (!scanFlight.begin()) return void pendingLink.put(link);
      setPanel(null);
      setStatusKey(null);
      endedNotice.clear();
      modeStore.dismissNotices();
      void handleRecognised(link.mode, link.raw)
        .catch(ignoreUnexpected)
        .finally(() => scanFlight.end());
    };
    drain();
    const offLink = pendingLink.subscribe(drain);
    const offFlight = scanFlight.onIdle(drain);
    const offStore = modeStore.subscribe(drain);
    return () => {
      offLink();
      offFlight();
      offStore();
    };
  }, [handleRecognised, setPanel, setStatusKey, scanFlight, modeStore, endedNotice]);
}
