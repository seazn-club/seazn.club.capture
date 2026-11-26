import { memo, useEffect } from 'react';
import { StyleSheet } from 'react-native';
import Animated, {
  cancelAnimation,
  Easing,
  ReduceMotion,
  useAnimatedStyle,
  useReducedMotion,
  useSharedValue,
  withRepeat,
  withTiming,
} from 'react-native-reanimated';
import { plate } from '@/ui/theme/tokens';

/** One pass of the band across the plate: slow, so it reads as "working on it", never as an alarm. */
const SWEEP_MS = 2400;
/** The band's width, as a share of the plate. It starts wholly off the left edge and ends wholly off the right. */
const BAND = 0.3;

/**
 * Going live's slow sweep (owner's mock, 2026-11-26): a band of faint live red crossing the dark plate, on the UI
 * thread (AGENTS §8). Under reduced motion it is not drawn at all, and the plate is its outline and its word: the static
 * fallback. Reanimated reads the system setting when the app starts (TurnGlyph's note), so a mid-session toggle takes
 * effect at the next start. Drawing only: it takes no touches and says nothing to a screen reader.
 */
export const PlateSweep = memo(function PlateSweep() {
  const reduce = useReducedMotion();
  return reduce ? null : <Sweep />;
});

function Sweep() {
  const at = useSharedValue(0);
  useEffect(() => {
    at.value = withRepeat(
      withTiming(1, {
        duration: SWEEP_MS,
        easing: Easing.inOut(Easing.quad),
        reduceMotion: ReduceMotion.System,
      }),
      -1,
    );
    return () => cancelAnimation(at);
  }, [at]);
  const band = useAnimatedStyle(() => ({ left: `${(at.value * (1 + BAND) - BAND) * 100}%` }));
  return (
    <Animated.View
      pointerEvents="none"
      importantForAccessibility="no-hide-descendants"
      accessibilityElementsHidden
      style={[styles.band, band]}
    />
  );
}

const styles = StyleSheet.create({
  band: {
    position: 'absolute',
    top: 0,
    bottom: 0,
    width: `${BAND * 100}%`,
    backgroundColor: plate.goingSweep,
  },
});
