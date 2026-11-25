import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { matchDayRealtimePortFor, realtimePortFor } from '@/hooks/realtimePortWiring';
import { createLogger } from '@/services/logger';
import type { RealtimeGrant } from '@/services/scoringPort';
import { createRingRecord, parseRecordLine } from '@/services/sessionRecord';

// Made up (AGENTS §13). The Match Day grant as the hook hands it to the port: the door's topic as the channel.
const MD_GRANT: RealtimeGrant = {
  token: 'eyJmYWtl.eyJtZC1mYWtlLXBheWxvYWQ.TUQtc2lnbmF0dXJl',
  channel: 'match-day:3c1d2e4f-5a6b-4c7d-8e9f-0a1b2c3d4e5f',
  url: 'https://abcdwxyz.supabase.example',
  apikey: 'sb-fake-anon-key-0123456789',
};
const SCORING_GRANT: RealtimeGrant = {
  token: 'eyJmYWtl.eyJmaXh0dXJlLWZha2U.U0Mtc2lnbmF0dXJl',
  channel: 'fixture:6f0c5a52-3d1e-4a0b-8f55-0c2f6d7b9e10',
  url: 'https://abcdwxyz.supabase.example',
  apikey: 'sb-fake-anon-key-0123456789',
};
const MD_TOPIC = `realtime:${MD_GRANT.channel}`;
const SCORING_TOPIC = `realtime:${SCORING_GRANT.channel}`;

type V1Frame = {
  topic: string;
  event: string;
  payload: { access_token?: string; config?: { private: boolean } };
  ref: string | null;
  join_ref: string | null;
};

/** The runtime's WebSocket, as `realtimePortWiring.test.ts` plays it: frames kept, the server played by `receive`. */
class FakeSocket {
  static readonly made: FakeSocket[] = [];
  readyState = 0;
  binaryType = 'blob';
  bufferedAmount = 0;
  readonly sent: string[] = [];
  closed = false;
  onopen: (() => void) | null = null;
  onmessage: ((event: { data: unknown }) => void) | null = null;
  onclose: ((event: unknown) => void) | null = null;
  onerror: ((event: unknown) => void) | null = null;
  constructor(readonly url: string) {
    FakeSocket.made.push(this);
    setTimeout(() => {
      this.readyState = 1;
      this.onopen?.();
    }, 0);
  }
  send(data: string): void {
    this.sent.push(data);
  }
  close(): void {
    this.closed = true;
    this.readyState = 3;
    this.onclose?.({ code: 1000 });
  }
  receive(frame: Record<string, unknown>): void {
    this.onmessage?.({ data: JSON.stringify(frame) });
  }
  get frames(): V1Frame[] {
    return this.sent.map((text) => JSON.parse(text) as V1Frame);
  }
  /** The server's ok to this socket's join. */
  joinOk(): void {
    const join = this.frames.find((frame) => frame.event === 'phx_join') as V1Frame;
    this.receive({
      topic: join.topic,
      event: 'phx_reply',
      payload: { status: 'ok', response: {} },
      ref: join.ref,
      join_ref: join.join_ref,
    });
  }
}

const until = async (condition: () => boolean): Promise<void> => {
  for (let tries = 0; tries < 200 && !condition(); tries += 1) {
    await new Promise((resolve) => setTimeout(resolve, 5));
  }
  expect(condition()).toBe(true);
};

const handlers = () => ({ onPing: vi.fn(), onDown: vi.fn(), onUp: vi.fn() });

/**
 * The frame the web sends (2026-11-24, confirmed): event `broadcast`, payload `{type, event, payload: {v, reason, at}}`,
 * `v` epoch ms and `at` ISO-8601. Vendored: capture-matchday-realtime.v1 (web 55d5bcc87d) names reason score, schedule or call.
 */
const broadcast = (topic: string, event: string, reason = 'score') => ({
  topic,
  event: 'broadcast',
  payload: {
    type: 'broadcast',
    event,
    payload: { v: 1_791_000_000_000, reason, at: '2026-10-03T13:00:00.000Z' },
  },
  ref: null,
  join_ref: null,
});

function world() {
  const record = createRingRecord();
  const logger = createLogger({ record, now: () => 0 });
  return {
    scoring: realtimePortFor({ logger }),
    matchDay: matchDayRealtimePortFor({ logger }),
    record,
  };
}

beforeEach(() => {
  FakeSocket.made.length = 0;
  vi.stubGlobal('WebSocket', FakeSocket);
});
afterEach(() => {
  vi.unstubAllGlobals();
});

