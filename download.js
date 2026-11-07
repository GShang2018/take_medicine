/**
 * 吃药提醒 · 下载配置
 * ------------------------------------------------------------
 * 发新版本时只改这个文件，首页所有下载相关的地方都会自动更新：
 *   顶部「下载 APK」按钮、下载卡片的版本/日期/大小/包名、
 *   更新说明、SHA-256、页脚版本号。
 *
 * 需要替换的字段：
 *   version      版本名，例如 1.5.11
 *   versionCode  整数版本号，例如 25
 *   file         相对页面的 APK 路径，发版后放在 dist/ 下
 *   url          可选：APK 放在别的地址时填完整 URL，留空则用 file
 *   size         显示用的大小，例如 4.4 MB
 *   released     更新日期，例如 2026-11-07
 *   minAndroid   最低系统版本，例如 Android 8.0
 *   packageName  包名
 *   sha256       APK 的 SHA-256（可用 certutil -hashfile 文件 SHA256 生成）
 *   updateNote   本次更新说明
 *
 * 提示：在 android/ 里执行 `gradlew assembleRelease`，APK 会自动复制到
 * 项目根目录的 dist/，把生成的文件名填到下面的 file 即可。
 * ------------------------------------------------------------
 */
window.APP_DOWNLOAD = {
  version: "1.5.11",
  versionCode: 25,
  file: "dist/吃药提醒-1.5.11.apk",
  url: "",
  size: "4.4 MB",
  released: "2026-11-07",
  minAndroid: "Android 8.0",
  packageName: "com.medremind.app",
  sha256: "304e812f6b34722b745b151ce4836cded74233584851b18a44d6248bd7a9046b",
  updateNote: "修复首页没有药品时，提示文字让你去找一个并不存在的「+」按钮；现在提示会引导到「药品」页，点一下就能直接跳过去添加。"
};
