import { memo } from 'react';
import { StyleSheet } from 'react-native';
import { selectSinceEpochMs } from '@/hooks/engineSelectors';
import { useClockTick } from '@/hooks/useClockTick';
import { useEngineSelector } from '@/hooks/useCaptureEngine';
import { useT } from '@/hooks/useLanguage';
import { formatElapsed } from '@/i18n/formatElapsed';
import { Text } from '@/ui/components/Text';
import { colour } from '@/ui/theme/tokens';

/**
 * Time on air, on air only (spec §4). It survives a drop: the hold keeps the broadcast continuous. It counts from the
 * upload's connect; while the plate reads Going live (`pending`) it counts in lilac, not the lime LED (owner's mock,
 * 2026-11-26), so nothing lit says the viewers have the picture before they do.
 */
export const Elapsed = memo(function Elapsed({ pending = false }: { pending?: boolean }) {
  const { t } = useT();
  const since = useEngineSelector(selectSinceEpochMs);
  const now = useClockTick(since !== null);
  if (since === null) return null;
  const time = formatElapsed(now - since);
  return (
    <Text
      variant="elapsed"
      testID="elapsed"
      style={pending ? styles.pending : undefined}
      accessibilityLabel={t('stream.elapsed.label', { time })}
    >
      {time}
    </Text>
  );
});

const styles = StyleSheet.create({
  pending: { color: colour.ink3 },
});
