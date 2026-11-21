import { common } from '@kit.AbilityKit';
import { BusinessError } from '@kit.BasicServicesKit';
import { image } from '@kit.ImageKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { WatchConnectionConfig, WatchMonitorStatus } from '../model/WatchMonitorModels';
import { WatchAlarmHaptics } from '../service/WatchAlarmHaptics';
import { WatchApiClient } from '../service/WatchApiClient';
import { WatchPreferences } from '../service/WatchPreferences';

const DOMAIN: number = 0x0000;
const TAG: string = 'WatchHomePage';

@Entry
@Component
struct WatchHomePage {
  @State status: WatchMonitorStatus = new WatchMonitorStatus();
  @State connected: boolean = false;
  @State hostAddress: string = '';
  @State errorHint: string = '点击下方设置连接手机端';
  @State snapshotPixelMap: image.PixelMap | undefined = undefined;
  @State autoSnapshot: boolean = true;
  @State isBusyAction: boolean = false;

  private haptics: WatchAlarmHaptics = new WatchAlarmHaptics();
  private pollTimerId: number = -1;
  private pollTick: number = 0;
  private isPollingRequest: boolean = false;

  aboutToAppear(): void {
    this.reloadConfigAndStart();
  }

  onPageShow(): void {
    this.reloadConfigAndStart();
  }

  onPageHide(): void {
    this.stopPolling();
    this.haptics.stopWakeAlarm();
  }

  aboutToDisappear(): void {
    this.stopPolling();
    this.haptics.stopWakeAlarm();
    this.releaseSnapshot();
  }

  private reloadConfigAndStart(): void {
    const context = this.getHostContext();
    if (!context) {
      return;
    }
    const cfg: WatchConnectionConfig = WatchPreferences.load(context);
    this.hostAddress = cfg.getBaseUrl();
    this.autoSnapshot = cfg.autoRefreshSnapshot;
    if (this.hostAddress.length === 0) {
      this.connected = false;
      this.errorHint = '尚未配置手机端 IP';
      this.stopPolling();
      this.haptics.stopWakeAlarm();
      return;
    }
    this.startPolling();
  }

  private startPolling(): void {
    this.stopPolling();
    this.pollTick = 0;
    this.pollOnce();
    this.pollTimerId = setInterval(() => {
      this.pollOnce();
    }, 1000);
  }

  private stopPolling(): void {
    if (this.pollTimerId !== -1) {
      clearInterval(this.pollTimerId);
      this.pollTimerId = -1;
    }
  }

  private async pollOnce(): Promise<void> {
    if (this.isPollingRequest || this.hostAddress.length === 0) {
      return;
    }
    this.isPollingRequest = true;
    try {
      const nextStatus: WatchMonitorStatus = await WatchApiClient.fetchStatus(this.hostAddress);
      this.applyStatus(nextStatus);
      this.connected = true;
      this.errorHint = '';

      this.pollTick += 1;
      if (this.autoSnapshot && nextStatus.hasSnapshot && (this.pollTick % 2 === 1 || nextStatus.alarmRinging)) {
        const frame: image.PixelMap | undefined = await WatchApiClient.fetchSnapshotPixelMap(this.hostAddress);
        if (frame) {
          this.replaceSnapshot(frame);
        }
      }
    } catch (e) {
      this.connected = false;
      this.errorHint = '无法连上手机端，请确认同一 Wi-Fi';
      this.haptics.stopWakeAlarm();
      hilog.warn(DOMAIN, TAG, 'pollOnce error: %{public}s', `${e}`);
    } finally {
      this.isPollingRequest = false;
    }
  }

  private applyStatus(next: WatchMonitorStatus): void {
    this.status = next;
    if (next.alarmRinging) {
      this.haptics.startWakeAlarm();
    } else {
      this.haptics.stopWakeAlarm();
    }
  }

  private replaceSnapshot(nextFrame: image.PixelMap): void {
    const oldFrame = this.snapshotPixelMap;
    this.snapshotPixelMap = nextFrame;
    if (oldFrame) {
      oldFrame.release().catch(() => {});
    }
  }

  private releaseSnapshot(): void {
    if (this.snapshotPixelMap) {
      this.snapshotPixelMap.release().catch(() => {});
      this.snapshotPixelMap = undefined;
    }
  }

