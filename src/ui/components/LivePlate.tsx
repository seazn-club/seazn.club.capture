import { memo } from 'react';
import { selectBackupOnly, selectStateKind } from '@/hooks/engineSelectors';
import { tallyPlateFor } from '@/hooks/preflight';
import { useEngineSelector } from '@/hooks/useCaptureEngine';
import { useAirStage } from '@/hooks/useViewfinder';
import { TallyPlate } from '@/ui/components/TallyPlate';

/**
 * Beside Back on Settings (AGENTS §6), so the broadcast is
 * never out of sight. The viewfinder's own plate, so the LIVE gate holds
 * here too: connecting never reads LIVE, and an unconfirmed picture reads
 * Going live from the same latch (owner, 2026-11-26).
 */
export const LivePlate = memo(function LivePlate() {
  const kind = useEngineSelector(selectStateKind);
  const backupOnly = useEngineSelector(selectBackupOnly);
  const stage = useAirStage(kind);
  const plate = tallyPlateFor(kind, false, backupOnly, stage);
  const onAir =
    plate === 'connecting' || plate === 'goingLive' || plate === 'live' || plate === 'trouble';
  return onAir ? <TallyPlate plate={plate} /> : null;
});
