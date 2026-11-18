# BYD Auto Dark Wallpaper (比亚迪自动深浅壁纸)

面向比亚迪车机（重点适配 **DiLink 150** 横屏座舱）的轻量级自动亮/暗壁纸切换应用。  
支持在不替换原厂 DiLink 桌面的前提下，监听车机系统深浅色模式、比亚迪车灯开关与环境光传感器信号，通过**双模式壁纸架构**自动切换日间亮色壁纸与夜间暗色壁纸。

---

## 一、 适配目标与硬件信息

本项目针对以下实车测试环境设计：
- **车型平台**：比亚迪 DiLink 150 车机
- **车机系统**：Android 14 / DiLink
- **硬件平台**：DiLink 150 平台
- **最低系统兼容**：Android 9 (`minSdk = 28`)，`targetSdk = 34`，`compileSdk = 37`

---

## 二、 核心特性

### 1. 双模式壁纸切换架构（可自由切换或同时启用）
在比亚迪 DiLink 车机上，不同版本的“百变主题”对原厂壁纸图层的接管策略不同，因此应用内置了三种输出模式：
1. **双模式同时生效（默认，推荐）**：同时触发 `AutoDarkWallpaperService` 与 `WallpaperManager.setBitmap`。
2. **仅动态壁纸服务 (`AutoDarkWallpaperService`)**：将本应用设为系统动态壁纸，内部 `Engine` 在内存中完成日间/夜间图片绘制与 `400ms` 淡入淡出过渡，避免频繁磁盘写入且系统天然保活。
3. **仅静态壁纸替换 (`WallpaperManager`)**：通过 `WallpaperManager.setBitmap(..., FLAG_SYSTEM)` 直接替换系统桌面静态壁纸（可选同步 `FLAG_LOCK` 锁屏壁纸）。

### 2. 全量触发条件支持（实车对比与后续定型）
应用后台会同时采集并实时展示所有信号源，主触发规则可在界面上随时切换：
1. **跟随车机系统深浅色模式 (`Configuration.uiMode`)**：通过 `ComponentCallbacks` 与 `ACTION_CONFIGURATION_CHANGED` 实时捕获 `UI_MODE_NIGHT_YES` / `UI_MODE_NIGHT_NO`。
2. **跟随比亚迪车灯开关 (`BYDAutoLightDevice`)**：支持独立勾选 **示宽灯/小灯 (`LIGHT_SIDE`)**、**近光灯 (`LIGHT_LOW_BEAM`)**、**远光灯 (`LIGHT_HIGH_BEAM`)**，开灯即切暗色，关灯即切亮色。
3. **跟随比亚迪环境光感等级 (`BYDAutoSensorDevice`)**：读取 `getLightIntensity()`（`Level 1` ~ `Level 5`），支持自定义触发档位阈值及判定方向（`<= 阈值` 或 `>= 阈值` 视为夜间，用于适配不同车型的光感档位定义）。
4. **多源联合触发**：上述已连接的信号源中任一判定为夜间暗色即切换暗色壁纸。
5. **额外辅助机制**：
   - 监听 `BYDAutoBodyworkDevice.getPowerLevel()`，当车辆上电进入 `ON` 档时主动重新校准全部硬件状态。
   - 支持 **0 秒 / 1.5 秒 / 3 秒 / 5 秒** 防抖延迟，避免进出短隧道或桥洞频繁闪切。

### 3. 诊断优先（无静默 Fallback）
- 任何 `BYDAuto*` 接口初始化失败、缺少权限返回 `null`、返回错误码（如 `FAILED` / `BUSY` / `TIMEOUT` / `INVALID_VALUE`）或 `WallpaperManager` 抛出异常时，**绝不静默降级或掩盖错误**。
- **BYD 硬件权限自检与动态授权**：应用启动与点击 **【权限自检/授权】** 时会扫描并记录所有 `android.permission.BYDAUTO_*` 权限的实时授予状态和系统 `protectionLevel`，自动发起动态授权申请，并支持一键跳转系统应用权限设置页；监听注册 (`registerListener`) 与主动轮询 (`poll`) 解耦，避免未重写限制或单点异常阻断数据读取，同时监控服务内置每 2 秒一次的轻量级兜底变化轮询。
- **自定义日志保存位置**：针对部分车机限制访问 `/sdcard/Android/data/` 的情况，日志中心默认优先尝试保存至车机“文件管理”可直接访问的公共目录 `Download/BYDAutoDarkLogs/autodark_debug.log`，并在界面提供 **【设置保存位置】** 弹窗，支持：
  - 一键切换预设目录（`Download/BYDAutoDarkLogs`、`Documents/BYDAutoDarkLogs`、外部存储根目录、`Android/data/.../files/logs`）或手动输入自定义路径（支持引导授予所有文件访问权限）。
  - 通过系统文件选择器 (`ACTION_OPEN_DOCUMENT_TREE`) 绑定持久化 SAF 目录（支持 U 盘等外接存储），后续点击 **【导出日志文件】** 时同步导出。
  - 通过 **【立即另存当前日志为文件...】** (`ACTION_CREATE_DOCUMENT`) 直接将全量日志保存到任意可选位置。
- 所有原始返回值、错误码及完整异常堆栈均实时输出至：
  - 右侧“实时硬件信号遥测”面板
  - 右侧“实时调试日志与堆栈”控制台
  - 本地持久化日志文件（默认优先公共 `Download/BYDAutoDarkLogs/autodark_debug.log`，或用户自定义的保存目录）
  - Android `Logcat`（Tag 前缀 `BYDAutoDark/`）

---

