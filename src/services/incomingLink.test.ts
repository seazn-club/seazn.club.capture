import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const linking = vi.hoisted(() => ({
  initial: vi.fn<() => Promise<string | null>>(),
  handlers: new Set<(event: { url: string }) => void>(),
}));

vi.mock('expo-linking', () => ({
  getInitialURL: () => linking.initial(),
  addEventListener: (_type: 'url', handler: (event: { url: string }) => void) => {
    linking.handlers.add(handler);
    return { remove: () => linking.handlers.delete(handler) };
  },
}));

import { createIncomingLink } from '@/services/incomingLink';

const URL_A = 'https://stg.seazn.club/app/day#md_fake';
const URL_B = 'https://stg.seazn.club/app/score#dl_fake';
const emit = (url: string) => linking.handlers.forEach((h) => h({ url }));
const flush = () => new Promise<void>((resolve) => setTimeout(resolve, 0));

describe('createIncomingLink', () => {
  let nowMs = 1_000;
  const port = () => createIncomingLink({ now: () => nowMs });

  beforeEach(() => {
    nowMs = 1_000;
    linking.handlers.clear();
    linking.initial.mockReset();
    linking.initial.mockResolvedValue(null);
  });
  afterEach(() => vi.restoreAllMocks());

  it('delivers the initial URL once, after subscribe', async () => {
    linking.initial.mockResolvedValue(URL_A);
    const heard: string[] = [];
    port().subscribe((url) => heard.push(url));
    expect(heard).toEqual([]);
    await flush();
    expect(heard).toEqual([URL_A]);
  });

  it('delivers a later event', async () => {
    const heard: string[] = [];
    port().subscribe((url) => heard.push(url));
    await flush();
    emit(URL_B);
    expect(heard).toEqual([URL_B]);
  });

  it('delivers the initial URL plus the same event within 2 s once', async () => {
    linking.initial.mockResolvedValue(URL_A);
    const heard: string[] = [];
    port().subscribe((url) => heard.push(url));
    await flush();
    nowMs += 1_999;
    emit(URL_A);
    expect(heard).toEqual([URL_A]);
  });

  it('delivers the same URL again after 2 s, and a different one at once', async () => {
    const heard: string[] = [];
    port().subscribe((url) => heard.push(url));
    await flush();
    emit(URL_A);
    emit(URL_B);
    nowMs += 2_000;
    emit(URL_A);
    expect(heard).toEqual([URL_A, URL_B, URL_A]);
  });

  it('removes its listener on unsubscribe, and stays silent if the initial URL arrives after it', async () => {
    let resolveInitial: (url: string | null) => void = () => undefined;
    linking.initial.mockReturnValue(new Promise((resolve) => (resolveInitial = resolve)));
    const heard: string[] = [];
    const off = port().subscribe((url) => heard.push(url));
    expect(linking.handlers.size).toBe(1);
    off();
    expect(linking.handlers.size).toBe(0);
    resolveInitial(URL_A);
    await flush();
    emit(URL_B);
    expect(heard).toEqual([]);
  });

  it('delivers nothing and never throws when the initial URL read rejects', async () => {
    linking.initial.mockRejectedValue(new Error('no activity'));
    const heard: string[] = [];
    port().subscribe((url) => heard.push(url));
    await flush();
    emit(URL_B);
    expect(heard).toEqual([URL_B]);
  });

  it('delivers nothing for an empty initial URL', async () => {
    linking.initial.mockResolvedValue('');
    const heard: string[] = [];
    port().subscribe((url) => heard.push(url));
    await flush();
    expect(heard).toEqual([]);
  });

  it('does not hand the initial URL to a second subscriber of the same port (a remounted hook)', async () => {
    linking.initial.mockResolvedValue(URL_A);
    const link = port();
    const first: string[] = [];
    const off = link.subscribe((url) => first.push(url));
    await flush();
    off();
    nowMs += 60_000;
    const second: string[] = [];
    link.subscribe((url) => second.push(url));
    await flush();
    expect(first).toEqual([URL_A]);
    expect(second).toEqual([]);
  });

  it('keeps the initial URL for the subscriber that stays (an effect that runs twice)', async () => {
    linking.initial.mockResolvedValue(URL_A);
    const link = port();
    const heard: string[] = [];
    link.subscribe((url) => heard.push(url))();
    link.subscribe((url) => heard.push(url));
    await flush();
    expect(heard).toEqual([URL_A]);
  });
});
