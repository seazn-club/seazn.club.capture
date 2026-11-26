import { memo } from 'react';
import { StyleSheet, View, type StyleProp, type TextStyle, type ViewStyle } from 'react-native';
import { tallyTone, type TallyPlate as Plate, type TallyTone } from '@/hooks/preflight';
import { useT } from '@/hooks/useLanguage';
import type { MessageKey } from '@/i18n/messages';
import { PlateSweep } from '@/ui/components/PlateSweep';
import { Text } from '@/ui/components/Text';
import { colour, layout, plate as plateColour, plateInk, space } from '@/ui/theme/tokens';

const WORD: Readonly<Record<Plate, MessageKey>> = {
  starting: 'stream.tally.starting',
  notReady: 'stream.tally.notReady',
  ready: 'stream.tally.ready',
  connecting: 'stream.tally.connecting',
  goingLive: 'stream.tally.goingLive',
  live: 'stream.tally.live',
  trouble: 'stream.tally.trouble',
  ended: 'stream.tally.ended',
};

/**
 * The state plate (spec §4): 56 dp of solid colour, judged by what reads at
 * two metres, not by its type. The colour comes only from `tallyTone`, so red
 * can only ever mean LIVE: the viewers' picture confirmed, or 60 s gone (owner,
 * 2026-11-26). Going live is the dark plate outlined in red, with a slow sweep.
 */
export const TallyPlate = memo(function TallyPlate({ plate }: { plate: Plate }) {
  const { t } = useT();
  const tone = tallyTone(plate);
  return (
    <View style={PLATE[tone]} testID="tally-plate" accessibilityLiveRegion="polite">
      {tone === 'going' ? <PlateSweep /> : null}
      <Text variant="state" style={INK[tone]}>
        {t(WORD[plate])}
      </Text>
    </View>
  );
});

const styles = StyleSheet.create({
  plate: { minHeight: layout.plateHeight, justifyContent: 'center', paddingHorizontal: space.sm },
  live: { backgroundColor: plateColour.live },
  // The sweep is clipped to the plate; the outline is inside its 56 dp, so the row does not move.
  going: {
    backgroundColor: plateColour.going,
    borderColor: plateColour.goingEdge,
    borderWidth: layout.plateOutline,
    overflow: 'hidden',
  },
  healthy: { backgroundColor: plateColour.healthy },
  degraded: { backgroundColor: plateColour.degraded },
  inert: { backgroundColor: plateColour.inert },
  // The state role sets no colour, and Fabric's default text is black: every
  // plate names its ink (the ContinueCard lesson, AGENTS §13).
  onPlate: { color: plateInk },
  onInert: { color: colour.ink },
});

const PLATE: Readonly<Record<TallyTone, StyleProp<ViewStyle>>> = {
  live: [styles.plate, styles.live],
  going: [styles.plate, styles.going],
  healthy: [styles.plate, styles.healthy],
  degraded: [styles.plate, styles.degraded],
  inert: [styles.plate, styles.inert],
};
const INK: Readonly<Record<TallyTone, StyleProp<TextStyle>>> = {
  live: styles.onPlate,
  going: styles.onInert,
  healthy: styles.onPlate,
  degraded: styles.onPlate,
  inert: styles.onInert,
};
