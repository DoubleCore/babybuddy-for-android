import { BusinessError } from '@kit.BasicServicesKit';
import { image } from '@kit.ImageKit';
import { http } from '@kit.NetworkKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { WatchMonitorStatus, WatchStatusPayload } from '../model/WatchMonitorModels';

const DOMAIN: number = 0x0000;
const TAG: string = 'WatchApiClient';

/**
 * HTTP client targeting Android/LAN `BabyWatchServer` endpoints:
 * - GET  /api/status
 * - GET  /api/snapshot.jpg
 * - POST /api/alarm/dismiss
 * - POST /api/alarm/snooze
 * - POST /api/alarm/test
 * - POST /api/zoom?delta=...
 */
export class WatchApiClient {
  public static async fetchStatus(baseUrl: string): Promise<WatchMonitorStatus> {
    return WatchApiClient.requestStatusJson(`${baseUrl}/api/status`, http.RequestMethod.GET);
  }

  public static async dismissAlarm(baseUrl: string): Promise<WatchMonitorStatus> {
    return WatchApiClient.requestStatusJson(`${baseUrl}/api/alarm/dismiss`, http.RequestMethod.POST);
  }

  public static async snoozeAlarm(baseUrl: string): Promise<WatchMonitorStatus> {
    return WatchApiClient.requestStatusJson(`${baseUrl}/api/alarm/snooze`, http.RequestMethod.POST);
  }

  public static async testAlarm(baseUrl: string): Promise<WatchMonitorStatus> {
    return WatchApiClient.requestStatusJson(`${baseUrl}/api/alarm/test`, http.RequestMethod.POST);
  }

  public static async adjustZoom(baseUrl: string, delta: number): Promise<WatchMonitorStatus> {
    const formatted = delta.toFixed(1);
    return WatchApiClient.requestStatusJson(`${baseUrl}/api/zoom?delta=${formatted}`, http.RequestMethod.POST);
  }

  public static async fetchSnapshotPixelMap(baseUrl: string): Promise<image.PixelMap | undefined> {
    const httpRequest: http.HttpRequest = http.createHttp();
    try {
      const options: http.HttpRequestOptions = {
        method: http.RequestMethod.GET,
        expectDataType: http.HttpDataType.ARRAY_BUFFER,
        connectTimeout: 4000,
        readTimeout: 4000
      };
      const resp: http.HttpResponse = await httpRequest.request(`${baseUrl}/api/snapshot.jpg?t=${Date.now()}`, options);
      if (resp.responseCode !== 200 || !resp.result) {
        return undefined;
      }
      const buffer: ArrayBuffer = resp.result as ArrayBuffer;
      if (buffer.byteLength === 0) {
        return undefined;
      }
      const imageSource: image.ImageSource = image.createImageSource(buffer);
      try {
        const pixelMap: image.PixelMap = await imageSource.createPixelMap();
        return pixelMap;
      } finally {
        imageSource.release();
      }
    } catch (e) {
      const bErr = e as BusinessError;
      hilog.warn(DOMAIN, TAG, 'fetchSnapshot failed: %{public}d %{public}s', bErr.code, bErr.message);
      return undefined;
    } finally {
      httpRequest.destroy();
    }
  }

  private static async requestStatusJson(url: string, method: http.RequestMethod): Promise<WatchMonitorStatus> {
    const httpRequest: http.HttpRequest = http.createHttp();
    try {
      const options: http.HttpRequestOptions = {
        method: method,
        expectDataType: http.HttpDataType.STRING,
        connectTimeout: 4000,
        readTimeout: 4000
      };
      const resp: http.HttpResponse = await httpRequest.request(url, options);
      if (resp.responseCode < 200 || resp.responseCode >= 300) {
        throw new Error(`HTTP ${resp.responseCode}`);
      }
      const text: string = typeof resp.result === 'string' ? resp.result : '';
      const parsed: WatchStatusPayload = JSON.parse(text) as WatchStatusPayload;
      return WatchMonitorStatus.fromPayload(parsed);
    } finally {
      httpRequest.destroy();
    }
  }
}
