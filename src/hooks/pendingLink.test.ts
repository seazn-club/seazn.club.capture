import { describe, expect, it } from 'vitest';
import { createPendingLink, LINK_TTL_MS } from '@/hooks/pendingLink';

const LINK = { mode: 'matchday', raw: '' } as const;

describe('pendingLink', () => {
  it('hands a link over once, and the next put replaces one nobody took', () => {
    const slot = createPendingLink(() => 0);
    slot.put({ mode: 'stream', raw: '' });
    slot.put(LINK);
    expect(slot.take()).toEqual(LINK);
    expect(slot.take()).toBeNull();
  });

  it('drops a link that waited more than 10 s, and keeps one at exactly 10 s', () => {
    let now = 0;
    const slot = createPendingLink(() => now);
    slot.put(LINK);
    now = LINK_TTL_MS;
    expect(slot.take()).toEqual(LINK);
    slot.put(LINK);
    now += LINK_TTL_MS + 1;
    expect(slot.take()).toBeNull();
  });

  it('claims the first return within 10 s, once', () => {
    let now = 0;
    const slot = createPendingLink(() => now);
    expect(slot.claimsReturn()).toBe(false);
    slot.put(LINK);
    now = 5_000;
    expect(slot.claimsReturn()).toBe(true);
    expect(slot.claimsReturn()).toBe(false);
  });

  it('claims no return that comes more than 10 s after the link', () => {
    let now = 0;
    const slot = createPendingLink(() => now);
    slot.put(LINK);
    now = LINK_TTL_MS + 1;
    expect(slot.claimsReturn()).toBe(false);
  });

  it('forgets the link and the claim on clear', () => {
    const slot = createPendingLink(() => 0);
    slot.put(LINK);
    slot.clear();
    expect(slot.take()).toBeNull();
    expect(slot.claimsReturn()).toBe(false);
  });
});
