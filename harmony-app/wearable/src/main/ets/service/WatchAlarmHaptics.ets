import { BusinessError } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { vibrator } from '@kit.SensorServiceKit';

const DOMAIN: number = 0x0000;
const TAG: string = 'WatchAlarmHaptics';

/**
 * Controls wrist vibration patterns on HarmonyOS wearable devices using `@kit.SensorServiceKit`.
 */
export class WatchAlarmHaptics {
  private alarmTimerId: number = -1;
  private ringing: boolean = false;

  public startWakeAlarm(): void {
    if (this.ringing) {
      return;
    }
    this.ringing = true;
    this.vibrateOnce(650, 'alarm');
    this.alarmTimerId = setInterval(() => {
      if (this.ringing) {
        this.vibrateOnce(650, 'alarm');
      }
    }, 1150);
  }

  public stopWakeAlarm(): void {
    this.ringing = false;
    if (this.alarmTimerId !== -1) {
      clearInterval(this.alarmTimerId);
      this.alarmTimerId = -1;
    }
    try {
      vibrator.stopVibration().catch((err: BusinessError) => {
        hilog.warn(DOMAIN, TAG, 'stopVibration failed: %{public}d', err.code);
      });
    } catch (e) {
      const bErr = e as BusinessError;
      hilog.warn(DOMAIN, TAG, 'stopVibration exception: %{public}d', bErr.code);
    }
  }

  public tapConfirm(): void {
    this.vibrateOnce(60, 'touch');
  }

  private vibrateOnce(durationMs: number, usage: vibrator.Usage): void {
    try {
      const effect: vibrator.VibrateTime = {
        type: 'time',
        duration: durationMs
      };
      const attr: vibrator.VibrateAttribute = {
        id: 0,
        usage: usage
      };
      vibrator.startVibration(effect, attr).catch((err: BusinessError) => {
        hilog.warn(DOMAIN, TAG, 'startVibration failed: %{public}d %{public}s', err.code, err.message);
      });
    } catch (e) {
      const bErr = e as BusinessError;
      hilog.warn(DOMAIN, TAG, 'startVibration exception: %{public}d', bErr.code);
    }
  }
}
