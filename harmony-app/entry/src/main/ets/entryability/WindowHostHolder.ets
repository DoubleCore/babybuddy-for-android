import { window } from '@kit.ArkUI';
import { BusinessError } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';

const DOMAIN: number = 0x0000;
const TAG: string = 'WindowHostHolder';

/**
 * Holds a strongly-typed reference to the EntryAbility main window so
 * ArkTS pages and JSBridge services can update system bars and keep-screen-on state.
 */
export class WindowHostHolder {
  private static mainWindow: window.Window | undefined = undefined;

  public static setMainWindow(win: window.Window | undefined): void {
    WindowHostHolder.mainWindow = win;
  }

  public static getMainWindow(): window.Window | undefined {
    return WindowHostHolder.mainWindow;
  }

  public static applyTheme(isDark: boolean): void {
    const win = WindowHostHolder.mainWindow;
    if (!win) {
      return;
    }
    const barProps: window.SystemBarProperties = {
      statusBarColor: isDark ? '#181514' : '#FFFAF5',
      navigationBarColor: isDark ? '#24201E' : '#FFFFFF',
      statusBarContentColor: isDark ? '#F5EBE6' : '#2D2522',
      navigationBarContentColor: isDark ? '#F5EBE6' : '#2D2522'
    };
    win.setWindowSystemBarProperties(barProps).catch((err: BusinessError) => {
      hilog.error(DOMAIN, TAG, 'setWindowSystemBarProperties failed: %{public}d %{public}s', err.code, err.message);
    });
  }

  public static setKeepScreenOn(enable: boolean): void {
    const win = WindowHostHolder.mainWindow;
    if (!win) {
      return;
    }
    win.setWindowKeepScreenOn(enable).catch((err: BusinessError) => {
      hilog.error(DOMAIN, TAG, 'setWindowKeepScreenOn failed: %{public}d %{public}s', err.code, err.message);
    });
  }
}
