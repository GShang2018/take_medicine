# 吃药提醒

到点就响的安卓服药闹钟：用系统闹钟通道提醒，锁屏也能看到；支持多账户、`.ics` 导入与网址订阅、系统日历同步。数据只保存在本机，不上传、无广告。

- 产品介绍与下载页：[index.html](index.html)
- 完整使用说明：[README-安卓版.md](README-安卓版.md)
- 网页版 .ics 生成器：[web.html](web.html)
- 发版配置：[download.js](download.js)（版本号、APK 文件名、大小、SHA-256、更新说明）

## 目录

| 路径 | 说明 |
| --- | --- |
| `android/` | Android 源码（Gradle 工程，JDK 17 + SDK 34） |
| `index.html` | 产品介绍 / 下载首页 |
| `web.html` | 网页版服药日历生成器 |
| `assets/` | 页面用截图 |
| `download.js` | 下载信息配置 |
| `dist/` | 发版 APK 输出目录（当前版本已提交进仓库，页面可直接下载） |

## 构建

```bat
cd android
set JAVA_HOME=C:\path\to\jdk-17
gradlew.bat assembleRelease
```

构建成功后，APK 会自动复制并重命名为 `dist/吃药提醒-<版本名>.apk`。

`.gitignore` 默认忽略 `*.apk`，发布新版本后需要手动把新安装包加入仓库：

```bat
git add -f dist/吃药提醒-<版本名>.apk
```
