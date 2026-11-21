import { AbilityConstant, UIAbility, Want } from '@kit.AbilityKit';
import { window } from '@kit.ArkUI';
import { BusinessError } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { WindowHostHolder } from './WindowHostHolder';

const DOMAIN: number = 0x0000;
const TAG: string = 'EntryAbility';

export default class EntryAbility extends UIAbility {
  private mainWindow: window.Window | undefined = undefined;

  onCreate(want: Want, launchParam: AbilityConstant.LaunchParam): void {
    hilog.info(DOMAIN, TAG, 'EntryAbility onCreate: %{public}s %{public}d', want.abilityName ?? '', launchParam.launchReason);
  }

  onDestroy(): void {
    hilog.info(DOMAIN, TAG, 'EntryAbility onDestroy');
  }

  onWindowStageCreate(windowStage: window.WindowStage): void {
    hilog.info(DOMAIN, TAG, 'EntryAbility onWindowStageCreate');
    AppStorage.setOrCreate<number>('topAvoidHeight', 0);
    AppStorage.setOrCreate<number>('bottomAvoidHeight', 0);
    AppStorage.setOrCreate<boolean>('isDarkMode', false);

    windowStage.loadContent('pages/Index', (err: BusinessError) => {
      if (err.code) {
        hilog.error(DOMAIN, TAG, 'Failed to load pages/Index. Code: %{public}d, message: %{public}s', err.code, err.message);
        return;
      }

      try {
        const win: window.Window = windowStage.getMainWindowSync();
        this.mainWindow = win;
        WindowHostHolder.setMainWindow(win);

        win.setWindowLayoutFullScreen(true).catch((fullErr: BusinessError) => {
          hilog.error(DOMAIN, TAG, 'setWindowLayoutFullScreen failed: %{public}d %{public}s', fullErr.code, fullErr.message);
        });

        WindowHostHolder.applyTheme(false);
        this.syncAvoidAreas(win);

        win.on('avoidAreaChange', (data: window.AvoidAreaOptions) => {
          const uiCtx = win.getUIContext();
          if (data.type === window.AvoidAreaType.TYPE_SYSTEM) {
            AppStorage.setOrCreate<number>('topAvoidHeight', uiCtx.px2vp(data.area.topRect.height));
          } else if (data.type === window.AvoidAreaType.TYPE_NAVIGATION_INDICATOR) {
            AppStorage.setOrCreate<number>('bottomAvoidHeight', uiCtx.px2vp(data.area.bottomRect.height));
          }
        });
      } catch (e) {
        const bErr = e as BusinessError;
        hilog.error(DOMAIN, TAG, 'Main window setup failed: %{public}d %{public}s', bErr.code, bErr.message);
      }
    });
  }

  onWindowStageDestroy(): void {
    if (this.mainWindow) {
      try {
        this.mainWindow.off('avoidAreaChange');
      } catch (e) {
        const bErr = e as BusinessError;
        hilog.warn(DOMAIN, TAG, 'off avoidAreaChange failed: %{public}d', bErr.code);
      }
      this.mainWindow = undefined;
    }
    WindowHostHolder.setMainWindow(undefined);
  }

  private syncAvoidAreas(win: window.Window): void {
    const uiCtx = win.getUIContext();
    const sysArea: window.AvoidArea = win.getWindowAvoidArea(window.AvoidAreaType.TYPE_SYSTEM);
    const navArea: window.AvoidArea = win.getWindowAvoidArea(window.AvoidAreaType.TYPE_NAVIGATION_INDICATOR);
    AppStorage.setOrCreate<number>('topAvoidHeight', uiCtx.px2vp(sysArea.topRect.height));
    AppStorage.setOrCreate<number>('bottomAvoidHeight', uiCtx.px2vp(navArea.bottomRect.height));
  }
}
