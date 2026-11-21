# Baby Buddy 鸿蒙版（HarmonyOS NEXT 手机/平板 + 手表双端）

本目录为 Baby Buddy 本地离线育儿手账的 **HarmonyOS NEXT（API 12+ / Stage 模型）** 多端适配工程，依据华为开发者联盟 HarmonyOS 官方应用开发文档构建，包含两个独立的 `entry` HAP 模块：

| 模块 | 目标设备 (`deviceTypes`) | 架构方案 | 核心能力 |
| :--- | :--- | :--- | :--- |
| `entry` | `phone`, `tablet` | **ArkWeb (`@kit.ArkWeb`) + 原生桥接** | 完整复用 `../web/` 离线育儿记录、WHO 曲线与姿态看护实验页，原生接入系统文件保存 (`@kit.CoreFileKit`)、马达强振 (`@kit.SensorServiceKit`)、相机动态授权 (`@kit.AbilityKit`)、沉浸式状态栏与安全区避让 (`@kit.ArkUI`) |
| `wearable` | `wearable` | **纯原生 ArkTS / ArkUI** | 适配圆形表盘 OLED 深色界面，通过局域网 HTTP (`@kit.NetworkKit` + `@kit.ImageKit`) 直连手机端 `BabyWatchServer`（默认端口 `8765`），提供实时姿态环、婴儿床远距抓拍图、苏醒告警强震、一键停闹/延后与远程变焦 |

---

## 1. 同步前端静态资源

每次修改根目录 `web/` 下的网页代码后，请先执行同步脚本将其更新到鸿蒙手机端的 `rawfile/web/` 目录，并可执行静态校验：

```bash
node harmony-app/scripts/sync-web.mjs
node harmony-app/scripts/verify-harmony.mjs
```

---

## 2. 使用 DevEco Studio 构建与运行

1. 安装 **DevEco Studio 5.0+**（配套 HarmonyOS 5.0.0 / API 12 及以上 SDK）。
2. 在 DevEco Studio 中选择 **File -> Open**，打开本仓库中的 `harmony-app` 目录。
3. 等待 Sync 完成（如使用更高版本的 DevEco Studio，可按提示将 `oh-package.json5` 与 `hvigor/hvigor-config.json5` 中的 `modelVersion` 同步升级）。
4. 打开 **File -> Project Structure -> Signing Configs**，登录华为开发者账号并勾选 **Automatically generate signature** 自动生成调试签名。
5. 选择运行目标：
   - 手机 / 平板真机或模拟器：运行配置选择 **`entry`** 模块。
   - 鸿蒙手表（如 HUAWEI WATCH 5 / Wearable 模拟器）：运行配置选择 **`wearable`** 模块。

命令行构建（需已配置 DevEco Studio 内置 `hvigorw` 与 SDK 环境变量）：

```bash
cd harmony-app
hvigorw assembleHap --mode module -p module=entry@default -p buildMode=debug --no-daemon
hvigorw assembleHap --mode module -p module=wearable@default -p buildMode=debug --no-daemon
```

---

## 3. 手机端 + 鸿蒙手表端联调婴儿看护

1. 确保手机与鸿蒙手表连接在**同一局域网 Wi-Fi**。
2. 在 Android 手机端 Baby Buddy 中打开 **姿态监测与看护**，记下界面中显示的局域网地址（例如 `http://192.168.1.105:8765/watch`）。
3. 在鸿蒙手表端打开 **宝宝看护 (`Baby Watch`)**，点击 **连接手机端**，输入手机 IP（如 `192.168.1.105`）和端口 `8765`，点击 **测试并保存**。
4. 连接成功后，手表将每秒同步手机端姿态状态、每 2 秒刷新婴儿床远距抓拍图；当宝宝姿态频变触发苏醒告警时，手表会立即进入红环强震模式，并支持抬腕一键 **我已醒 · 停闹**、**延后 3 分钟** 以及远程 **变焦 +/-**。

---

## 4. 权限与安全说明

- `ohos.permission.INTERNET`：手机端用于联网加载字体/图标或访问局域网监控源；手表端用于局域网访问手机 `BabyWatchServer` 接口（已通过 `resources/base/profile/network_config.json` 配置 `cleartextTrafficPermitted: true` 以允许局域网 HTTP 通信）。
- `ohos.permission.VIBRATE`：用于婴儿苏醒告警时的原生强效马达振动提醒。
- `ohos.permission.CAMERA`（仅手机/平板端，`user_grant`）：已在 `entry/src/main/module.json5` 配置 `reason` 与 `usedScene`，并仅在用户打开姿态检测摄像头时通过 `abilityAccessCtrl` 动态向用户申请。
- 本地备份导出使用 `@kit.CoreFileKit` 的 `DocumentViewPicker`，由用户亲自选择保存目录，无需申请敏感存储权限。