## 三、 工程结构

```text
byd-autodarkmode/
├── bydauto-openapi.jar                          # 比亚迪开放平台 Stub JAR（仅编译期 compileOnly 引用）
├── build.gradle / settings.gradle / gradle.properties / local.properties
├── README.md
└── app/
    ├── build.gradle                             # applicationId: com.byd.autodarkwallpaper
    └── src/main/
        ├── AndroidManifest.xml                  # 权限声明、动态壁纸服务、前台监控服务、自启广播
        ├── res/
        │   ├── layout-land/activity_main.xml    # 车机横屏双栏主界面
        │   ├── layout/activity_main.xml         # 竖屏/分屏适配布局
        │   └── xml/live_wallpaper.xml           # WallpaperService 元数据
        └── java/com/byd/autodarkwallpaper/
            ├── AutoDarkApp.java                 # Application 入口与默认测试壁纸初始化
            ├── config/AppPrefs.java             # SharedPreferences 配置项管理
            ├── util/AppLogger.java              # 内存缓冲 + 本地持久化日志 + 导出 + BYD 返回码解析
            ├── monitor/VehicleStateMonitor.java # uiMode / BYDAutoLightDevice / BYDAutoSensorDevice / Bodywork 监听
            ├── wallpaper/WallpaperController.java # 内置测试壁纸生成、选图导入、双模式切换调度
            ├── service/AutoDarkWallpaperService.java # 动态壁纸 Engine（400ms 平滑过渡）
            ├── service/AutoDarkMonitorService.java   # 前台保活监控服务与防抖调度
            ├── receiver/BootReceiver.java       # 开机/上电自启接收器
            └── ui/MainActivity.java             # 主控制、预览、遥测与日志调试界面
```

---

## 四、 编译构建说明

### 关键构建要求
`bydauto-openapi.jar` 是比亚迪提供的编译期 **Stub 存根库**（内部方法全部抛出 `RuntimeException("Stub!")`），在 `app/build.gradle` 中**必须使用 `compileOnly`** 引入，绝不可打包进 APK 的 `classes.dex` 中，否则在真车运行时会覆盖系统 `framework` 的真实实现。

### 命令行构建步骤
在项目根目录下执行：

```powershell
.\gradlew.bat assembleDebug
```

编译产物位于：
```text
app/build/outputs/apk/debug/app-debug.apk
```

---

## 五、 实车安装与调试流程

### 1. 安装 APK
通过 U 盘安装，或连接车机 ADB 后执行：
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 2. 验证内置壁纸与双模式可用性
1. 打开应用后，首次启动会自动生成两张 `2560x1600` 的内置测试壁纸（日间亮色与夜间暗色），可在左侧预览区确认。
2. 点击 **【立即切亮色(测试)】** 和 **【立即切暗色(测试)】**，返回车机桌面观察原厂桌面背景是否变化：
   - 若直接生效，说明静态 `WallpaperManager` 在当前车机固件上可直接控制桌面壁纸。
   - 若需测试动态壁纸引擎，点击 **【去激活系统动态壁纸】** 并在系统弹窗中设为桌面壁纸（若车机精简了原生选择器，日志区会打印完整异常栈）。
3. 验证成功后，通过 **【选择日间壁纸】** 和 **【选择夜间壁纸】** 导入自己的图片。

### 3. 实车验证三种触发条件与权限排查
1. 观察右侧 **“实时硬件信号遥测”** 面板（可随时点击 **【主动轮询刷新硬件状态】** 或 **【权限自检/授权】**）：
   - 确认 `BYDAutoLightDevice` 与 `BYDAutoSensorDevice` 是否显示 **`[已连接]`** 及原始数值。
   - 若提示 `SecurityException: [getInt] permission deny!` 或权限 `DENIED`，先点击 **【权限自检/授权】** 查看每一项 `BYDAUTO_*` 权限的 `protectionLevel`（若为 `dangerous` 可直接弹窗授权；若为 `signature|privileged`，说明车机系统对第三方未签名应用限制了该项硬件读取）。
   - 若已开启车机 ADB 调试，也可检查权限状态：
     ```bash
     adb shell dumpsys package com.byd.autodarkwallpaper | grep BYDAUTO
     ```
2. 对比测试：
   - 切换车机系统深/浅色外观，观察 `系统 uiMode` 数值变化。
   - 开关车辆示宽灯/近光灯，观察 `BYDAutoLightDevice` 状态跳变。
   - 遮挡前风挡光感探头或进出地库，观察 `BYDAutoSensorDevice.getLightIntensity()` 档位在 `Level 1`~`Level 5` 间的变化方向，据此调整 `<= 阈值` 或 `>= 阈值` 配置。
3. 最后根据实际车机表现选定最稳定的 **实现模式** 与 **主触发源**。

### 4. 日志保存位置配置与导出排查
- 默认会优先尝试保存至公共目录 `Download/BYDAutoDarkLogs/autodark_debug.log`（可在车机自带文件管理器“下载”中直接查看或复制到 U 盘）。
- 点击 **【设置保存位置】**（或直接点击日志文件路径行）可自定义实时日志文件夹路径，或通过系统文件选择器直接绑定 U 盘/任意文件夹，也可直接另存为 `.log` 文件。
- 应用内可直接点击 **【复制全部日志】** 或 **【导出日志文件】**。
- 若已连接 ADB，也可拉取日志（根据实际设置的目录调整路径）：
  ```bash
  adb pull /sdcard/Download/BYDAutoDarkLogs/ ./byd_logs/
  adb logcat -d | grep BYDAutoDark > byd_logcat.txt
  ```
