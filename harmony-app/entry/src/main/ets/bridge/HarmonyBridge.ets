import { abilityAccessCtrl, common, PermissionRequestResult, Permissions } from '@kit.AbilityKit';
import { UIContext } from '@kit.ArkUI';
import { BusinessError } from '@kit.BasicServicesKit';
import { fileIo as fs, picker } from '@kit.CoreFileKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { vibrator } from '@kit.SensorServiceKit';
import { WindowHostHolder } from '../entryability/WindowHostHolder';

const DOMAIN: number = 0x0000;
const TAG: string = 'HarmonyBridge';

/**
 * Native bridge injected into ArkWeb as `window.HarmonyBridge` via `javaScriptProxy`.
 * Enables native HarmonyOS file export (CoreFileKit), alarm haptics (SensorServiceKit),
 * screen wake lock & system bar syncing (ArkUI window), and runtime camera permission.
 */
export class HarmonyBridge {
  private uiContext: UIContext | undefined = undefined;
  private alarmTimerId: number = -1;

  public attachUIContext(uiContext: UIContext): void {
    this.uiContext = uiContext;
  }

  public getPlatformInfo(): string {
    return '{"os":"HarmonyOS","bridgeVersion":1,"app":"BabyBuddy"}';
  }

  public syncThemeMode(isDark: boolean): void {
    AppStorage.setOrCreate<boolean>('isDarkMode', isDark);
    WindowHostHolder.applyTheme(isDark);
  }

  public setKeepScreenOn(enable: boolean): void {
    WindowHostHolder.setKeepScreenOn(enable);
  }

  public startAlarmVibration(): void {
    this.stopAlarmVibration();
    this.pulseAlarmOnce();
    this.alarmTimerId = setInterval(() => {
      this.pulseAlarmOnce();
    }, 1100);
  }

  public stopAlarmVibration(): void {
    if (this.alarmTimerId !== -1) {
      clearInterval(this.alarmTimerId);
      this.alarmTimerId = -1;
    }
    try {
      vibrator.stopVibration().catch((err: BusinessError) => {
        hilog.warn(DOMAIN, TAG, 'stopVibration failed: %{public}d %{public}s', err.code, err.message);
      });
    } catch (e) {
      const bErr = e as BusinessError;
      hilog.warn(DOMAIN, TAG, 'stopVibration exception: %{public}d', bErr.code);
    }
  }

  public requestCameraPermission(): void {
    const context = this.getHostContext();
    if (!context) {
      return;
    }
    const atManager: abilityAccessCtrl.AtManager = abilityAccessCtrl.createAtManager();
    const permissions: Permissions[] = ['ohos.permission.CAMERA'];
    atManager.requestPermissionsFromUser(context, permissions).then((result: PermissionRequestResult) => {
      const granted = result.authResults.length > 0 && result.authResults[0] === 0;
      if (!granted) {
        this.showToast('未授予相机权限，姿态监测将使用模拟模式');
      }
    }).catch((err: BusinessError) => {
      hilog.error(DOMAIN, TAG, 'requestCameraPermission failed: %{public}d %{public}s', err.code, err.message);
    });
  }

  public saveTextFile(filename: string, content: string, mimeType: string): void {
    const context = this.getHostContext();
    if (!context) {
      hilog.error(DOMAIN, TAG, 'saveTextFile skipped: missing UIAbilityContext');
      return;
    }
    const safeName = filename && filename.trim().length > 0 ? filename.trim() : 'babybuddy-backup.json';
    const saveOptions = new picker.DocumentSaveOptions();
    saveOptions.newFileNames = [safeName];
    if (mimeType === 'text/csv' || safeName.endsWith('.csv')) {
      saveOptions.fileSuffixChoices = ['.csv'];
    } else {
      saveOptions.fileSuffixChoices = ['.json'];
    }

    const documentViewPicker = new picker.DocumentViewPicker(context);
    documentViewPicker.save(saveOptions).then((uris: Array<string>) => {
      if (!uris || uris.length === 0) {
        this.showToast('已取消保存');
        return;
      }
      const targetUri: string = uris[0];
      let file: fs.File | undefined = undefined;
      try {
        file = fs.openSync(targetUri, fs.OpenMode.READ_WRITE);
        fs.writeSync(file.fd, content);
        this.showToast(`已保存备份：${safeName}`);
      } catch (writeErr) {
        const bErr = writeErr as BusinessError;
        hilog.error(DOMAIN, TAG, 'write backup failed: %{public}d %{public}s', bErr.code, bErr.message);
        this.showToast('写入备份文件失败');
      } finally {
        if (file) {
          try {
            fs.closeSync(file);
          } catch (closeErr) {
            const bErr = closeErr as BusinessError;
            hilog.warn(DOMAIN, TAG, 'closeSync failed: %{public}d', bErr.code);
          }
        }
      }
    }).catch((err: BusinessError) => {
      hilog.error(DOMAIN, TAG, 'DocumentViewPicker.save failed: %{public}d %{public}s', err.code, err.message);
      this.showToast('无法打开系统文件保存面板');
    });
  }

  public dispose(): void {
    this.stopAlarmVibration();
    WindowHostHolder.setKeepScreenOn(false);
  }

  private pulseAlarmOnce(): void {
    try {
      const effect: vibrator.VibrateTime = {
        type: 'time',
        duration: 600
      };
      const attr: vibrator.VibrateAttribute = {
        id: 0,
        usage: 'alarm'
      };
      vibrator.startVibration(effect, attr).catch((err: BusinessError) => {
        hilog.warn(DOMAIN, TAG, 'startVibration failed: %{public}d %{public}s', err.code, err.message);
      });
    } catch (e) {
      const bErr = e as BusinessError;
      hilog.warn(DOMAIN, TAG, 'startVibration exception: %{public}d', bErr.code);
    }
  }

  private getHostContext(): common.UIAbilityContext | undefined {
    if (!this.uiContext) {
      return undefined;
    }
    const host = this.uiContext.getHostContext();
    return host ? (host as common.UIAbilityContext) : undefined;
  }

  private showToast(message: string): void {
    if (!this.uiContext) {
      return;
    }
    try {
      this.uiContext.getPromptAction().showToast({
        message: message,
        duration: 2000
      });
    } catch (e) {
      const bErr = e as BusinessError;
      hilog.warn(DOMAIN, TAG, 'showToast failed: %{public}d', bErr.code);
    }
  }
}
