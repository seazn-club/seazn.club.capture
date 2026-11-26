import type { CaptureEngineNative } from '@/engine/nativeModule';
import { createRecordFilePort, type RecordFilePort } from '@/services/recordFilePort';

/**
 * N53: the production record file port over the native module `createNativePorts` loaded once. No module (iOS, the
 * web) is a port with no file, so Send and Share use the JS ring. The call is a closure over the module, so native's
 * `this` stays bound, and a stale build whose module has no `readRecord` throws inside the port, which reads no file.
 */
export function recordFilePortFor(
  native: Pick<CaptureEngineNative, 'readRecord'> | null,
): RecordFilePort {
  return createRecordFilePort(native === null ? null : () => native.readRecord());
}
