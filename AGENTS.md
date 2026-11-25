# AGENTS.md 

## 1. 项目概览与技术栈

- **应用名称**：深浅壁纸 / 自动深浅壁纸 (`BYD Auto Dark Wallpaper`)
- **Application ID / Namespace**：`com.byd.autodarkwallpaper`
- **当前版本**：`versionName = "2.0.1"`，`versionCode = 2`（见 `app/build.gradle`）
- **SDK 与语言**：Java 11（无 Kotlin / Jetpack Compose，纯原生 Android View + XML 布局），`minSdk = 28`、`targetSdk = 34`、`compileSdk = 37`、`buildToolsVersion = '36.0.0'`
- **核心依赖**：
  - `androidx.media3:media3-exoplayer:1.11.1`
  - `androidx.annotation:annotation:1.9.1`
- **测试框架**：`junit:junit:4.13.2` + `org.robolectric:robolectric:4.14.1`（搭配 `org.ow2.asm:asm*:9.9.1`）

---

## 2. 比亚迪车机触发源与权限边界

- **真实触发源（实车验证结论）**：
  - 在实车（Di150VCP / Android 14 / Theme 7.0.0）测试中确认，比亚迪车机默认 `support_change_system_uimode = 0`，切换日间/夜间时**不会**同步修改标准 Android 的 `Configuration.uiMode`。
  - `VehicleStateMonitor` 通过标准 `ContentObserver` 结合 `AutoDarkMonitorService` 后台轻量轮询（`QUIET_POLL_INTERVAL_MS = 2000L`，调用 `pollQuietly()` 仅在值变化时通知）监听比亚迪系统设置键 **`Settings.Global.key_byd_night_mode`**：
    - `1` = 夜间 / 深色（`BYD_NIGHT_MODE_DARK`）
    - `2` = 日间 / 浅色（`BYD_NIGHT_MODE_LIGHT`）
  - 同时读取 `content://carsettings/global` 中的 **`time_wallpaper_mode`**（`1` = 手动夜间，`2` = 手动日间，`3` = 自动）作为 UI 档位状态提示（读取失败时不影响主判断）。
- **App 自身主题同步**：
  - `MainActivity.attachBaseContext` 调用 `VehicleStateMonitor.queryBydDarkStateOrNull` 覆写 `Configuration.uiMode`（`UI_MODE_NIGHT_YES` / `UI_MODE_NIGHT_NO`），并在 `onStart` / `onResume` 监听 `key_byd_night_mode` 与 `carsettings/global` 变化以匹配 `values/` 与 `values-night/` 双主题。
- **零私有权限原则**：
  - **无需系统签名权限，也不要引入任何 `BYDAUTO_*` 私有权限**。
---

## 3. 核心模块与业务规则

### 3.1 两种壁纸切换模式（`AppPrefs.KEY_WALLPAPER_MODE`）
1. **直接修改壁纸（`MODE_STATIC_MANAGER = 0`，默认）**：
   - 通过 `WallpaperManager.setBitmap(bitmap, null, true, WallpaperManager.FLAG_SYSTEM)` 替换系统桌面静态壁纸（仅支持图片，拦截视频）。
   - 开启总开关或从动态模式切回静态模式前，通过 `WallpaperRepository.backupCurrentSystemWallpaper` 备份原系统壁纸（`original_backup.img` + 元数据）；关闭总开关或切往动态模式时，通过 `restoreOriginalWallpaper` 自动复原。
   - 若因缺少存储读取权限或系统当前为动态壁纸/车机百变主题导致无法读取，弹窗引导授权（Android 11+ 跳转 `MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`）或允许用户选择「不备份继续」。
2. **动态壁纸模式（`MODE_LIVE_SERVICE = 1`）**：
   - 由 `AutoDarkWallpaperService`（`WallpaperService`）与 `WallpaperGlRenderer`（OpenGL ES 2.0 统一渲染管线，独立 `HandlerThread` 维护 EGL 上下文）驱动。
   - 统一渲染静态图片（`GL_TEXTURE_2D`）与 `Media3 ExoPlayer` 视频帧（`GL_TEXTURE_EXTERNAL_OES`），支持 `400ms`（`TRANSITION_DURATION_MS`）双层平滑淡入淡出过渡与横竖屏自适应 CenterCrop 裁剪。
   - 播放控制：完全禁用音轨以免干扰车机音乐与导航播报；壁纸隐藏超过 `30s`（`IDLE_PLAYER_RELEASE_DELAY_MS = 30_000L`）自动释放 `ExoPlayer` 归还硬件解码资源；播放异常时延迟 `1500ms` 重建重试（最多 `MAX_VIDEO_RETRY = 3` 次）。
   - 需用户在系统壁纸选择器中将 `AutoDarkWallpaperService` 设为系统动态壁纸（`WallpaperController.isOurLiveWallpaperActive` 检测激活状态）。

