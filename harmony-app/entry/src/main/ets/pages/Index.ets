import { abilityAccessCtrl, common, PermissionRequestResult, Permissions } from '@kit.AbilityKit';
import { webview } from '@kit.ArkWeb';
import { BusinessError } from '@kit.BasicServicesKit';
import { picker } from '@kit.CoreFileKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { HarmonyBridge } from '../bridge/HarmonyBridge';

const DOMAIN: number = 0x0000;
const TAG: string = 'BabyBuddyWebPage';

@Entry
@Component
struct Index {
  private controller: webview.WebviewController = new webview.WebviewController();
  private bridge: HarmonyBridge = new HarmonyBridge();

  @StorageProp('topAvoidHeight') @Watch('onSafeAreaChanged') topAvoidHeight: number = 0;
  @StorageProp('bottomAvoidHeight') @Watch('onSafeAreaChanged') bottomAvoidHeight: number = 0;
  @StorageProp('isDarkMode') isDarkMode: boolean = false;

  @State isPageReady: boolean = false;
  @State lastErrorText: string = '';
  private lastBackPressMs: number = 0;

  aboutToAppear(): void {
    this.bridge.attachUIContext(this.getUIContext());
  }

  aboutToDisappear(): void {
    this.bridge.dispose();
  }

  onSafeAreaChanged(): void {
    this.pushSafeAreaToWeb();
  }

  private pushSafeAreaToWeb(): void {
    if (!this.isPageReady) {
      return;
    }
    const topVal = Math.max(0, Math.round(this.topAvoidHeight));
    const bottomVal = Math.max(0, Math.round(this.bottomAvoidHeight));
    const script = `if (window.App && window.App.applyNativeSafeArea) { window.App.applyNativeSafeArea(${topVal}, ${bottomVal}); }`;
    try {
      this.controller.runJavaScript(script).catch((err: BusinessError) => {
        hilog.warn(DOMAIN, TAG, 'applyNativeSafeArea failed: %{public}d', err.code);
      });
    } catch (e) {
      const bErr = e as BusinessError;
      hilog.warn(DOMAIN, TAG, 'runJavaScript failed: %{public}d', bErr.code);
    }
  }

