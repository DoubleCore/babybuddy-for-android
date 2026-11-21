/**
 * Data contract matching Android `BabyWatchServer.kt` (`/api/status`).
 */
export interface WatchStatusPayload {
  babyName?: string;
  monitoring?: boolean;
  targetDetected?: boolean;
  poseCode?: string;
  poseLabel?: string;
  anglesSummary?: string;
  lastAngleDeltaDeg?: number;
  angleThresholdDeg?: number;
  changeCount?: number;
  threshold?: number;
  windowSec?: number;
  motionScore?: number;
  zoomRatio?: number;
  alarmRinging?: boolean;
  snoozedRemainingSec?: number;
  lastWakeTimestampMs?: number;
  hasSnapshot?: boolean;
  recentHistory?: Array<string>;
}

export class WatchMonitorStatus {
  public babyName: string = '宝宝';
  public monitoring: boolean = false;
  public targetDetected: boolean = false;
  public poseCode: string = 'none';
  public poseLabel: string = '等待连接手机端';
  public anglesSummary: string = '';
  public lastAngleDeltaDeg: number = 0;
  public angleThresholdDeg: number = 25;
  public changeCount: number = 0;
  public threshold: number = 5;
  public windowSec: number = 30;
  public motionScore: number = 0;
  public zoomRatio: number = 2.0;
  public alarmRinging: boolean = false;
  public snoozedRemainingSec: number = 0;
  public lastWakeTimestampMs: number = 0;
  public hasSnapshot: boolean = false;
  public recentHistory: Array<string> = [];

  public static fromPayload(raw: WatchStatusPayload): WatchMonitorStatus {
    const status = new WatchMonitorStatus();
    status.babyName = raw.babyName && raw.babyName.length > 0 ? raw.babyName : '宝宝';
    status.monitoring = Boolean(raw.monitoring);
    status.targetDetected = Boolean(raw.targetDetected);
    status.poseCode = raw.poseCode ?? 'none';
    status.poseLabel = raw.poseLabel ?? '未检测到婴儿';
    status.anglesSummary = raw.anglesSummary ?? '';
    status.lastAngleDeltaDeg = typeof raw.lastAngleDeltaDeg === 'number' ? raw.lastAngleDeltaDeg : 0;
    status.angleThresholdDeg = typeof raw.angleThresholdDeg === 'number' && raw.angleThresholdDeg > 0 ? raw.angleThresholdDeg : 25;
    status.changeCount = typeof raw.changeCount === 'number' ? raw.changeCount : 0;
    status.threshold = typeof raw.threshold === 'number' && raw.threshold > 0 ? raw.threshold : 5;
    status.windowSec = typeof raw.windowSec === 'number' && raw.windowSec > 0 ? raw.windowSec : 30;
    status.motionScore = typeof raw.motionScore === 'number' ? raw.motionScore : 0;
    status.zoomRatio = typeof raw.zoomRatio === 'number' && raw.zoomRatio >= 1.0 ? raw.zoomRatio : 1.0;
    status.alarmRinging = Boolean(raw.alarmRinging);
    status.snoozedRemainingSec = typeof raw.snoozedRemainingSec === 'number' ? raw.snoozedRemainingSec : 0;
    status.lastWakeTimestampMs = typeof raw.lastWakeTimestampMs === 'number' ? raw.lastWakeTimestampMs : 0;
    status.hasSnapshot = Boolean(raw.hasSnapshot);
    status.recentHistory = Array.isArray(raw.recentHistory) ? raw.recentHistory : [];
    return status;
  }
}

export class WatchConnectionConfig {
  public host: string = '';
  public port: number = 8765;
  public autoRefreshSnapshot: boolean = true;

  public getBaseUrl(): string {
    const trimmed = this.host.trim();
    if (trimmed.length === 0) {
      return '';
    }
    if (trimmed.startsWith('http://') || trimmed.startsWith('https://')) {
      return trimmed.endsWith('/') ? trimmed.slice(0, trimmed.length - 1) : trimmed;
    }
    const cleanPort = this.port > 0 && this.port <= 65535 ? this.port : 8765;
    return `http://${trimmed}:${cleanPort}`;
  }
}
