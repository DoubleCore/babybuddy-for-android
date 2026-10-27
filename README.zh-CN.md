# Baby Buddy for Android — 简体中文说明

本仓库是 [Baby Buddy](https://docs.baby-buddy.net/) 的 Android 客户端。它不是独立的婴儿记录服务：**使用前必须已有一个可以访问的 Baby Buddy 服务器**（可自行部署）。

客户端提供喂养、睡眠、趴卧练习、吸奶等活动的计时记录，支持换尿布、备注、历史记录、二维码登录和多宝宝切换。

## 简体中文界面

本分支新增了完整的简体中文界面资源，包括登录、菜单、计时及事件记录、设置、提示与错误、关于页面和应用内帮助。

- 使用简体中文的 Android 系统时，应用会自动采用简体中文界面；其他语言的设备仍使用原有的语言回退机制。
- 原版英文资源位于 `app/src/main/res/values/strings.xml`，中文资源位于 `app/src/main/res/values-zh/strings.xml`。
- 中文帮助教程位于 `resources/help_images/help-zh.md`，构建时由 `help.makefile` 生成对应的 Android 资源。
- 图片中的英文示例屏幕属于原项目的截图，并非实际界面文字。网页端、服务器返回的错误文本和第三方许可证原文不由此 Android 语言包翻译。

**注意：** 不要翻译 API 路径、JSON 字段、存储键、服务器枚举（如 `breast milk`、`left breast`）或定时器的 `-BBapp:` 标记；这些内容是与服务器兼容所必需的数据协议。

## 构建

本项目依赖 Android SDK、NDK、适配 Android Gradle 插件的 JDK、Python、pipenv、Pandoc、ImageMagick、make，以及 ZXing 子模块。请参考[英文主文档](README.md)中的完整依赖和许可证说明。

```bash
git submodule update --init --recursive
bash check-build-prerequisites.sh
./gradlew build -PskipIntegrationTests
```

本项目构建前会自动生成帮助文案、开源许可声明和图标资源，因此只有编译器还不够。`check-build-prerequisites.sh` 的 JDK 检查可能与后续更新的 Gradle 配置不完全一致，遇到版本问题请以构建脚本和 Android Gradle 插件要求为准。

若只修改了翻译，可以先运行无需 Android SDK 的静态检查：

```bash
python3 tools/verify-translation-file.py \
  app/src/main/res/values/strings.xml \
  app/src/main/res/values-zh/strings.xml
```

## 许可证与原作者

Android 客户端源代码采用 [MIT 许可证](LICENSE.md)，并非 Baby Buddy 服务端项目的 BSD-2-Clause 许可证。仓库中部分图标和媒体素材另有署名及再分发条件，详见 [ATTRIBUTIONS.md](ATTRIBUTIONS.md) 与 [3RDPARTY.md](3RDPARTY.md)。二次开发、发布 APK 或上架商店时应继续保留相关版权与许可信息。
