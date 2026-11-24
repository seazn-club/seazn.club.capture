import { RealtimeClient } from '@supabase/realtime-js';
import type { Logger } from '@/services/logger';
import { createSupabaseRealtimePort, type RealtimePort } from '@/services/realtimePort';

/**
 * The production Realtime port, which `createNativePorts` builds once (plan S2-1 Task 8): the real
 * `@supabase/realtime-js` client, never `supabase-js`. Given no transport it takes the runtime's global `WebSocket`
 * (`WebSocketFactory.getWebSocketConstructor`, which Hermes has), so nothing is polyfilled and no `ws` is bundled; a
 * runtime with none makes the library throw, which the port says and answers with `onDown`, so polling carries on.
 * The port's options (the key, `vsn` 1.0.0 so no frame needs `TextDecoder`, no idle-disconnect timer) go to the client
 * as they are. One client per subscription, because a grant's token and channel belong to one subscription. Pure, so
 * the wiring is tested, like `scoringPortFor`.
 */
export function realtimePortFor(deps: { readonly logger: Logger }): RealtimePort {
  return createSupabaseRealtimePort({
    logger: deps.logger,
    createClient: (endpoint, options) => new RealtimeClient(endpoint, options),
  });
}

/** Match Day's nudge (phone design 2026-11-24 §4): the broadcast `nudge` is the ping, and the record says `matchday.realtime`. */
export const MATCHDAY_REALTIME = { ping: 'nudge', line: 'matchday.realtime' } as const;

/**
 * Match Day's own Realtime port: a second instance, never Remote Scoring's, so each family has its own socket and its
 * own `setAuth`, and a Match Day subscription never closes the focused match's (each port holds one subscription).
 */
export function matchDayRealtimePortFor(deps: { readonly logger: Logger }): RealtimePort {
  return createSupabaseRealtimePort({
    logger: deps.logger,
    createClient: (endpoint, options) => new RealtimeClient(endpoint, options),
    family: MATCHDAY_REALTIME,
  });
}
