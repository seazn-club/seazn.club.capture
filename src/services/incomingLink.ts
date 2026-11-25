import { addEventListener, getInitialURL } from 'expo-linking';
import type { IncomingLinkPort } from '@/services/devicePorts';

/** The same URL inside this window is one arrival: some cold starts report the initial URL and then a `url` event. */
export const DUPLICATE_WINDOW_MS = 2_000;

/**
 * The URLs the phone is asked to open, over `expo-linking`: the one the app was started with, once, then each `url`
 * event. A URL may carry a code's token in its fragment: nothing here logs, stores or keeps it past the listener.
 *
 * The initial URL is the app's for its whole life, and `getInitialURL` answers with it every time, so it is read once
 * and given to one listener that is still subscribed when it arrives: a hook that remounts (or runs its effect twice)
 * never handles a link it already did, and never loses one.
 */
export function createIncomingLink(deps: { readonly now?: () => number } = {}): IncomingLinkPort {
  const now = deps.now ?? Date.now;
  let initial: Promise<string | null> | null = null;
  let initialDelivered = false;
  let last: { readonly url: string; readonly at: number } | null = null;
  return {
    subscribe: (listener) => {
      let open = true;
      const deliver = (url: string) => {
        if (url.length === 0) return;
        const at = now();
        if (last !== null && last.url === url && at - last.at < DUPLICATE_WINDOW_MS) return;
        last = { url, at };
        listener(url);
      };
      const subscription = addEventListener('url', (event) => deliver(event.url));
      if (!initialDelivered) {
        initial ??= getInitialURL();
        initial.then(
          (url) => {
            if (!open || initialDelivered) return;
            initialDelivered = true;
            deliver(url ?? '');
          },
          () => undefined,
        );
      }
      return () => {
        open = false;
        subscription.remove();
      };
    },
  };
}