### 3.2 多壁纸库、视频预检与选取策略
- **壁纸库管理（`WallpaperRepository`）**：
  - 独立维护日间与夜间目录及 `library.json` 元数据，支持多选图片/视频导入；图片导入时自动处理 EXIF 旋转（最大边限制 `MAX_IMPORT_EDGE = 3840`），视频流式复制原文件并抽取首帧缩略图（最大边 `MAX_VIDEO_THUMB_EDGE = 960`）。
  - `AutoDarkApp.onCreate()` 负责执行 `migrateLegacyCustomWallpapersIfNeeded` 迁移旧版自定义壁纸。
- **高规格视频预检（`VideoSpecEvaluator`）**：
  - 导入前检测以下 4 项条件，满足 **任意 2 项及以上**（`TRIGGER_MIN_CONDITIONS = 2`）时弹窗提醒用户（可选择「仍然导入」、「跳过高规格视频」或「取消」）：
    - 分辨率 ≥ 2K（`Math.max(w, h) >= 2560 || Math.min(w, h) >= 1440`）
    - 帧率 > 60fps（`fps > 60.1f`）
    - 时长 ≥ 60s（`durationMs >= 60_000L`）
    - 体积 > 100MB（`sizeBytes > 100L * 1024L * 1024L`）
- **选取策略（`AppPrefs.getSelectionMode(isDark)`）**：
  - `SELECT_FIXED = 0`（指定）：使用用户点选的壁纸 ID。
  - `SELECT_RANDOM = 1`（随机）：仅在**系统日/夜模式切换**（`modeTransition`）或**开机/上电启动**（`ACTION_BOOT_START`）时允许随机轮换，并尽量避免与当前生效壁纸连续重复。

### 3.3 后台保活、自启与诊断日志
- **开机/上电自启（`BootReceiver`）**：接收 `BOOT_COMPLETED`、`LOCKED_BOOT_COMPLETED`、`QUICKBOOT_POWERON`、`com.byd.action.BOOT_COMPLETED`，直接跟随 `AppPrefs.isEnabled()` 状态决定是否调用 `AutoDarkMonitorService.startMonitorForBoot`。
- **前台监控服务（`AutoDarkMonitorService`）**：`foregroundServiceType="specialUse"`，维持 `VehicleStateMonitor` 与 `2s` 静默轮询，并向 `WallpaperController` 去重调度壁纸切换。
- **日志中心（`AppLogger`）**：同时输出到 Logcat（前缀 `BYDAutoDark/<TAG>`）、内存环形缓冲（最多 `2500` 行，实时推送至「关于」页控制台）与本地文件 `autodark_debug.log`（按顺序探测预设目录：`Download/BYDAutoDarkLogs` → `Documents/BYDAutoDarkLogs` → 外部存储根目录 `/BYDAutoDarkLogs` → `Android/data/<pkg>/files/logs`），支持自定义目录、复制、清空、时间戳文件导出以及 SAF（`ACTION_OPEN_DOCUMENT_TREE` / `ACTION_CREATE_DOCUMENT`）导出至 U 盘。

---

## 4. 工程结构

```text
byd-autodarkmode/
├── .github/workflows/
│   ├── ci.yml                                       # Push / PR 自动执行单元测试、Lint 与 Debug APK 构建
│   └── release.yml                                  # Tag (v*) 或手动触发：测试、构建 Release APK 并发布 GitHub Release
├── build.gradle / settings.gradle / gradle.properties
├── README.md                                        # 面向最终用户的安装与使用说明
├── AGENTS.md                                        # 面向开发者与 Coding Agent 的技术维护指南（本文件）
└── app/
    ├── build.gradle                                 # applicationId: com.byd.autodarkwallpaper, versionName: 2.0.1 (code 2)
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml                  # 最小化权限、动态壁纸服务、specialUse 前台监控服务、自启广播
        │   ├── res/
        │   │   ├── drawable/                        # Lucide Icons 矢量图标与车机卡片/徽章样式
        │   │   ├── values/ & values-night/          # 车机原生浅色/深色双主题、配色与 strings.xml
        │   │   ├── values/dimens.xml & values-land/ # 侧栏宽度 (156dp / 横屏 216dp) 与网格列数 (2 列 / 横屏 3 列)
        │   │   ├── xml/live_wallpaper.xml           # 动态壁纸服务元数据描述
        │   │   └── layout/                          # activity_main + 主页 / 壁纸 / 关于页面与 dialog_info_menu
        │   └── java/com/byd/autodarkwallpaper/
        │       ├── AutoDarkApp.java                 # 应用入口与旧版自定义壁纸迁移
        │       ├── config/AppPrefs.java             # SharedPreferences (byd_autodark_prefs) 配置项管理
        │       ├── util/AppLogger.java              # 内存缓冲与本地/SAF 持久化日志工具
        │       ├── monitor/VehicleStateMonitor.java # 比亚迪 key_byd_night_mode / time_wallpaper_mode 实时监听
        │       ├── wallpaper/
        │       │   ├── VideoSpecEvaluator.java        # 高规格视频阈值评估器（4 选 2 触发提示）
        │       │   ├── WallpaperRepository.java       # 多壁纸库存储、视频预检导入、随机抽取与原壁纸备份
        │       │   └── WallpaperController.java       # 直接修改壁纸与动态壁纸调度控制器
        │       ├── service/
        │       │   ├── WallpaperGlRenderer.java       # OpenGL ES 2.0 统一渲染器（2D 图片 + OES 视频帧）
        │       │   ├── AutoDarkWallpaperService.java  # 动态壁纸 Engine（Media3 ExoPlayer + 400ms 过渡）
        │       │   └── AutoDarkMonitorService.java    # 前台保活监控服务
        │       ├── receiver/BootReceiver.java         # 开机/上电自启接收器
        │       └── ui/
        │           ├── MainActivity.java              # 左侧导航栏与三大功能页控制逻辑
        │           └── WallpaperGridAdapter.java      # 壁纸网格列表适配器（带缩略图缓存）
        └── test/java/com/byd/autodarkwallpaper/       # JUnit 4 + Robolectric 本地单元测试
            ├── config/AppPrefsTest.java
            ├── monitor/VehicleStateMonitorTest.java
            ├── receiver/BootReceiverTest.java
            ├── service/WallpaperGlRendererMathTest.java
            ├── util/AppLoggerTest.java
            └── wallpaper/
                ├── VideoSpecEvaluatorTest.java
                ├── WallpaperControllerTest.java
                └── WallpaperRepositoryTest.java
```

