import { describe, expect, it } from 'vitest';
import { createScanFlight } from '@/services/scanFlight';

describe('scanFlight', () => {
  it('is down until a scan begins, and down again once it ends in the app', () => {
    const flight = createScanFlight();
    expect(flight.active()).toBe(false);
    expect(flight.begin()).toBe(true);
    expect(flight.active()).toBe(true);
    flight.end();
    expect(flight.active()).toBe(false);
    expect(flight.appReturned()).toBe(false);
  });

  it('refuses a second scan while one is in flight, and takes the next after it ends', () => {
    const flight = createScanFlight();
    flight.begin();
    expect(flight.begin()).toBe(false);
    flight.end();
    expect(flight.begin()).toBe(true);
  });

  it('claims the return that lands before the result, then ends on the result (I2)', () => {
    const flight = createScanFlight();
    flight.begin();
    flight.appLeft();
    expect(flight.appReturned()).toBe(true);
    expect(flight.active()).toBe(true);
    flight.end();
    expect(flight.active()).toBe(false);
    expect(flight.appReturned()).toBe(false);
  });

  it('stays up past a result that lands before the return, and claims that return (R36)', () => {
    const flight = createScanFlight();
    flight.begin();
    flight.appLeft();
    flight.end();
    expect(flight.active()).toBe(true);
    expect(flight.appReturned()).toBe(true);
    expect(flight.active()).toBe(false);
    expect(flight.appReturned()).toBe(false);
  });

  it('ignores the app leaving when no scan is up', () => {
    const flight = createScanFlight();
    flight.appLeft();
    expect(flight.active()).toBe(false);
    expect(flight.appReturned()).toBe(false);
  });

  it('tells a listener each time the flight comes down, and only then (Open in app waits on it)', () => {
    const flight = createScanFlight();
    let idle = 0;
    const off = flight.onIdle(() => (idle += 1));
    flight.end();
    flight.appReturned();
    expect(idle).toBe(0);
    flight.begin();
    flight.end();
    expect(idle).toBe(1);
    flight.begin();
    flight.appLeft();
    flight.end();
    expect(idle).toBe(1);
    flight.appReturned();
    expect(idle).toBe(2);
    off();
    flight.begin();
    flight.end();
    expect(idle).toBe(2);
  });
});
