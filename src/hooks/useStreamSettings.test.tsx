import { act, renderHook, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { useStreamSettings } from '@/hooks/useStreamSettings';
import { createMemoryKeyValueStore, type KeyValueStore } from '@/services/KeyValueStore';
import { createLogger } from '@/services/logger';
import { createRingRecord } from '@/services/sessionRecord';
import { createStreamSettingsStore, SETTINGS_KEY } from '@/services/streamSettingsStore';
import { createFakePorts } from '../../test/fakePorts';
import { wrapperFor } from '../../test/renderWithPorts';

/** A keystore that refuses the first write and takes every one after. */
function refusingOnce(): KeyValueStore {
  const kv = createMemoryKeyValueStore();
  let refused = false;
  return {
    get: kv.get,
    delete: kv.delete,
    set: (key, value) => {
      if (refused) return kv.set(key, value);
      refused = true;
      return Promise.reject(new Error('keystore locked'));
    },
  };
}

describe('useStreamSettings (D23)', () => {
  it('starts on the defaults, then shows what was saved once it loads', async () => {
    const fakes = createFakePorts({
      kvSeed: { [SETTINGS_KEY]: '{"v":3,"side":"left","automatic":false}' },
    });
    const { result } = renderHook(() => useStreamSettings(), { wrapper: wrapperFor(fakes) });
    const tuning = { matchSound: true, constantBitrate: false };
    expect(result.current.settings).toEqual({ side: 'right', automatic: true, ...tuning });
    await waitFor(() =>
      expect(result.current.settings).toEqual({ side: 'left', automatic: false, ...tuning }),
    );
    expect(result.current.saveFailed).toBe(false);
  });

  it('applies a change at once and saves it, with nothing failed', async () => {
    const fakes = createFakePorts();
    const { result } = renderHook(() => useStreamSettings(), { wrapper: wrapperFor(fakes) });
    act(() => result.current.change({ side: 'left' }));
    expect(result.current.settings.side).toBe('left');
    await waitFor(() =>
      expect(fakes.kv.entries.get(SETTINGS_KEY)).toBe(
        '{"v":4,"side":"left","automatic":true,"matchSound":true,"constantBitrate":false}',
      ),
    );
    expect(result.current.saveFailed).toBe(false);
  });

  // Spec 2026-11-24-capture-tuning §2: constant bitrate is off from Arming on, so the screens read whether one is held.
  it('says whether a broadcast is held, from Arming on, and none again once it is over', () => {
    const fakes = createFakePorts();
    const { result } = renderHook(() => useStreamSettings(), { wrapper: wrapperFor(fakes) });
    expect(result.current.held).toBe(false);
    act(() => fakes.engine.scene('arming-retrying'));
    expect(result.current.held).toBe(true);
    act(() => fakes.engine.scene('live'));
    expect(result.current.held).toBe(true);
    act(() => fakes.engine.scene('stopped'));
    expect(result.current.held).toBe(false);
  });

  it('says a refused change held for this session only, until a later one saves', async () => {
    const record = createRingRecord();
    const streamSettings = createStreamSettingsStore(
      refusingOnce(),
      createLogger({ record, now: () => 0 }),
    );
    const fakes = createFakePorts({ streamSettings });
    const { result } = renderHook(() => useStreamSettings(), { wrapper: wrapperFor(fakes) });
    act(() => result.current.change({ automatic: false }));
    await waitFor(() => expect(result.current.saveFailed).toBe(true));
    expect(result.current.settings.automatic).toBe(false);
    act(() => result.current.change({ side: 'left' }));
    await waitFor(() => expect(result.current.saveFailed).toBe(false));
  });

  it('reads the saved settings once, even when the effect runs twice (StrictMode)', async () => {
    const fakes = createFakePorts();
    const get = vi.spyOn(fakes.kv, 'get');
    renderHook(() => useStreamSettings(), { wrapper: wrapperFor(fakes), reactStrictMode: true });
    await waitFor(() => expect(get).toHaveBeenCalledWith(SETTINGS_KEY));
    expect(get.mock.calls.filter(([key]) => key === SETTINGS_KEY)).toHaveLength(1);
  });
});