/** The inert seam (AGENTS §13): what `createNativePorts` wires for Match Day, run over the real library. */
describe('Match Day’s Realtime port, over the real library', () => {
  it('joins the topic exactly as answered, private, with the token in the join and never in the URL', async () => {
    const { matchDay } = world();
    const off = matchDay.subscribe(MD_GRANT, handlers());
    await until(() => FakeSocket.made[0]?.sent.length > 0);
    const socket = FakeSocket.made[0];
    const url = new URL(socket.url);
    expect(url.searchParams.get('vsn')).toBe('1.0.0');
    expect(url.searchParams.get('apikey')).toBe(MD_GRANT.apikey);
    expect(socket.url).not.toContain(MD_GRANT.token);
    const [join] = socket.frames;
    expect(join.topic).toBe(MD_TOPIC);
    expect(join.event).toBe('phx_join');
    expect(join.payload.access_token).toBe(MD_GRANT.token);
    expect(join.payload.config?.private).toBe(true);
    off();
  });

  it('a nudge is a ping whatever its reason, an unknown one included; any other event is not', async () => {
    const { matchDay } = world();
    const h = handlers();
    const off = matchDay.subscribe(MD_GRANT, h);
    await until(() => FakeSocket.made[0]?.sent.length > 0);
    const socket = FakeSocket.made[0];
    socket.joinOk();
    expect(h.onUp).toHaveBeenCalledTimes(1);
    for (const reason of ['score', 'schedule', 'call', 'go-live', 'something-new']) {
      socket.receive(broadcast(MD_TOPIC, 'nudge', reason));
    }
    socket.receive(broadcast(MD_TOPIC, 'state_changed'));
    socket.receive(broadcast(MD_TOPIC, 'another'));
    expect(h.onPing).toHaveBeenCalledTimes(5);
    expect(h.onPing).toHaveBeenCalledWith();
    off();
  });

  it('a runtime with no WebSocket is down and said in Match Day’s own line, so the list polls', async () => {
    vi.stubGlobal('WebSocket', undefined);
    const { matchDay, record } = world();
    const h = handlers();
    matchDay.subscribe(MD_GRANT, h);
    await until(() => h.onDown.mock.calls.length > 0);
    expect(
      record
        .lines()
        .map(parseRecordLine)
        .map((line) => [line.event, line.fields]),
    ).toEqual([['matchday.realtime', { state: 'down', problem: 'client' }]]);
  });
});

/** AGENTS §10 question 4: Remote Scoring's channel open at the same time keeps working. */
describe('Match Day and Remote Scoring at once', () => {
  it('each has its own socket and token; a Match Day subscribe never closes scoring’s, and each hears only its own ping', async () => {
    const { scoring, matchDay, record } = world();
    const s = handlers();
    const m = handlers();
    const offScoring = scoring.subscribe(SCORING_GRANT, s);
    await until(() => FakeSocket.made[0]?.sent.length > 0);
    const offMatchDay = matchDay.subscribe(MD_GRANT, m);
    await until(() => FakeSocket.made[1]?.sent.length > 0);
    const [scoringSocket, matchDaySocket] = FakeSocket.made;
    expect(scoringSocket.closed).toBe(false);
    expect(scoringSocket.frames[0].payload.access_token).toBe(SCORING_GRANT.token);
    expect(matchDaySocket.frames[0].payload.access_token).toBe(MD_GRANT.token);
    expect(scoringSocket.sent.join()).not.toContain(MD_GRANT.token);
    expect(matchDaySocket.sent.join()).not.toContain(SCORING_GRANT.token);

    scoringSocket.joinOk();
    matchDaySocket.joinOk();
    scoringSocket.receive(broadcast(SCORING_TOPIC, 'state_changed'));
    matchDaySocket.receive(broadcast(MD_TOPIC, 'nudge'));
    expect(s.onPing).toHaveBeenCalledTimes(1);
    expect(m.onPing).toHaveBeenCalledTimes(1);

    offMatchDay();
    expect(matchDaySocket.closed).toBe(true);
    expect(scoringSocket.closed).toBe(false);
    scoringSocket.receive(broadcast(SCORING_TOPIC, 'state_changed'));
    expect(s.onPing).toHaveBeenCalledTimes(2);
    offScoring();

    const events = record
      .lines()
      .map(parseRecordLine)
      .map((line) => line.event);
    expect(events).toEqual(['scoring.realtime', 'matchday.realtime']);
    expect(record.lines().join('\n')).not.toContain(MD_GRANT.token);
  });
});
