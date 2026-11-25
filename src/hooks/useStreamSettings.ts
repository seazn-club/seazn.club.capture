import { useCallback, useEffect, useState, useSyncExternalStore } from 'react';
import { selectBroadcastHeld } from '@/hooks/engineSelectors';
import { useEngineSelector } from '@/hooks/useCaptureEngine';
import { usePorts } from '@/hooks/usePorts';
import type { StreamSettings } from '@/services/streamSettingsStore';

/**
 * The stream settings for the viewfinder and Settings. Loads once, on first
 * use; `saveFailed` says the last change held for this session only (D23).
 * `held` is a broadcast held, from Arming on (`selectBroadcastHeld`): constant
 * bitrate is off then (spec 2026-11-24-capture-tuning §2).
 */
export function useStreamSettings(): {
  readonly settings: StreamSettings;
  readonly saveFailed: boolean;
  readonly held: boolean;
  change(change: Partial<StreamSettings>): void;
} {
  const { streamSettings } = usePorts();
  const settings = useSyncExternalStore(streamSettings.subscribe, streamSettings.getSnapshot);
  const [saveFailed, setSaveFailed] = useState(false);
  useEffect(() => {
    void streamSettings.load();
  }, [streamSettings]);
  const change = useCallback(
    (next: Partial<StreamSettings>) => {
      // Saves settle in the order they were asked (serialSaves), so this ends on the last one.
      void streamSettings.set(next).then((result) => setSaveFailed(result === 'refused'));
    },
    [streamSettings],
  );
  const held = useEngineSelector(selectBroadcastHeld);
  return { settings, saveFailed, held, change };
}
