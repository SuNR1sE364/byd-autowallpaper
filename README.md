# 自动深浅壁纸 (BYD Auto Dark Wallpaper)

面向比亚迪车机（DiLink 横屏座舱）的日常自动深浅壁纸应用。  
采用贴合车机原生的 **左边栏 + 右侧主界面** 布局并自动跟随系统浅色/深色主题，在监听车机系统日/夜模式变化时自动切换对应的日间与夜间壁纸。

---

## 一、 核心功能

### 1. 主页：总开关与模式切换
- **自动切换总开关**：
  - 使用 **直接修改壁纸** 模式时，开启前会自动备份用户当前的系统桌面壁纸；关闭开关后自动将系统桌面复原为原有壁纸。
  - 若因缺少文件访问权限或当前使用动态壁纸/车机百变主题导致无法读取原壁纸，会引导授权或由用户选择是否不备份继续开启。
  - 开机/上电自启状态直接跟随总开关状态。
- **两种壁纸切换模式**：
  1. **直接修改壁纸（默认）**：通过 `WallpaperManager.setBitmap` 直接替换系统桌面静态壁纸。
  2. **动态壁纸模式**：通过 `AutoDarkWallpaperService` 在内存中完成日/夜图片绘制与 `400ms` 平滑淡入淡出过渡，避免反复刷写磁盘。
- **壁纸概览**：展示日间与夜间当前生效壁纸及选取策略，点击可直达“壁纸”页对应分页。

### 2. 壁纸：多壁纸库与选取策略
- 独立管理 **日间壁纸** 与 **夜间壁纸**（支持一次上传单张或多张图片，自动处理 EXIF 旋转）。
- 两种选取策略（日、夜可分别配置）：
  - **使用指定壁纸**：固定使用您点击选中的那一张壁纸。
  - **随机轮换**：在每次系统日/夜模式切换以及车机上电/重启启动时，从多张壁纸中随机更换一张（尽量避免与当前壁纸连续重复）。

### 3. 关于：应用信息与调试模式
- 展示应用版本、触发方式、切换模式与当前车机运行环境信息。
- **调试模式开关**（默认关闭）：开启后在该页面下方展示 **实时运行日志控制台**，仅用于查看与保存日志（支持设置日志保存位置、绑定 SAF 目录/U 盘、导出带时间戳的日志文件、复制全部日志及清空日志）。

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
        │   │   ├── values/ & values-night/          # 车机原生浅色/深色双主题与配色
        │   │   ├── values/dimens.xml & values-land/ # 侧栏宽度与壁纸网格列数自适应
        │   │   └── layout/                          # 左侧导航栏 + 主页 / 壁纸 / 关于页面布局
        │   └── java/com/byd/autodarkwallpaper/
        │       ├── AutoDarkApp.java                 # 应用入口与旧版自定义壁纸迁移
        │       ├── config/AppPrefs.java             # SharedPreferences 配置项管理
        │       ├── util/AppLogger.java              # 内存缓冲与本地/SAF 持久化日志工具
        │       ├── monitor/VehicleStateMonitor.java # 比亚迪 key_byd_night_mode / time_wallpaper_mode 实时监听
        │       ├── wallpaper/WallpaperRepository.java # 多壁纸库存储、随机抽取、原壁纸备份与复原
        │       ├── wallpaper/WallpaperController.java # 直接修改壁纸与动态壁纸调度控制器
        │       ├── service/AutoDarkWallpaperService.java # 动态壁纸 Engine（400ms 渐变过渡）
        │       ├── service/AutoDarkMonitorService.java   # 前台保活监控服务
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
