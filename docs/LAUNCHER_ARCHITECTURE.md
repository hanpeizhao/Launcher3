# Launcher3 架构与核心调用链路

> 以本工程（AOSP Launcher3，Android 16 树）为基准，说明应用如何分模块运行、模块间如何协作，并给出几条端到端调用链路（启动、数据加载、图标渲染、点击启动、手势多任务）。链路均经过实机（红米 K70 / HyperOS / Android 16）调试验证。
>
> 配套文档：[RUN_ON_RETAIL_DEVICE.md](RUN_ON_RETAIL_DEVICE.md)（零售机运行与兼容性修复）、[launcher3-entry-points-and-flow.md](launcher3-entry-points-and-flow.md)（入口点与流程）。

---

## 1. 工程模块地图

Launcher3 不是单一大模块，而是 **"一个主 APK + 多个 Gradle 子模块 + 按 flavor 拼装的源码集"** 的结构：

| 目录 | Gradle 身份 | 职责 |
|---|---|---|
| `src/` | 主源码集 | 桌面核心：数据模型、加载器、UI、图标、小组件、设置 |
| `quickstep/` | 主源码集（withQuickstep flavor 专属） | 手势导航、多任务（recents）、taskbar、与 SystemUI 的集成层 |
| `iconloaderlib/` | 子模块 `:IconLoader` | 图标加载、缩放、蒙版裁剪、缓存（独立于桌面 UI，可被复用） |
| `animationlib/` | 子模块 `:animationlib` | 动画插值/路径动画数学库 |
| `contextualeducationlib/` | 子模块 `:contextualeducationlib` | Pixel 专属 Contextual Search 教育 UI（小米上不可用，已降级） |
| `flags/` | 子模块 `:flags` | aconfig 生成的 FeatureFlags（本 fork 为手工维护的布尔值） |
| `shared/src` | 主源码集 | SystemUI 共享层（`QuickStepContract`、远程动画协议等） |
| `system_ext/` | 主源码集 | 系统 hidden API 的 facade/桩 |
| `compose/facade/` | 主源码集 | Compose 支持 facade（core/disabled 两套） |
| `res/` + `quickstep/res` | 资源集 | 主资源；quickstep 资源会**覆盖**主资源（同名资源优先） |
| `protos/` `protos_overrides/` | proto 源 | 数据结构定义（workspace 备份、grid control 等） |
| `src_flags/` `src_shortcuts_overrides/` `src_no_quickstep/` `src_ui_overrides/` `go/` | flavor 源码集 | 按 withoutQuickstep/withQuickstep/go 变体拼装差异代码 |
| `prebuilts/libs/` | — | 编译期依赖的 platform `framework.jar`（含隐藏 API）和 SystemUI shared jar |
| `tests/` | 测试 | multivalent 单测 / 设备测试 / TAPL UI 自动化 |

**关键理解**：`src` 与 `quickstep` 不是两个 APK，而是同一个 APK 内的两个包层——`src` 是"桌面本体"，`quickstep` 是"系统集成增强"，通过 uioverrides（如 `QuickstepLauncher extends Launcher`）向上覆盖。

---

## 2. 进程启动与初始化链路

```
系统点击桌面图标 / HOME
  └─ ActivityTaskManager 启动 com.android.launcher3/.uioverrides.QuickstepLauncher
      └─ Application: LauncherApplication.attachBaseContext()        ← 进程内最早代码
          └─ HiddenApiBypass.setHiddenApiExemptions("L")            ← 零售机兼容（解除隐藏API拦截）
      └─ LauncherApplication.onCreate()
          └─ MainProcessInitializer.initialize(this)
              ├─ 按 android:process 分流（主进程 / :smartspace 等子进程）
              └─ 初始化各 process component（TaskbarManager、共享单例等）
      └─ QuickstepLauncher.onCreate()
          ├─ super (Launcher.onCreate)
          │    ├─ inflate launcher.xml → Workspace / Hotseat / SearchBar
          │    ├─ LauncherAppState.getInstance() → InvariantDeviceProfile、IconCache、LauncherModel
          │    └─ LauncherModel.onAdded/onStart → 触发数据加载（§3）
          └─ Quickstep 特有初始化
               ├─ RecentsAnimationDeviceState（查询 PIP/任务栈，无权限时降级）
               ├─ QuickstepTransitionManager（远程转场动画，无权限时降级）
               └─ TouchInteractionService 等系统绑定组件
```

