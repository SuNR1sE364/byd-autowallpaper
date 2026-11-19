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
- **调试模式开关**（默认关闭）：开启后在该页面下方显示：
  - **车机日/夜模式信号探测**：支持抓取「快照 A (日间)」「快照 B (夜间)」「快照 C (自动)」并自动输出全量 Diff（覆盖 `Configuration.uiMode`、`UiModeManager`、`content://carsettings/*`、`Settings.Global/System/Secure`、`getprop`、`Sensor.TYPE_LIGHT` 与车机相关 `ContentProvider` 枚举），并支持开启 `ContentObserver` + 候选广播的**实时变动监听**。
  - **实时运行日志控制台**：支持设置日志保存位置、绑定 SAF 目录（支持 U 盘）、导出日志文件、复制全部日志及清空日志。

### 4. 触发源说明
- 实车测试（Di150VCP / Android 14 / Theme 7.0.0）证实比亚迪车机默认 `support_change_system_uimode = 0`，不会同步修改标准 Android 的 `Configuration.uiMode`（恒为 `0x23 / NIGHT_YES`）。
- 当前版本直接通过标准 `ContentObserver` + 后台轻量轮询监听比亚迪真实的系统日夜键 **`Settings.Global.key_byd_night_mode`**（`1` = 夜间/深色，`2` = 日间/浅色；在自动模式下也会实时更新），同时读取 `content://carsettings/global` 中的 **`time_wallpaper_mode`**（`1` = 手动夜间，`2` = 手动日间，`3` = 自动）作为档位状态提示，**无需系统签名权限**。
- App 自身界面亦同步跟随 `key_byd_night_mode` 切换浅色/深色原生车机主题。
- 原有的 `BYDAuto*` 硬件监听与权限自检底层代码已保留在 `VehicleStateMonitor` 中，日常运行默认不初始化、不轮询，也不主动申请任何 `BYDAUTO_*` 权限。

---

## 二、 工程结构

```text
byd-autodarkmode/
├── bydauto-openapi.jar                              # 比亚迪开放平台 Stub JAR（仅 compileOnly 引用）
├── build.gradle / settings.gradle / gradle.properties
├── README.md
└── app/
    ├── build.gradle                                 # applicationId: com.byd.autodarkwallpaper, versionName: 2.0.0
    └── src/main/
        ├── AndroidManifest.xml                      # 权限、动态壁纸服务、前台监控服务、自启广播
        ├── res/
        │   ├── values/ & values-night/              # 车机原生浅色/深色双主题与配色
        │   ├── values/dimens.xml & values-land/     # 侧栏宽度与壁纸网格列数自适应
        │   └── layout/                              # 左侧导航栏 + 主页 / 壁纸 / 关于页面布局
        └── java/com/byd/autodarkwallpaper/
            ├── AutoDarkApp.java                     # 应用入口与旧版自定义壁纸迁移
            ├── config/AppPrefs.java                 # SharedPreferences 配置项管理
            ├── util/AppLogger.java                  # 内存缓冲与本地持久化日志工具
            ├── util/DayNightProbeHelper.java        # 车机日/夜模式底层信号快照 Diff 与实时监听探测工具
            ├── monitor/VehicleStateMonitor.java     # 比亚迪 key_byd_night_mode / time_wallpaper_mode 实时监听（保留 BYD 硬件底层代码）
            ├── wallpaper/WallpaperRepository.java   # 多壁纸库存储、随机抽取、原壁纸备份与复原
            ├── wallpaper/WallpaperController.java   # 直接修改壁纸与动态壁纸调度控制器
            ├── service/AutoDarkWallpaperService.java# 动态壁纸 Engine（400ms 渐变过渡）
            ├── service/AutoDarkMonitorService.java  # 前台保活监控服务
            ├── receiver/BootReceiver.java           # 开机/上电自启接收器
            └── ui/
                ├── MainActivity.java                # 左侧导航栏与三大功能页控制逻辑
                └── WallpaperGridAdapter.java        # 壁纸网格列表适配器（带缩略图缓存）
```

---

## 三、 编译构建

在项目根目录下执行：

```powershell
.\gradlew.bat assembleDebug
```

编译产物位于：
```text
app/build/outputs/apk/debug/app-debug.apk
```