---

## 5. 编译、测试与 CI/CD

### 本地构建与验证（Windows PowerShell）

项目使用 Gradle Wrapper（Gradle 9.6.0 / AGP 9.5.2）。在项目根目录执行：

```powershell
# 运行本地单元测试（修改核心逻辑后必跑）
.\gradlew.bat testDebugUnitTest

# 运行 Android Lint 检查
.\gradlew.bat lintDebug

# 构建 Debug APK
.\gradlew.bat assembleDebug
```

编译产物路径：
```text
app/build/outputs/apk/debug/app-debug.apk
```

### GitHub CI/CD 流水线

- **持续集成 (`.github/workflows/ci.yml`)**：每次推送到 `master` / `main` 分支、提交 Pull Request 或手动触发时，在 `ubuntu-latest`（JDK 21）上自动执行 `testDebugUnitTest`、`lintDebug` 和 `assembleDebug`，并上传测试报告、Lint 报告与 Debug APK 构件。
- **自动发布 (`.github/workflows/release.yml`)**：推送 `v*` 标签（例如 `git tag v2.0.1 && git push origin v2.0.1`）或在 GitHub Actions 页面手动触发时，自动执行单元测试、构建 Release APK（`BYDAutoDarkWallpaper-<tag>.apk` 或 `-unsigned.apk`）、生成 `SHA256SUMS.txt` 并发布 GitHub Release。
  - 如需对 Release APK 自动签名，需在仓库 `Settings -> Secrets and variables -> Actions` 配置以下 4 个 Secrets（未配置时默认产出未签名 APK）：
    - `ANDROID_KEYSTORE_BASE64`（Keystore 文件的 Base64 编码）
    - `ANDROID_KEYSTORE_PASSWORD`
    - `ANDROID_KEY_ALIAS`
    - `ANDROID_KEY_PASSWORD`

---

## 6.开发规范

1. **文档职责分离与同步**：
   - 修改面向用户的功能、权限引导、切换行为、配置入口或排查方式时，同步更新 [README.md](README.md)。
   - 修改工程结构、包/类名、状态键、构建/测试命令或关键阈值规则时，同步更新本文件（`AGENTS.md`）。
2. **单测与静态检查门禁**：
   - 修改 `config`、`monitor`、`wallpaper`、`service`、`receiver`、`util` 逻辑时，需在 `app/src/test/java/com/byd/autodarkwallpaper/` 补充或更新对应的 JUnit 4 + Robolectric 测试。
   - 提交或交付代码变更前，运行 `.\gradlew.bat testDebugUnitTest` 与 `.\gradlew.bat lintDebug` 确认全部通过（纯文档修改除外）。
3. **错误处理与日志**：
   - 保持清晰完整的中文日志记录（使用 `AppLogger.i/w/e`）与显式错误提示，避免静默吞掉关键异常或依赖无依据的静默兜底。
4. **编码与风格**：
   - 所有中文文档、资源文件（`strings.xml` 等）及注释统一使用 **UTF-8** 编码。
   - 保持车机界面文案精简、图标化；新增界面文字与 `!` 说明菜单保持一致。
   - 严守最小化权限原则，不新增无关权限或私有车机 SDK 依赖。