依赖注入：全应用通过 **Dagger** 组织（`LauncherAppComponent` 应用级单例；Activity 级 `MainActivityComponent`）。任意位置可用 `context.appComponent` 拿到组件；`ThemeManager.INSTANCE` 这类 `DaggerSingletonObject` 提供惰性单例访问桥。

---

## 3. 数据加载链路（桌面上的图标从哪来）

```
LauncherModel（数据中枢，持 Callbacks 列表）
  └─ MODEL_EXECUTOR 后台线程 → LoaderTask
      ├─ LauncherApps.getActivityList()            → 全部应用（抽屉数据，AppsList）
      ├─ 读 workspace DB（LauncherProvider, favorites 表）→ 桌面布局/文件夹/小组件
      ├─ LauncherWidgetHolder / AppWidgetManager   → 小组件信息
      └─ 每条 item → IconCache.getIcon() 生成 BitmapInfo（§4）
  └─ 产出 BgDataModel（items map、文件夹、app 计数等）
  └─ MAIN_EXECUTOR 回调 LauncherCallbacks.bindItems/bindAllApps...
      └─ Workspace（CellLayout） / AllAppsRecyclerView 渲染
```

后续增量更新：`PackageUpdatedTask` 等以同一条管线跑（应用安装/卸载/更新 → 包广播 → Model 过滤 → 局部 rebind）。

---

## 4. 图标加载与渲染链路（以一个图标为例，端到端）

```
LoaderTask / PackageUpdatedTask
  └─ IconCache.getIcon()
      └─ LauncherIconProvider.getIcon()            ← 桌面自己的 IconProvider
          ├─ getThemeDataForPackage()              ← 主题图标（mono）映射表
          ├─ loadAppInfoIcon()                     ← 形状为圆形时优先加载应用 roundIcon
          └─ BaseIconFactory.createBadgedIconBitmap()
              ├─ wrapToAdaptiveIcon()              ← 旧式图标包一层背景成 Adaptive
              ├─ drawIconBitmap()
              │    └─ getShapePath(drawable, bounds)   ★ 蒙版决策点
              │         └─ LauncherIcons.getShapePath()     ← 桌面覆盖
              │              └─ ThemeManager.iconShape           ← 形状状态中心
              │                   ├─ LauncherPrefs["icon_shape_model"]（用户选择）
              │                   ├─ ShapesProvider.iconShapes（内置形状目录）
              │                   ├─ ThemeManager.DEFAULT_ICON_SHAPE（默认超椭圆）
              │                   └─ ShapeDelegate.pickBestShape() → Circle/RoundedSquare/GenericPathShape
              │    └─ drawAdaptiveIcon()           ← canvas.clipPath(蒙版) 后画前景/背景
              └─ BitmapInfo（bitmap + 主色 + flags）
  └─ FastBitmapDrawable → BubbleTextView.setIcon() → 屏幕显示
```

**形状缓存失效**：`IconState.toUniqueId()` 含 `iconMask.hashCode()` → `LauncherIconProvider.updateSystemState()` 拼进 `mSystemState` → 蒙版变化即触发图标缓存整体重建（无需手动清缓存）。

**文件夹预览形状**：`PreviewBackground` / `FolderAnimationManager` 直接取 `ThemeManager.folderShape`，与图标蒙版同一状态源。

---

## 5. 点击图标 → 启动应用链路

```
用户点击 BubbleTextView
  └─ ItemClickHandler.onClickAppItem()
      └─ ActivityContext.startItemActivity()
          ├─ QuickstepLauncher.getActivityLaunchOptions(v, item)
          │    └─ QuickstepTransitionManager.getActivityLaunchOptions()
          │         ├─ 无 CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS 权限
          │         │    → 返回 ActivityOptions.makeBasic()（系统默认转场）★ 零售机路径
          │         └─ 有权限 → makeRemoteAnimation(RemoteAnimationAdapter)（AOSP 系统桌面路径）
          ├─ 同用户: context.startActivity(intent, optsBundle)
          ├─ 工作资料: LauncherApps.startMainActivity(component, user, ...)
          └─ 深快捷方式: LauncherApps.startShortcut(...)
          └─ catch (NPE | ActivityNotFoundException | SecurityException)
               → Toast R.string.activity_not_found（"未安装该应用。"）+ 打日志
```

