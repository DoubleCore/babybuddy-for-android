# 登录

## 账号密码登录

![](direct/help_login_page.png)

连接您的 Baby Buddy 服务器有两种方式：输入服务器地址、用户名和密码，然后点击「登录」即可连接；或者选择扫码登录（向下滑动查看扫码登录详情）。

## 扫码登录

![](direct/help_login_qrcode.png)

您也可以使用登录二维码快速连接 Baby Buddy 服务器。请先在其他设备的浏览器中登录 Baby Buddy 网页端，点击右上角的用户菜单并进入「添加设备（Add Device）」页面即可找到登录二维码。使用手机扫描该二维码，识别成功后点击「立即登录」按钮即可完成登录。

## 不安全连接提示

![](screenshots/help_insecure_http_warning.png)

如果您输入的是 http 地址而非加密的 https 地址，应用会弹出安全警告。请仅在您自己受控的局域网环境中使用 http 连接本地 Baby Buddy 服务器！

## Home Assistant Ingress

![](direct/help_login_homeassistant.png)

本应用对托管在 Home Assistant Ingress 上的 Baby Buddy 服务提供实验性支持。连接此类服务器时，必须使用「扫码登录」方式进行登录。

# 界面概览

## 界面概览

![](screenshots/help_overview_2.png)

登录后，屏幕上方为各类日常活动的快捷记录按钮，屏幕下方为最近记录的历史时间线。如果您需要同时记录多个宝宝，左右滑动屏幕即可在不同宝宝之间切换。

# 计时记录

## 启动计时器

![](direct/help_play_button_highlight.png)

点击屏幕上方任意一个计时类按钮（如喂养、睡眠、趴卧练习、吸奶），即可立即开始计时。

## 填写记录信息

![](screenshots/help_timer_interface.png)

启动计时后会展开对应的记录面板。请填写您需要记录的详细信息，当必填项选择完成后，右下角会出现保存按钮。

## 保存或取消计时

![](direct/help_save_button_highlight.png)

保存按钮出现后，点击保存按钮即可停止计时并保存记录。如果您不想保存本次计时，只需再次点击顶部的对应活动图标即可取消计时。

# 事件记录

## 记录换尿布与备注

![](screenshots/help_diaper_logging.png)

除计时类活动外，顶部按钮栏还支持直接记录无需计时的事件（例如换尿布和日常备注），操作方式与计时面板一致，填写后点击保存即可。

# 历史记录

## 历史记录管理

![](screenshots/help_events.png)

屏幕底部的时间线展示了当前所选宝宝的最新活动记录。按住某条记录约三秒钟，即可在浏览器中打开对应的 Baby Buddy 网页进行编辑；点击右侧红色的删除按钮即可删除该条记录。
