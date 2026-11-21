import { common } from '@kit.AbilityKit';
import { WatchConnectionConfig } from '../model/WatchMonitorModels';
import { WatchApiClient } from '../service/WatchApiClient';
import { WatchPreferences } from '../service/WatchPreferences';

@Entry
@Component
struct WatchConfigPage {
  @State hostInput: string = '192.168.1.';
  @State portInput: string = '8765';
  @State autoSnapshot: boolean = true;
  @State testing: boolean = false;
  @State statusMessage: string = '在手机端「姿态看护」中查看局域网 IP';

  aboutToAppear(): void {
    const ctx = this.getHostContext();
    if (!ctx) {
      return;
    }
    const cfg = WatchPreferences.load(ctx);
    if (cfg.host.length > 0) {
      this.hostInput = cfg.host;
    }
    this.portInput = `${cfg.port}`;
    this.autoSnapshot = cfg.autoRefreshSnapshot;
  }

  private getHostContext(): common.UIAbilityContext | undefined {
    const host = this.getUIContext().getHostContext();
    return host ? (host as common.UIAbilityContext) : undefined;
  }

  private buildConfig(): WatchConnectionConfig {
    const cfg = new WatchConnectionConfig();
    cfg.host = this.hostInput.trim();
    const parsedPort = Number.parseInt(this.portInput.trim(), 10);
    cfg.port = Number.isNaN(parsedPort) || parsedPort <= 0 || parsedPort > 65535 ? 8765 : parsedPort;
    cfg.autoRefreshSnapshot = this.autoSnapshot;
    return cfg;
  }

  private saveOnly(): void {
    const ctx = this.getHostContext();
    if (!ctx) {
      return;
    }
    const cfg = this.buildConfig();
    WatchPreferences.save(ctx, cfg);
    this.getUIContext().getPromptAction().showToast({
      message: '已保存手机地址',
      duration: 1500
    });
    this.getUIContext().getRouter().back();
  }

  private async testAndSave(): Promise<void> {
    if (this.testing) {
      return;
    }
    const ctx = this.getHostContext();
    if (!ctx) {
      return;
    }
    const cfg = this.buildConfig();
    const baseUrl = cfg.getBaseUrl();
    if (baseUrl.length === 0) {
      this.statusMessage = '请先输入手机 IP 地址';
      return;
    }
    this.testing = true;
    this.statusMessage = '正在测试连接…';
    try {
      const snap = await WatchApiClient.fetchStatus(baseUrl);
      WatchPreferences.save(ctx, cfg);
      this.getUIContext().getPromptAction().showToast({
        message: `已连接：${snap.babyName}`,
        duration: 1600
      });
      this.getUIContext().getRouter().back();
    } catch (err) {
      this.statusMessage = `连接失败，可先仅保存稍后重试`;
      console.warn(`WatchConfigPage testAndSave failed: ${err}`);
    } finally {
      this.testing = false;
    }
  }

  build() {
    Scroll() {
      Column({ space: 8 }) {
        Text('连接手机端看护')
          .fontSize(14)
          .fontWeight(FontWeight.Bold)
          .fontColor('#FFFFFF')

        TextInput({ placeholder: '192.168.1.100', text: this.hostInput })
          .width('86%')
          .height(34)
          .fontSize(12)
          .fontColor('#FFFFFF')
          .placeholderColor('#64748B')
          .backgroundColor('#161D2F')
          .borderRadius(12)
          .onChange((val: string) => {
            this.hostInput = val;
          })

        // Quick LAN subnet prefix buttons for easy wrist input
        Row({ space: 4 }) {
          Button('192.168.1.')
            .fontSize(9)
            .height(24)
            .padding({ left: 6, right: 6 })
            .backgroundColor('#1D263B')
            .fontColor('#CBD5E1')
            .onClick(() => { this.hostInput = '192.168.1.'; })

          Button('192.168.0.')
            .fontSize(9)
            .height(24)
            .padding({ left: 6, right: 6 })
            .backgroundColor('#1D263B')
            .fontColor('#CBD5E1')
            .onClick(() => { this.hostInput = '192.168.0.'; })
        }

        Row({ space: 6 }) {
          Text('端口')
            .fontSize(11)
            .fontColor('#9AA7C2')
          TextInput({ placeholder: '8765', text: this.portInput })
            .width(86)
            .height(30)
            .fontSize(12)
            .type(InputType.Number)
            .fontColor('#FFFFFF')
            .backgroundColor('#161D2F')
            .borderRadius(10)
            .onChange((val: string) => {
              this.portInput = val;
            })
        }
        .alignItems(VerticalAlign.Center)

        Row({ space: 8 }) {
          Text('显示抓拍图')
            .fontSize(11)
            .fontColor('#CBD5E1')
          Toggle({ type: ToggleType.Switch, isOn: this.autoSnapshot })
            .selectedColor('#E07A5F')
            .onChange((isOn: boolean) => {
              this.autoSnapshot = isOn;
            })
        }
        .alignItems(VerticalAlign.Center)

        Text(this.statusMessage)
          .fontSize(10)
          .fontColor('#9AA7C2')
          .textAlign(TextAlign.Center)
          .width('82%')

        Button(this.testing ? '连接中…' : '测试并保存')
          .width('80%')
          .height(34)
          .fontSize(12)
          .fontWeight(FontWeight.Bold)
          .fontColor('#FFFFFF')
          .backgroundColor('#E07A5F')
          .borderRadius(17)
          .enabled(!this.testing)
          .onClick(() => {
            this.testAndSave();
          })

        Button('仅保存返回')
          .width('70%')
          .height(30)
          .fontSize(11)
          .fontColor('#CBD5E1')
          .backgroundColor('#1D263B')
          .borderRadius(15)
          .onClick(() => {
            this.saveOnly();
          })
      }
      .width('100%')
      .padding({ top: 24, bottom: 34, left: 16, right: 16 })
      .alignItems(HorizontalAlign.Center)
    }
    .width('100%')
    .height('100%')
    .backgroundColor('#090B10')
    .scrollBar(BarState.Off)
  }
}
