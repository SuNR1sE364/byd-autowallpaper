# 自动深浅壁纸 (BYD Auto Dark Wallpaper)

面向比亚迪车机（DiLink 横屏座舱）的日常自动深浅壁纸应用。  
采用贴合车机原生的 **左边栏 + 右侧主界面** 布局并自动跟随系统浅色/深色主题；全套界面图标基于开源矢量图标库 **Lucide Icons (ISC)**，主界面精简冗余描述文字，以图标、状态徽章与预览布局直观呈现功能，详细说明统一收纳于页面顶部的 **`!` 感叹号信息菜单** 中。

---

## 一、 核心功能

### 1. 主页：总开关与模式切换
- **自动切换总开关**：
  - 使用 **直接修改壁纸** 模式时，开启前会自动备份用户当前的系统桌面壁纸；关闭开关后自动将系统桌面复原为原有壁纸。
  - 若因缺少文件访问权限或当前使用动态壁纸/车机百变主题导致无法读取原壁纸，会引导授权或由用户选择是否不备份继续开启。
  - 开机/上电自启状态直接跟随总开关状态。
- **两种壁纸切换模式**：
  1. **直接修改壁纸（默认）**：通过 `WallpaperManager.setBitmap` 直接替换系统桌面静态壁纸（仅支持图片）。
  2. **动态壁纸模式**：通过 `AutoDarkWallpaperService` 结合 **OpenGL ES 2.0 + Media3 ExoPlayer** 统一渲染管线，同时支持**静态图片**与**视频壁纸**（支持 `400ms` 平滑淡入淡出过渡，完全禁用音轨以免干扰车机音乐与导航播报，并在车机可见性变化时自动启停以控制功耗）。
- **壁纸概览**：展示日间与夜间当前生效壁纸及选取策略，点击可直达“壁纸”页对应分页。

### 2. 壁纸：多壁纸库与选取策略
- 独立管理 **日间壁纸** 与 **夜间壁纸**（支持一次上传单张或多张图片/视频，图片自动处理 EXIF 旋转）。
- **视频壁纸支持（不限制导入，设置生效仅动态壁纸模式可用）**：
  - 无论当前处于何种切换模式，均支持混选导入本地视频（不受比亚迪原生相册 20s 时长硬限制，流式保留原视频并自动抽取首帧缩略图）；
  - 导入前自动预检视频规格：**分辨率 ≥ 2K**、**帧率 > 60fps**、**时长 ≥ 60s**、**体积 > 100MB** 四个条件满足**任意两个及以上**时弹窗提醒，用户可选择“仍然导入”、“跳过高规格视频”或“取消”；
  - 处于“直接修改壁纸”模式时保留已导入视频文件并在列表中标注“仅动态模式”，限制将视频设为静态壁纸，自动筛选图片生效且不破坏原动态模式选中配置。
- 两种选取策略（日、夜可分别配置）：
  - **使用指定壁纸**：固定使用您点击选中的那一项壁纸。
  - **随机轮换**：在每次系统日/夜模式切换以及车机上电/重启启动时，从可用壁纸中随机更换（尽量避免与当前壁纸连续重复）。

### 3. 关于：应用信息、集中说明与调试日志
- 展示应用版本、当前车机运行环境信息，并提供集中式 **功能与使用说明** 入口（与各页顶部 `!` 感叹号按钮打开同一说明菜单，包含工作机制与当前备份/执行状态摘要）。
- **调试日志开关**（默认关闭）：开启后在该页面下方展开 **运行日志控制台**，用于查看与保存日志（支持切换日志目录、绑定 SAF 目录/U 盘、复制、导出与清空日志）。

### 4. 触发源说明
- 实车测试（Di150VCP / Android 14 / Theme 7.0.0）证实比亚迪车机默认 `support_change_system_uimode = 0`，不会同步修改标准 Android 的 `Configuration.uiMode`。
- 当前版本直接通过标准 `ContentObserver` + 后台轻量轮询监听比亚迪真实的系统日夜键 **`Settings.Global.key_byd_night_mode`**（`1` = 夜间/深色，`2` = 日间/浅色；在自动模式下也会实时更新），同时读取 `content://carsettings/global` 中的 **`time_wallpaper_mode`**（`1` = 手动夜间，`2` = 手动日间，`3` = 自动）作为档位状态提示，**无需系统签名权限或任何 `BYDAUTO_*` 私有权限**。
- App 自身界面亦同步跟随 `key_byd_night_mode` 切换浅色/深色原生车机主题。

---

## 二、 工程结构

```text
byd-autodarkmode/
├── .github/workflows/
│   ├── ci.yml                                       # Push / PR 自动执行单元测试、Lint 与 Debug APK 构建
│   └── release.yml                                  # Tag (v*) 自动测试、构建 Release APK 并发布 GitHub Release
├── build.gradle / settings.gradle / gradle.properties
├── README.md
└── app/
    ├── build.gradle                                 # applicationId: com.byd.autodarkwallpaper, versionName: 2.0.0
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml                  # 最小化权限、动态壁纸服务、前台监控服务、自启广播
        │   ├── res/
        │   │   ├── drawable/                        # Lucide Icons 矢量图标与车机卡片/徽章样式
        │   │   ├── values/ & values-night/          # 车机原生浅色/深色双主题与配色
        │   │   ├── values/dimens.xml & values-land/ # 侧栏宽度与壁纸网格列数自适应
        │   │   └── layout/                          # 左侧导航栏 + 主页 / 壁纸 / 关于页面与 ! 感叹号说明菜单布局
        │   └── java/com/byd/autodarkwallpaper/
        │       ├── AutoDarkApp.java                 # 应用入口与旧版自定义壁纸迁移
        │       ├── config/AppPrefs.java             # SharedPreferences 配置项管理
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
        │       ├── receiver/BootReceiver.java       # 开机/上电自启接收器
        │       └── ui/
        │           ├── MainActivity.java            # 左侧导航栏与三大功能页控制逻辑
        │           └── WallpaperGridAdapter.java    # 壁纸网格列表适配器（带缩略图缓存）
        └── test/java/com/byd/autodarkwallpaper/     # JUnit 4 + Robolectric 本地单元测试
```

---

## 三、 编译、测试与发布

### 本地构建与测试

在项目根目录下执行：

```powershell
# 运行本地单元测试
.\gradlew.bat testDebugUnitTest

# 运行 Android Lint 检查
.\gradlew.bat lintDebug

# 构建 Debug APK
.\gradlew.bat assembleDebug
```

编译产物位于：
```text
app/build/outputs/apk/debug/app-debug.apk
```

### GitHub CI/CD 流水线

- **持续集成 (`.github/workflows/ci.yml`)**：每次向 `master` / `main` 分支推送或提交 Pull Request 时，自动执行单元测试、`lintDebug` 和 `assembleDebug`，并上传测试报告与 Debug APK 构件。
- **自动发布 (`.github/workflows/release.yml`)**：推送 `v*` 标签（如 `git tag v2.0.0 && git push origin v2.0.0`）或在 GitHub Actions 页面手动触发时，自动执行单元测试、构建 Release APK、生成 `SHA256SUMS.txt` 并创建 GitHub Release。
  - 如需自动对 Release APK 签名，可在仓库 `Settings -> Secrets and variables -> Actions` 中配置以下 4 个 Secrets（未配置时默认产出未签名 Release APK）：
    - `ANDROID_KEYSTORE_BASE64`（Keystore 文件的 Base64 编码）
    - `ANDROID_KEYSTORE_PASSWORD`
    - `ANDROID_KEY_ALIAS`
    - `ANDROID_KEY_PASSWORD`
