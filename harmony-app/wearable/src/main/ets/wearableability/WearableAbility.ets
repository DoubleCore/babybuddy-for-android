import { AbilityConstant, UIAbility, Want } from '@kit.AbilityKit';
import { window } from '@kit.ArkUI';
import { BusinessError } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';

const DOMAIN: number = 0x0000;
const TAG: string = 'WearableAbility';

export default class WearableAbility extends UIAbility {
  private watchWindow: window.Window | undefined = undefined;

  onCreate(want: Want, launchParam: AbilityConstant.LaunchParam): void {
    hilog.info(DOMAIN, TAG, 'WearableAbility onCreate: %{public}s %{public}d', want.abilityName ?? '', launchParam.launchReason);
  }

  onDestroy(): void {
    hilog.info(DOMAIN, TAG, 'WearableAbility onDestroy');
  }

  onWindowStageCreate(windowStage: window.WindowStage): void {
    hilog.info(DOMAIN, TAG, 'WearableAbility onWindowStageCreate');
    windowStage.loadContent('pages/WatchHomePage', (err: BusinessError) => {
      if (err.code) {
        hilog.error(DOMAIN, TAG, 'Failed to load WatchHomePage: %{public}d %{public}s', err.code, err.message);
        return;
      }
      try {
        const win: window.Window = windowStage.getMainWindowSync();
        this.watchWindow = win;
        // Keep watch screen awake while the baby monitor page is actively open.
        win.setWindowKeepScreenOn(true).catch((keepErr: BusinessError) => {
          hilog.warn(DOMAIN, TAG, 'setWindowKeepScreenOn failed: %{public}d', keepErr.code);
        });
      } catch (e) {
        const bErr = e as BusinessError;
        hilog.warn(DOMAIN, TAG, 'getMainWindowSync failed: %{public}d', bErr.code);
      }
    });
  }

  onWindowStageDestroy(): void {
    if (this.watchWindow) {
      this.watchWindow.setWindowKeepScreenOn(false).catch(() => {});
      this.watchWindow = undefined;
    }
  }
}
