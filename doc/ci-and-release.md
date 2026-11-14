# CI 与发布

## 自动构建

推送 main/master、feat/**、ci/**，以及所有 PR，自动运行 `CI checks`；也可手动运行。
环境统一为 Ubuntu 24.04、JDK 21、Python 3.12、Android 37.0（兼容 AGP 的 android-37 路径）、NDK 27.3.13750724。
执行 debug APK 编译、离线单元测试、lint 和所有语言目录的翻译检查。
CI 额外使用一次性测试密钥验证 release APK/AAB 编译及签名；这些验证产物不发布、不供安装，只有 debug APK 上传为测试下载。
需要真实 Baby Buddy 服务的 ClientV2IntegrationTest 通过项目既有 skipIntegrationTests 参数排除；没有运行设备/UI 测试。
项目原有 lint.abortOnError=false，因此 lint 报告保留，但 lint 问题不会阻断构建。
成功后在 Actions → 对应运行 → Artifacts 下载 debug APK；报告保留 14 天，APK 保留 30 天。
测试包使用 Android debug 签名，不应作为稳定发布签名。

## 正式发布

Release 使用固定的生产签名密钥，不生成临时发布证书，避免下一版本不能覆盖升级。
在 Settings → Secrets and variables → Actions 配置：

| Secret | 内容 |
| --- | --- |
| SIGNING_KEYSTORE_BASE64 | 原有发布 keystore 的 Base64 内容 |
| SIGNING_KEYSTORE_PASSWORD | keystore 密码 |
| SIGNING_KEY_ALIAS | 密钥别名 |
| SIGNING_KEY_PASSWORD | 私钥密码；留空时使用 keystore 密码 |

保管并备份同一份 keystore，后续版本复用。不要把密钥、密码或 Base64 文件提交到 Git。
缺少必要 Secrets 时，Release 会在编译前明确失败；需要体验安装时直接下载 CI debug APK。
密钥通过环境变量传入 Gradle，不写入工作流 outputs 或命令行参数；临时 keystore 无论成功失败均删除。

发布步骤：

1. 修改 app/build.gradle 的 versionName 和递增 versionCode。
2. 将修改合入 main，确认 CI 通过。
3. 对该提交推送版本标签，例如 versionName 为 2.6.5 时推送 v2.6.5。
4. 自动编译、单元测试、翻译检查、APK 签名校验通过后，发布 APK、AAB 和 SHA256SUMS.txt。

也可在 Actions → Release → Run workflow 指定一个已存在的版本标签，默认创建草稿；可关闭 AAB 或选择 prerelease。
标签必须与实际编译源码的 versionName 一致。手动执行构建的是标签提交，不是下拉框所选分支的最新源码。
使用包含本工作流及共享 setup-build action 的新标签。历史版本标签不适用新的构建环境。
带 `-` 后缀的版本自动标记为 prerelease。发布 job 单独拥有 contents:write，编译 job 仅有读取权限。
发布不会覆盖已存在的同名附件；版本变更请使用新的版本号和标签。
GitHub Release 下载包与 Actions Artifact 均包含校验和；第三方版权声明附在 Release 说明中。
