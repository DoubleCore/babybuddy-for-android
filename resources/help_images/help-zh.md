# 登录

## 账号密码登录

![](direct/help_login_page.png)

有两种方式可以连接 Baby Buddy 服务器。第一种是在登录界面填写服务器网址、用户名和密码，再点击“登录”。第二种是扫描登录二维码，具体操作见下一页。

## 扫描二维码登录

![](direct/help_login_qrcode.png)

先在另一台设备上登录 Baby Buddy 网页版，点击右上角的用户菜单，进入“添加设备（Add a Device）”页面，找到登录二维码。然后用手机应用扫描该二维码，识别成功后点击“立即登录”。

## 不安全的 HTTP 连接

![](screenshots/help_insecure_http_warning.png)

如果服务器地址使用 http 而非 https，应用会显示安全警告。只有在您能够控制的本地网络中，才应考虑使用非加密连接；通过公网连接时请使用 https。

## Home Assistant Ingress 登录

![](direct/help_login_homeassistant.png)

安卓版部分支持通过 Home Assistant Ingress 访问的 Baby Buddy 服务器。连接此类服务器时，需要使用二维码登录。该功能仍可能不稳定。

# 页面概览

## 主界面

![](screenshots/help_overview_2.png)

登录后，屏幕上半部分显示喂养、睡眠、换尿布等快捷记录按钮；下半部分显示最近的活动记录。如果为多个宝宝建立了档案，可以左右滑动切换宝宝。

# 使用计时记录

## 开始计时

![](direct/help_play_button_highlight.png)

点击屏幕上方的喂养、睡眠、趴卧练习或吸奶按钮，即可开始相应活动的计时。

## 填写记录

![](screenshots/help_timer_interface.png)

开始计时后，会显示该活动的详细记录界面。按需填写活动信息；完成所有必填项后，保存按钮会自动出现。

## 保存或取消计时

![](direct/help_save_button_highlight.png)

点击保存按钮即可结束计时并保存记录。如果不想保存，可以再次点击顶部对应的计时按钮来取消本次计时。

# 记录其他活动

## 尿布与备注

![](screenshots/help_diaper_logging.png)

除了计时活动，顶部按钮还可以记录换尿布、普通备注等不需要计时的事件。填写和保存方式与计时记录类似，但不会启动计时器。

# 历史记录

## 查看和删除记录

![](screenshots/help_events.png)

屏幕下方的时间线显示当前宝宝最近的活动。长按一条记录约三秒，可以在浏览器中打开 Baby Buddy 网页版的对应详情页面。若要删除记录，请点击该条目旁的红色删除按钮。
