import { describe, expect, it } from 'vitest';
import config, { appLinkFilters } from '../app.config';
import { seaznHosts } from '../src/services/seaznHosts';

const filterFor = (host: string) => [
  {
    action: 'VIEW',
    autoVerify: true,
    category: ['BROWSABLE', 'DEFAULT'],
    data: [{ scheme: 'https', host, pathPrefix: '/app/' }],
  },
];

describe('appLinkFilters', () => {
  it('declares /app/ on seazn.club in production', () => {
    expect(appLinkFilters('production')).toEqual(filterFor('seazn.club'));
  });

  it.each([undefined, 'staging', '', 'Production'])('declares stg.seazn.club for %s', (env) => {
    expect(appLinkFilters(env)).toEqual(filterFor('stg.seazn.club'));
  });

  it.each(['production', 'staging', undefined])(
    'uses exactly the hosts seaznHosts lists (%s)',
    (env) => {
      const hosts = appLinkFilters(env).flatMap((f) => f.data.map((d) => d.host));
      expect(hosts).toEqual([...seaznHosts(env)]);
    },
  );
});

describe('resolved config', () => {
  const resolve = (env: Record<string, string | undefined>) => {
    const saved = { ...process.env };
    for (const [k, v] of Object.entries(env)) {
      if (v === undefined) delete process.env[k];
      else process.env[k] = v;
    }
    try {
      return config({
        config: { name: 'Seazn Club', slug: 'capture' },
        projectRoot: process.cwd(),
        staticConfigPath: null,
        packageJsonPath: null,
      });
    } finally {
      process.env = saved;
    }
  };

  it('carries the production filter on a production build', () => {
    const c = resolve({ APP_VARIANT: 'production', EXPO_PUBLIC_SEAZN_ENV: 'production' });
    expect(c.android?.intentFilters).toEqual(filterFor('seazn.club'));
  });

  it('carries the staging filter with neither variable', () => {
    const c = resolve({ APP_VARIANT: undefined, EXPO_PUBLIC_SEAZN_ENV: undefined });
    expect(c.android?.intentFilters).toEqual(filterFor('stg.seazn.club'));
  });
});
