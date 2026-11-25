import { useEffect } from 'react';
import { unwrapLink } from '@/domain/mode/openLink';
import { selectBroadcastHeld } from '@/hooks/engineSelectors';
import { pendingLink, type PendingLink } from '@/hooks/pendingLink';
import { usePorts } from '@/hooks/usePorts';

/**
 * Open in app (spec 2026-11-25 §6): a link tapped in the browser is a scan that arrives on its own. This is the root's
 * half. It is mounted once the reopen gate has settled, so a cold start's link waits for the gate and is handled once.
 *
 * - A broadcast held (Arming or live): the link is recorded as `held` and ignored. It never navigates, never parks.
 * - Anything else: the app goes Home and parks the link's text for Home's own scan handling (`usePendingLink`), which
 *   is where a scan lands. `raw` is `''` for a link it could not read, which Home shows as the foreign panel.
 *
 * Nothing here pairs, goes live or scores. The record gets `link-opened {mode, state}` and never the URL, the fragment
 * or the token (spec §6; AGENTS §1).
 */
export function useIncomingLink(): void {
  const { incomingLink, hosts, engine, navigation, logger } = usePorts();
  useEffect(
    () =>
      incomingLink.subscribe((url) => {
        const link = unwrapLink(url, hosts);
        const mode = link.mode ?? link.named;
        const record = (state: 'handled' | 'held' | 'foreign') =>
          logger.info('link-opened', mode === null ? { state } : { mode, state });
        if (selectBroadcastHeld(engine.getSnapshot())) return record('held');
        const parked: PendingLink =
          link.mode === null
            ? { mode: link.named ?? 'stream', raw: '' }
            : { mode: link.mode, raw: link.raw };
        record(link.mode === null ? 'foreign' : 'handled');
        // Home first: a mounted Home takes the link the moment it is parked.
        navigation.go('home');
        pendingLink.put(parked);
      }),
    [incomingLink, hosts, engine, navigation, logger],
  );
}