  private getRingColor(): string {
    if (!this.connected) {
      return '#2C374E';
    }
    if (this.status.alarmRinging) {
      return '#FF3B5C';
    }
    if (this.status.poseCode === 'limb_active' || this.status.changeCount >= Math.max(2, this.status.threshold - 1)) {
      return '#FF9F1C';
    }
    if (this.status.monitoring) {
      return '#2EC4B6';
    }
    return '#4A5D82';
  }

  private getStatusSummary(): string {
    if (!this.connected) {
      return '未连接';
    }
    if (this.status.alarmRinging) {
      return '🚨 宝宝即将醒来';
    }
    if (this.status.snoozedRemainingSec > 0) {
      return `已延后 ${this.status.snoozedRemainingSec}s`;
    }
    return this.status.monitoring ? '看护运行中' : '手机未开摄像头';
  }

  private async runRemoteAction(actionName: string, task: () => Promise<WatchMonitorStatus>): Promise<void> {
    if (this.isBusyAction || this.hostAddress.length === 0) {
      return;
    }
    this.isBusyAction = true;
    this.haptics.tapConfirm();
    try {
      const updated = await task();
      this.applyStatus(updated);
      this.connected = true;
      this.showToast(`${actionName}成功`);
    } catch (err) {
      hilog.warn(DOMAIN, TAG, '%{public}s failed: %{public}s', actionName, `${err}`);
      this.showToast(`${actionName}失败`);
    } finally {
      this.isBusyAction = false;
    }
  }

  private openConfigPage(): void {
    try {
      this.getUIContext().getRouter().pushUrl({
        url: 'pages/WatchConfigPage'
      }).catch((err: BusinessError) => {
        hilog.error(DOMAIN, TAG, 'pushUrl WatchConfigPage failed: %{public}d %{public}s', err.code, err.message);
      });
    } catch (e) {
      const bErr = e as BusinessError;
      hilog.error(DOMAIN, TAG, 'openConfigPage exception: %{public}d', bErr.code);
    }
  }

  private getHostContext(): common.UIAbilityContext | undefined {
    const host = this.getUIContext().getHostContext();
    return host ? (host as common.UIAbilityContext) : undefined;
  }

  private showToast(msg: string): void {
    try {
      this.getUIContext().getPromptAction().showToast({
        message: msg,
        duration: 1600
      });
    } catch (err) {
      hilog.warn(DOMAIN, TAG, 'showToast failed: %{public}s', `${err}`);
    }
  }

