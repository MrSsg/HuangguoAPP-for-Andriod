# 热更新运行与发布

## 结构

- HotUpdateManager：后台下载、ECDSA 验签、SHA-256 校验、ZIP 路径与体积校验、兼容范围、试用确认、回退、本地资源读取。
- SiteProfile：每个页面或播放器会话固定一份规则，集中处理域名、路由、字段、选择器和封面参数。
- MainActivity：WebViewAssetLoader 将本地可信资源映射到 appassets.androidplatform.net；只允许本地可信入口导航，HTML CSP 禁止远程脚本和 iframe。
- hot-runtime.js：APK 固定的主题和健康引导；runtime.js 的 HTTPS 内容由原生生成。
- Hot Updates/publish.py：本机构建、签名与独立 GitHub Release 发布，默认主题不改变现有外观。

资源目录使用 app/files/hot-updates，活动状态用 AtomicFile 写入。新包先处于 pending；会话开始时才启用并标记 trial。app.js 初始化结束调用 hgBootReady。15 秒内未确认启动则回退；进程在确认前退出，下次启动也回退。最后可用版本和内置资源保留。失败 revision 被阻止再次启用，发布修复或回退时必须递增 revision。

下载在后台，不阻塞启动。自动检查间隔至少 5 分钟；新包不强制刷新当前页面或播放器。下一次恢复应用且首页/设置页空闲时启用，或用户在设置主动启用。表单、年龄确认、详情或播放期间保持旧会话。

站点规则 fingerprint 改变时清除目录缓存。封面缓存包含规则版本与域名；收藏/进度独立保存。每次播放仍使用进入时的规则和来源，资源下载不打断播放。

## 发布

独立仓库地址：https://github.com/MrSsg/HuangguoAPP-HotUpdates

热更新私钥保存在 LOCALAPPDATA/HuangGuoSigning/hot-update-private.pem。APK 仅嵌入公钥。现有 APK 签名密钥不变。

```powershell
# 在 Hot Updates 目录：编辑 site.json/theme.json/release.json，递增 revision
python publish.py --web --publish
```

普通主题/规则包可以省略 --web，完整界面需保留健康确认协议。使用正常 APK 仓库的 latest 下载地址只发布 android-update.json；热更新包使用独立仓库的 latest/hot-update.json，互不覆盖。

## 回归

```powershell
.\gradlew.bat -PplaybackQa :app:connectedDebugAndroidTest --console=plain
```

独立测试包使用本地签名样本，不含私钥。覆盖验签、包摘要、兼容性、解压路径、试用回退与阻止重放、解析规则变化、封面域名重写、可信 WebView 来源、实际主题启用、坏界面包回退以及观看进度保留。原有 6 项播放回归也继续运行。

## 范围

支持域名与已有解析/解密规则变化，以及主题和完整前端。新加密算法、原生控件新能力和播放器库升级仍需要 APK。资源包版本与 APK 版本各自递增。主题起止时间需要 ISO 8601 时区；到期恢复普通外观。