  onBackPress(): boolean {
    if (this.controller.accessBackward()) {
      this.controller.backward();
      return true;
    }

    const now = Date.now();
    // Ask the SPA to close any active sheet or navigate back to the Today tab first.
    try {
      this.controller.runJavaScript(
        'window.App && window.App.handleNativeBack ? window.App.handleNativeBack() : "pass"'
      ).then((rawResult: string) => {
        const normalized = rawResult.replace(/"/g, '').trim();
        if (normalized === 'pass') {
          if (now - this.lastBackPressMs < 2000) {
            const ctx = this.getUIContext().getHostContext() as common.UIAbilityContext | undefined;
            if (ctx) {
              ctx.terminateSelf().catch((err: BusinessError) => {
                hilog.warn(DOMAIN, TAG, 'terminateSelf failed: %{public}d', err.code);
              });
            }
          } else {
            this.lastBackPressMs = now;
            this.getUIContext().getPromptAction().showToast({
              message: '再滑一次退出 Baby Buddy',
              duration: 1800
            });
          }
        }
      }).catch((err: BusinessError) => {
        hilog.warn(DOMAIN, TAG, 'handleNativeBack failed: %{public}d', err.code);
      });
      return true;
    } catch (e) {
      const bErr = e as BusinessError;
      hilog.warn(DOMAIN, TAG, 'onBackPress fallback: %{public}d', bErr.code);
      return false;
    }
  }

  private handleWebPermissionRequest(request: PermissionRequest): void {
    const resources: Array<string> = request.getAccessibleResource();
    const hostContext = this.getUIContext().getHostContext() as common.UIAbilityContext | undefined;
    if (!hostContext) {
      request.deny();
      return;
    }

    const needCamera = resources.some((res: string) => res.indexOf('VIDEO') >= 0 || res.indexOf('CAMERA') >= 0);
    if (!needCamera) {
      request.grant(resources);
      return;
    }

    const atManager: abilityAccessCtrl.AtManager = abilityAccessCtrl.createAtManager();
    const permissions: Permissions[] = ['ohos.permission.CAMERA'];
    atManager.requestPermissionsFromUser(hostContext, permissions).then((res: PermissionRequestResult) => {
      if (res.authResults.length > 0 && res.authResults[0] === 0) {
        request.grant(resources);
      } else {
        request.deny();
        this.getUIContext().getPromptAction().showToast({
          message: '请在系统设置中允许相机权限以启用婴儿姿态检测',
          duration: 2400
        });
      }
    }).catch((err: BusinessError) => {
      hilog.error(DOMAIN, TAG, 'requestPermissionsFromUser failed: %{public}d %{public}s', err.code, err.message);
      request.deny();
    });
  }

  private handleWebFileSelect(result: FileSelectorResult): boolean {
    const hostContext = this.getUIContext().getHostContext() as common.UIAbilityContext | undefined;
    if (!hostContext) {
      result.handleFileList([]);
      return true;
    }
    const selectOptions = new picker.DocumentSelectOptions();
    selectOptions.maxSelectNumber = 1;
    const documentPicker = new picker.DocumentViewPicker(hostContext);
    documentPicker.select(selectOptions).then((uris: Array<string>) => {
      result.handleFileList(uris && uris.length > 0 ? uris : []);
    }).catch((err: BusinessError) => {
      hilog.error(DOMAIN, TAG, 'DocumentViewPicker.select failed: %{public}d %{public}s', err.code, err.message);
      result.handleFileList([]);
    });
    return true;
  }

  build() {
    Stack() {
      Web({ src: $rawfile('web/index.html'), controller: this.controller })
        .width('100%')
        .height('100%')
        .backgroundColor(this.isDarkMode ? '#181514' : '#FFFAF5')
        .javaScriptAccess(true)
        .domStorageAccess(true)
        .databaseAccess(true)
        .fileAccess(true)
        .imageAccess(true)
        .onlineImageAccess(true)
        .mediaPlayGestureAccess(false)
        .mixedMode(MixedMode.Compatible)
        .javaScriptProxy({
          object: this.bridge,
          name: 'HarmonyBridge',
          methodList: [
            'getPlatformInfo',
            'syncThemeMode',
            'setKeepScreenOn',
            'startAlarmVibration',
            'stopAlarmVibration',
            'requestCameraPermission',
            'saveTextFile'
          ],
          controller: this.controller
        })
        .onPageEnd(() => {
          this.isPageReady = true;
          this.lastErrorText = '';
          this.pushSafeAreaToWeb();
        })
        .onErrorReceive((event) => {
          if (event && event.request && event.request.isMainFrame()) {
            this.lastErrorText = event.error.getErrorInfo();
          }
        })
        .onPermissionRequest((event) => {
          if (event && event.request) {
            this.handleWebPermissionRequest(event.request);
          }
        })
        .onShowFileSelector((event) => {
          if (event && event.result) {
            return this.handleWebFileSelect(event.result);
          }
          return false;
        })

      if (!this.isPageReady) {
        Column({ space: 12 }) {
          LoadingProgress()
            .width(36)
            .height(36)
            .color('#E07A5F')
          Text('Baby Buddy 正在加载…')
            .fontSize(14)
            .fontColor(this.isDarkMode ? '#F5EBE6' : '#7A6B65')
        }
        .width('100%')
        .height('100%')
        .justifyContent(FlexAlign.Center)
        .backgroundColor(this.isDarkMode ? '#181514' : '#FFFAF5')
      }

      if (this.lastErrorText.length > 0) {
        Column({ space: 12 }) {
          Text('页面加载异常')
            .fontSize(16)
            .fontWeight(FontWeight.Bold)
            .fontColor('#D9534F')
          Text(this.lastErrorText)
            .fontSize(12)
            .fontColor('#7A6B65')
            .textAlign(TextAlign.Center)
          Button('重新加载')
            .backgroundColor('#E07A5F')
            .fontColor('#FFFFFF')
            .onClick(() => {
              this.lastErrorText = '';
              this.controller.refresh();
            })
        }
        .padding(24)
        .borderRadius(20)
        .backgroundColor(this.isDarkMode ? '#24201E' : '#FFFFFF')
        .margin({ left: 24, right: 24 })
      }
    }
    .width('100%')
    .height('100%')
    .backgroundColor(this.isDarkMode ? '#181514' : '#FFFAF5')
  }
}
