# DownloadProviderMod

> 小米系统下载器优化模块 · Xposed Module for Xiaomi Download Manager
> Author: **RadiAsync** · License: [MIT](LICENSE)

[![Xposed API](https://img.shields.io/badge/Xposed%20API-82-blue)](https://api.xposed.info/)
[![Android](https://img.shields.io/badge/Android-14%2B-green)]()
[![Framework](https://img.shields.io/badge/Framework-Vector%20%2F%20LSPosed-purple)]()
[![License](https://img.shields.io/badge/License-MIT-lightgrey)](LICENSE)

一个针对小米 HyperOS 系统下载器（`com.android.providers.downloads.ui`）的 Xposed 模块，
移除全部广告与推广内容，并将 Microsoft Edge 的下载无缝接管到小米系统下载器，
同时把无用的「下载热榜」页改造成实时网速曲线。

## ✨ 功能

| 功能 | 说明 |
|------|------|
| 🚫 去广告 | 拦截主页横幅广告、主页/详情页推荐应用、热榜推荐、MIUI 广告 SDK 接口 |
| 🚫 广告页封禁 | 广告 WebView（`:ad` 进程）与信息流设置页直接关闭 |
| ⬇️ Edge 下载接管 | Edge 浏览器点击下载 → 自动转入小米系统下载器（DownloadProvider 引擎），Edge 不再重复下载 |
| 📈 网速曲线 | 「下载热榜」页替换为实时网速曲线（0.5s 采样，当前 / 峰值 / 平均速度） |

## 📱 兼容性

- **系统**：Xiaomi HyperOS 4.0+（Android 14+），在 OS4.0.0.15.XPMCNXM（Android 17）实测
- **Edge**：151.0.x（Chromium 151）实测；其他大版本可能需要更新 hook 点
- **框架**：Vector（JingMatrix）/ LSPosed / EdXposed，Xposed API 82

## 📦 安装

1. 从 [Releases](../../releases) 下载 `DownloadProviderMod_vX.X_RadiAsync.apk` 并安装
2. 在 Xposed 框架管理器中启用模块，作用域勾选：
   - `com.android.providers.downloads.ui`（系统下载器）
   - `com.microsoft.emmx`（Microsoft Edge）
3. 重启这两个应用（或重启手机）

> ⚠️ 需要 Root + Xposed 框架（如 Vector / LSPosed）。模块对手机无任何破坏性修改，卸载模块即可完全还原。

## 🔧 构建

环境要求：JDK 21、Android SDK 34+。

```powershell
# 工程已附带 Gradle Wrapper（8.11.1）
$env:JAVA_HOME='C:\Program Files\Java\latest\jdk-21'
.\gradlew.bat assembleRelease

# 产物：app\build\outputs\apk\release\app-release-unsigned.apk
# 签名（示例）：
apksigner sign --ks debug.keystore --out DownloadProviderMod_vX.X_RadiAsync.apk app-release-unsigned.apk
```

> 首次构建需在工程根目录创建 `local.properties` 并写入 `sdk.dir=你的AndroidSdk路径`。

## 🧩 技术实现

### 下载器去广告

| 目标类（`com.android.providers.downloads.ui` 下） | 方法 | 处理 |
|------|------|------|
| `recommend.HomePageRecommendApi` | `getBannerAdAppList(J,Str,Str,Z)` | 返回空 List |
| `recommend.HomePageRecommendApi` | `getAppSubject(Str,Z)` | 返回 null |
| `recommend.HomePageRecommendApi` | `getDetailPageRecommend(Str,Str)` | 返回空 List |
| `recommend.RankListRecommendApi` | `getRankList(Str,I)` | 返回空 List |
| `recommend.RankListRecommendApi` | `getRankRecomendList(Str,Str)` | 返回空 List |
| `recommend.AppRecommendManager` | `getHomePageRecommendApps(Z,Z)` | 返回空 List |
| `recommend.AppRecommendManager` | `getDetailPageRecommendApps(Str,Str,I)` | 返回空 List |
| `recommend.AppRecommendManager` | `getAppSubject(Z)` | 返回 null |
| `api.miuad.BaseNewMiuiRequest` | `getBaseApiUrl()` | 返回空串（广告 SDK 兜底） |
| `activity.LpWebViewActivity` | `onCreate` | `finish()` |
| `activity.InfoFlowSettingActivity` | `onCreate` | `finish()` |

### 网速曲线

热榜页 Fragment（混淆类 `I0.q`）`onResume` 时隐藏其列表（`f`）与空状态（`K`），
注入自绘 `SpeedCurveView`：每 0.5s 采样 `TrafficStats.getTotalRxBytes()` 差值绘制曲线。

### Edge 下载接管

| 目标类 | 方法 | 处理 |
|--------|------|------|
| `org.chromium.components.download.DownloadCollectionBridge` | `a(String,String,String,String)`（R8 混淆名） | 从参数识别 URL → 系统 `DownloadManager.enqueue()` → `setResult(null)` 中止 Edge 下载 |
| `org.chromium.chrome.browser.download.DownloadManagerService` | `onDownloadItemCreated(DownloadItem)` | 懒 hook 兜底 + 一次性清理 |

防误伤策略：

- `DownloadCollectionBridge.a` 仅在「新建下载」时被 native 调用（历史恢复不经过此路径），因此打开 Edge 后立即下载也能被正常接管
- 60 秒内同 URL 只入队一次（native 重试仍会继续中止 Edge 下载）
- 仅接管 `http/https/ftp`；`magnet/ed2k/blob` 由 Edge 原生处理
- 已转发 URL 持久化记录（`dpm_forwarded.txt`），避免恢复期重复入队

## ⚠️ 已知限制

1. Edge 下载被接管后，Edge 自己的下载 UI 会显示失败状态（任务已由系统下载器接管，属预期）
2. Edge / 系统 OTA 大版本更新可能改变 R8 混淆类名，需按上表重新定位
3. 网速曲线统计的是整机总下行流量（TrafficStats 全局值），非单任务速度

## 📄 免责声明

本项目仅供学习与研究使用。使用本模块产生的一切后果由使用者自行承担，请遵守当地法律法规及对应软件的服务条款。

## License

[MIT](LICENSE) © RadiAsync