`startActivity` 携带 remoteAnimationAdapter 同样会触发权限检查——这就是"未安装该应用"问题的根源（详见 RUN_ON_RETAIL_DEVICE.md §3.8）。

---

## 6. 手势 / 多任务链路（quickstep 模块）

```
SystemUI（原生：pixel nav bar）绑定 TouchInteractionService
  └─ RecentsAnimationDeviceState（导航模式、PIP、任务栈监听）
      └─ 系统手势事件 → RecentsAnimationController
          ├─ 概览态：LauncherRecentsView / RecentTasksList（任务快照）
          ├─ 快捷切换：TaskAnimationManager
          └─ 远程动画协议（shared/QuickStepContract）
```

**HyperOS 现状**：小米 SystemUI 不认识第三方 quickstep 服务（`STATUS_BAR_SERVICE` 权限不可得），手势与多任务由 HyperOS 自家接管——上滑 HOME 通过 intent 落到我们桌面（正常），但 recents 快照/手势动画链路不会被激活。桌面进程内相应组件以降级状态空转（§5、RUN_ON_RETAIL_DEVICE.md §6）。

---

## 7. 状态与配置系统（模块间"共享状态"怎么走）

| 系统 | 类 | 用途 | 持久化 |
|---|---|---|---|
| 桌面偏好 | `LauncherPrefs` | icon_shape_model、themed_icons 等 | SharedPreferences（可备份加密） |
| 系统 settings | `SettingsCache` | 以 ContentObserver 监听 Secure/Global/System，带缓存 | 内存（零售机上 @hide key 禁读已兜底） |
| 图标主题 | `ThemeManager` | 图标形状/主题单例状态 + 变更监听（listener 模式） | 依赖 LauncherPrefs |
| Feature 开关 | `Flags`（:flags 模块） | aconfig 生成，本 fork 手工维护布尔值 | 编译期固定 |
| 设备网格 | `InvariantDeviceProfile` | 网格/图标尺寸/密度，变更广播重建 | 持久化 + 广播 |
| 系统形状 | framework `config_icon_mask` | 系统默认蒙版兜底 | 系统资源 |

---

## 8. 模块间协作机制总结

1. **Dagger 依赖图**：`LauncherAppComponent`（应用单例）→ 各 feature 组件；跨模块取单例用 `DaggerSingletonObject`（如 `ThemeManager.INSTANCE`）
2. **线程模型**：`MAIN_EXECUTOR`（UI）、`MODEL_EXECUTOR`（数据加载）、`UI_HELPER_EXECUTOR`（跨线程 UI 辅助）、磁盘 IO 走专用线程——数据链路（§3）全部异步
3. **观察者模式贯穿全局**：Model→Callbacks（数据绑定）、ThemeManager→ThemeChangeListener（图标换装）、SettingsCache→listener（设置变化）、Prefs→LauncherPrefChangeListener
4. **覆盖层次**：`uioverrides`（quickstep 覆盖 src 行为）、`res` 覆盖（quickstep/res > res）、flavor 源码集——这是"系统应用增强层"的组织方式

---

## 9. 本次所有改动在链路中的位置

| # | 改动 | 所在链路 |
|---|---|---|
| 1 | `LauncherApplication.attachBaseContext` 隐藏API豁免 | §2 启动初始化 |
| 2 | `ContextualSearchStateManager` 资源缺失降级 | §2 启动（quickstep） |
| 3 | `RotationTouchHelper` 非可视化 Context 降级 | §6 手势初始化 |
| 4 | `SettingsCache` SecurityException 兜底 | §7 状态系统 |
| 5 | `RecentsAnimationDeviceState` PIP 查询降级 | §6 手势初始化 |
| 6 | `QuickstepTransitionManager` 注册远程动画降级 | §5/§6 转场动画 |
| 7 | `SystemApiWrapper.getActivityOverrides` 降级 | §3 数据加载 |
| 8 | `getActivityLaunchOptions`/`createFadeOutAnimOptions` 按权限降级 | §5 点击启动 |
| 9 | `ThemeManager.DEFAULT_ICON_SHAPE` 超椭圆默认蒙版 | §4 图标渲染 |
| 10 | `TaskUtils.closeSystemWindowsAsync` 捕获 SecurityException | §5 点击启动（11 处调用唯一汇聚点） |
| 11 | （无代码改动）微件黑框经 OutlineTrace 插桩定案为微件自绘描边 | 微件渲染（详见 RUN_ON_RETAIL_DEVICE.md §3.10） |