  build() {
    Stack() {
      Scroll() {
        Column({ space: 8 }) {
          // Top status pill (safe margin for round watch dial)
          Text(`${this.status.babyName} · ${this.getStatusSummary()}`)
            .fontSize(11)
            .fontColor(this.status.alarmRinging ? '#FFE4E8' : '#A9B6D0')
            .backgroundColor(this.status.alarmRinging ? '#5C1122' : '#182032')
            .padding({ left: 10, right: 10, top: 4, bottom: 4 })
            .borderRadius(999)
            .maxLines(1)

          // Current pose headline + joint angle delta
          Text(this.connected ? `${this.status.poseLabel} · Δ${Math.round(this.status.lastAngleDeltaDeg)}°` : '宝宝看护手表端')
            .fontSize(17)
            .fontWeight(FontWeight.Bold)
            .fontColor(this.status.alarmRinging ? '#FF6B84' : '#FFFFFF')
            .textAlign(TextAlign.Center)

          if (this.connected && this.status.anglesSummary.length > 0) {
            Text(this.status.anglesSummary)
              .fontSize(10)
              .fontColor('#7CE7DB')
              .textAlign(TextAlign.Center)
              .width('86%')
              .maxLines(2)
          }

          // Wake-up alarm emergency actions (front & center when ringing)
          if (this.status.alarmRinging) {
            Column({ space: 6 }) {
              Button('我已醒 · 停闹')
                .width('86%')
                .height(36)
                .fontSize(13)
                .fontWeight(FontWeight.Bold)
                .fontColor('#FFFFFF')
                .backgroundColor('#FF3B5C')
                .borderRadius(18)
                .enabled(!this.isBusyAction)
                .onClick(() => {
                  this.runRemoteAction('停闹', () => WatchApiClient.dismissAlarm(this.hostAddress));
                })

              Button('延后 3 分钟')
                .width('78%')
                .height(32)
                .fontSize(12)
                .fontColor('#FFE1B5')
                .backgroundColor('#3C2810')
                .borderRadius(16)
                .enabled(!this.isBusyAction)
                .onClick(() => {
                  this.runRemoteAction('延后', () => WatchApiClient.snoozeAlarm(this.hostAddress));
                })
            }
            .width('100%')
            .alignItems(HorizontalAlign.Center)
          }

          // Live crib snapshot card
          if (this.connected && this.snapshotPixelMap) {
            Image(this.snapshotPixelMap)
              .width('76%')
              .height(92)
              .objectFit(ImageFit.Cover)
              .borderRadius(14)
              .border({ width: 1.5, color: this.getRingColor() })
          }

          // Metrics row
          if (this.connected) {
            Row({ space: 6 }) {
              Column({ space: 2 }) {
                Text('姿态频变')
                  .fontSize(10)
                  .fontColor('#8F9DB8')
                Text(`${this.status.changeCount}/${this.status.threshold}次`)
                  .fontSize(12)
                  .fontWeight(FontWeight.Bold)
                  .fontColor('#F4F6FB')
              }
              .layoutWeight(1)
              .padding({ top: 6, bottom: 6 })
              .backgroundColor('#141A29')
              .borderRadius(12)

              Column({ space: 2 }) {
                Text('远距变焦')
                  .fontSize(10)
                  .fontColor('#8F9DB8')
                Text(`${this.status.zoomRatio.toFixed(1)}x`)
                  .fontSize(12)
                  .fontWeight(FontWeight.Bold)
                  .fontColor('#7CE7DB')
              }
              .layoutWeight(1)
              .padding({ top: 6, bottom: 6 })
              .backgroundColor('#141A29')
              .borderRadius(12)
            }
            .width('84%')

            // Remote zoom controls
            Row({ space: 8 }) {
              Button('变焦 -')
                .layoutWeight(1)
                .height(30)
                .fontSize(11)
                .fontColor('#E5ECF8')
                .backgroundColor('#1D263B')
                .borderRadius(15)
                .onClick(() => {
                  this.runRemoteAction('缩小画面', () => WatchApiClient.adjustZoom(this.hostAddress, -0.5));
                })

              Button('变焦 +')
                .layoutWeight(1)
                .height(30)
                .fontSize(11)
                .fontColor('#E5ECF8')
                .backgroundColor('#1D263B')
                .borderRadius(15)
                .onClick(() => {
                  this.runRemoteAction('放大画面', () => WatchApiClient.adjustZoom(this.hostAddress, 0.5));
                })
            }
            .width('84%')

            if (!this.status.alarmRinging) {
              Button('测试唤醒告警')
                .width('78%')
                .height(30)
                .fontSize(11)
                .fontColor('#FCD34D')
                .backgroundColor('#2B2313')
                .borderRadius(15)
                .onClick(() => {
                  this.runRemoteAction('测试告警', () => WatchApiClient.testAlarm(this.hostAddress));
                })
            }

            if (this.status.recentHistory.length > 0) {
              Text(`轨迹：${this.status.recentHistory[0]}`)
                .fontSize(10)
                .fontColor('#8F9DB8')
                .textAlign(TextAlign.Center)
                .width('82%')
                .maxLines(2)
            }
          } else {
            Text(this.errorHint)
              .fontSize(11)
              .fontColor('#9AA7C2')
              .textAlign(TextAlign.Center)
              .width('80%')
              .margin({ top: 4, bottom: 4 })
          }

          // Server connection setup button
          Button(this.hostAddress.length > 0 ? '修改手机地址' : '连接手机端')
            .width('72%')
            .height(32)
            .fontSize(12)
            .fontColor('#FFFFFF')
            .backgroundColor('#E07A5F')
            .borderRadius(16)
            .margin({ top: 4 })
            .onClick(() => {
              this.openConfigPage();
            })

          if (this.hostAddress.length > 0) {
            Text(this.hostAddress)
              .fontSize(9)
              .fontColor('#64748B')
              .maxLines(1)
          }
        }
        .width('100%')
        .padding({ top: 24, bottom: 34, left: 16, right: 16 })
        .alignItems(HorizontalAlign.Center)
      }
      .width('100%')
      .height('100%')
      .scrollBar(BarState.Off)
    }
    .width('100%')
    .height('100%')
    .backgroundColor(this.status.alarmRinging ? '#220911' : '#090B10')
    .border({ width: 3, color: this.getRingColor(), radius: 999 })
  }
}
