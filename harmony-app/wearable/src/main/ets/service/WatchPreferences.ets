import { common } from '@kit.AbilityKit';
import { preferences } from '@kit.ArkData';
import { BusinessError } from '@kit.BasicServicesKit';
import { hilog } from '@kit.PerformanceAnalysisKit';
import { WatchConnectionConfig } from '../model/WatchMonitorModels';

const DOMAIN: number = 0x0000;
const TAG: string = 'WatchPreferences';
const PREF_NAME: string = 'babybuddy_watch_prefs';
const KEY_HOST: string = 'server_host';
const KEY_PORT: string = 'server_port';
const KEY_AUTO_SNAPSHOT: string = 'auto_snapshot';

export class WatchPreferences {
  public static load(context: common.UIAbilityContext): WatchConnectionConfig {
    const config = new WatchConnectionConfig();
    try {
      const options: preferences.Options = { name: PREF_NAME };
      const store: preferences.Preferences = preferences.getPreferencesSync(context, options);
      const savedHost: preferences.ValueType = store.getSync(KEY_HOST, '');
      const savedPort: preferences.ValueType = store.getSync(KEY_PORT, 8765);
      const savedSnap: preferences.ValueType = store.getSync(KEY_AUTO_SNAPSHOT, true);
      if (typeof savedHost === 'string') {
        config.host = savedHost;
      }
      if (typeof savedPort === 'number' && savedPort > 0) {
        config.port = savedPort;
      }
      if (typeof savedSnap === 'boolean') {
        config.autoRefreshSnapshot = savedSnap;
      }
    } catch (e) {
      const bErr = e as BusinessError;
      hilog.error(DOMAIN, TAG, 'load preferences failed: %{public}d %{public}s', bErr.code, bErr.message);
    }
    return config;
  }

  public static save(context: common.UIAbilityContext, config: WatchConnectionConfig): void {
    try {
      const options: preferences.Options = { name: PREF_NAME };
      const store: preferences.Preferences = preferences.getPreferencesSync(context, options);
      store.putSync(KEY_HOST, config.host.trim());
      store.putSync(KEY_PORT, config.port);
      store.putSync(KEY_AUTO_SNAPSHOT, config.autoRefreshSnapshot);
      store.flush().catch((err: BusinessError) => {
        hilog.error(DOMAIN, TAG, 'flush preferences failed: %{public}d %{public}s', err.code, err.message);
      });
    } catch (e) {
      const bErr = e as BusinessError;
      hilog.error(DOMAIN, TAG, 'save preferences failed: %{public}d %{public}s', bErr.code, bErr.message);
    }
  }
}
