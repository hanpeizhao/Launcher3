# Launcher3 入口与运行流程文档

> 基于 `main` 分支（commit `39c232d7`）及 `Launcher3-16-Qc` 同步后代码结构整理。

---

## 1. 进程级入口：`LauncherApplication`

**文件**：`src/com/android/launcher3/LauncherApplication.java`

```java
public class LauncherApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        MainProcessInitializer.initialize(this);  // 关键：初始化全局单例
    }

    public LauncherAppComponent getAppComponent() {  // 懒初始化 Dagger
        if (mAppComponent == null) {
            synchronized (this) {
                if (mAppComponent == null) {
                    initDaggerComponent(DaggerLauncherAppComponent.builder()
                            .iconsDbName(LauncherFiles.APP_ICONS_DB));
                }
            }
        }
        return (LauncherAppComponent) mAppComponent;
    }
}
```

**初始化的核心单例**：
- `IconCache` — 图标加载/缓存管线
- `LauncherModel` — 工作区/全应用数据模型
- `WidgetManager` — 小部件管理
- `SettingsCache` / `DeviceProfile` — 配置与设备规格
- `Dagger AppComponent` — 依赖注入图（Quickstep 变体用 `QuickstepBaseAppComponent`）

---

## 2. Variant 机制详解

### 2.1 两个 Flavor Dimension

`build.gradle` 定义两个正交维度，**交叉组合出 4 个构建变体**：

| Dimension | Flavor | 说明 |
|:----------|:-------|:-----|
| **app** | `aosp` | 标准 AOSP Launcher（默认） |
| | `l3go` | Android Go 低内存设备优化版（简化动画、低分资源、内存压缩） |
| **recents** | `withQuickstep` | 含 Quickstep：手势导航、概览/最近任务、任务栏、分屏选择 |
| | `withoutQuickstep` | 纯桌面：仅工作区、全应用列表、文件夹，**无手势、无概览** |

**组合矩阵**：

| Variant 名称 | 组合 | 典型用途 |
|:-------------|:-----|:---------|
| **`aospWithQuickstep`** | `aosp` + `withQuickstep` | **默认完整版**（Pixel/标准设备预装） |
| `aospWithoutQuickstep` | `aosp` + `withoutQuickstep` | 精简桌面（无手势导航的设备、测试基线） |
| `l3goWithQuickstep` | `l3go` + `withQuickstep` | Go 设备完整版（1-2GB RAM） |
| `l3goWithoutQuickstep` | `l3go` + `withoutQuickstep` | Go 设备精简版（极低内存） |

> **Gradle 命名规则**：`<app-flavor><Recents-flavor><BuildType>`，如 `aospWithQuickstepDebug`、`l3goWithoutQuickstepRelease`。

---

### 2.2 编译期替换机制（`sourceSets`）

**核心原理**：同一个包名/类名，按 variant 从不同目录编译，**零运行时判断**。

```groovy
// build.gradle 关键片段
flavorDimensions "app", "recents"

productFlavors {
    aosp { dimension "app" }
    l3go { dimension "app" }

    withQuickstep {
        dimension "recents"
        minSdkVersion 28   // Quickstep 需 API 28+
    }
    withoutQuickstep { dimension "recents" }
}

sourceSets {
    // 公共基础代码
    main {
        java.srcDirs = ['src', 'src_plugins', 'shared/src', ...]
        manifest.srcFile 'AndroidManifest-common.xml'
    }

    // aosp 专用（flags、shortcuts 覆盖）
    aosp {
        java.srcDirs = ['src_flags', 'src_shortcuts_overrides']
    }

    // 无 Quickstep：仅基础 Launcher 实现
    aospWithoutQuickstep {
        java.srcDirs = ['src_no_quickstep']
        manifest.srcFile "AndroidManifest.xml"
    }

    // 有 Quickstep：完整实现
    aospWithQuickstep {
        manifest.srcFile "quickstep/AndroidManifest-launcher.xml"
    }

    // Go 版资源/代码
    l3go {
        res.srcDirs = ['go/res']
        java.srcDirs = ['go/src']
        manifest.srcFile "go/AndroidManifest.xml"
    }

    // withoutQuickstep 维度补丁（两个 app flavor 共用）
    withoutQuickstep {
        java.srcDirs = ['src_ui_overrides']
        proto { srcDirs = ['protos_overrides/'] }
    }

    // withQuickstep 维度补丁（两个 app flavor 共用）
    withQuickstep {
        res.srcDirs = ['quickstep/res', 'quickstep/recents_ui_overrides/res']
        java.srcDirs = ['quickstep/src', 'quickstep/recents_ui_overrides/src',
                        'quickstep/src_protolog', 'quickstep/dagger', ...]
        proto { srcDirs = ['quickstep/protos_overrides/'] }
        manifest.srcFile "quickstep/AndroidManifest.xml"
    }
}
```

**关键替换点**：

| 组件 | `withQuickstep` 来源 | `withoutQuickstep` 来源 | 差异 |
|:-----|:---------------------|:------------------------|:-----|
| `QuickstepLauncher` | `quickstep/src/.../uioverrides/QuickstepLauncher.java` | `src_ui_overrides/.../QuickstepLauncher.java` | 前者集成手势/概览/任务栏，后者仅继承 `Launcher` |
| `LauncherAppComponent` | `quickstep/dagger/QuickstepBaseAppComponent.java` | `dagger/LauncherAppComponent.java` | 前者额外绑定 `TouchInteractionService`、`RecentsModel` 等 |
| `AndroidManifest` | `quickstep/AndroidManifest.xml`（含 `RecentsActivity`、`TouchInteractionService`、权限） | `AndroidManifest.xml`（仅基础 `Launcher`） | 系统组件声明完全不同 |
| Proto 定义 | `quickstep/protos_overrides/` | `protos_overrides/` | 手势/概览相关协议仅在 withQuickstep 存在 |

---

### 2.3 三个典型 Variant 对比表

| 特性 | `aospWithQuickstep` | `aospWithoutQuickstep` | `l3goWithQuickstep` |
|:-----|:---------------------|:------------------------|:---------------------|
| **手势导航** | ✅ `TouchInteractionService` | ❌ | ✅ (简化) |
| **概览/最近任务** | ✅ `RecentsActivity` | ❌ | ✅ (低内存模式) |
| **任务栏** | ✅ `Taskbar` | ❌ | ❌ |
| **分屏选择** | ✅ `SplitSelectActivity` | ❌ | ✅ |
| **最小 SDK** | 28 | 26 | 28 (Go 要求) |
| **内存优化** | 标准 | 标准 | 激进（图标压缩、动画降级、后台清理） |
| **资源分辨率** | 标准 | 标准 | `go/res` 低分辨率 |
| **Dagger 组件** | `QuickstepBaseAppComponent` | `LauncherAppComponent` | `QuickstepBaseAppComponent` + Go 绑定 |
| **预装场景** | Pixel / 标准手机 | 无手势导航设备、车载、电视 | Android Go 设备 (1-2GB RAM) |

---

### 2.4 如何选择/构建

```bash
# 列出所有 variant
./gradlew :app:assembleDebug --dry-run | grep "assemble.*Debug"

# 构建默认完整版
./gradlew :app:assembleAospWithQuickstepDebug

# 构建精简桌面版
./gradlew :app:assembleAospWithoutQuickstepDebug

# 构建 Go 版
./gradlew :app:assembleL3goWithQuickstepDebug

# 安装指定 variant
adb install -r app/build/outputs/apk/aospWithQuickstep/debug/app-aospWithQuickstep-debug.apk
```

> **注意**：所有 variant 共用 `applicationId = 'com.android.launcher3'`，**手机上同一时间只能安装一个**。切换 variant 需先卸载再装。

---

## 3. Activity 继承链（冷启动调用栈）

```
QuickstepLauncher (extends Launcher)
    └─ Launcher (extends StatefulActivity<LauncherState>)
           └─ StatefulActivity (extends BaseActivity)
                  └─ BaseActivity (extends Activity)
                         └─ ContextThemeWrapper → ContextWrapper → Context
```

### 关键回调顺序（冷启动）

| 顺序 | 类/方法 | 核心工作 |
|------|---------|----------|
| 1 | `LauncherApplication.onCreate()` | 全局单例初始化 |
| 2 | `BaseActivity.onCreate()` | `ActivityContext`、显示信息监听注册 |
| 3 | `StatefulActivity.onCreate()` | `StateManager` 状态机初始化 |
| 4 | `Launcher.onCreate()` | **核心 UI/数据初始化**（见下文） |
| 5 | `onStart()` → `onResume()` | 状态机处理 `onResume` 转换 |
| 6 | `LauncherModel` 绑定完成回调 | `Workspace` 绑定图标 → 首帧绘制完成 |

---

## 4. `Launcher.onCreate()` 核心初始化细节

```java
@Override
protected void onCreate(Bundle savedInstanceState) {
    // 1. 启动计时
    mStartupLatencyLogger = createStartupLatencyLogger(...);
    mStartupLatencyLogger.logStart(LAUNCHER_LATENCY_STARTUP_TOTAL_DURATION);

    // 2. 设备配置（网格、热座、图标大小、边距…）
    InvariantDeviceProfile idp = InvariantDeviceProfile.INSTANCE.get(this);

    // 3. 核心 UI 容器
    mDragController = new DragController(this);
    mWorkspace = findViewById(R.id.workspace);
    mHotseat = findViewById(R.id.hotseat);
    mAppsView = findViewById(R.id.apps_view);
    mScrimView = findViewById(R.id.scrim);
    mDropTargetBar = findViewById(R.id.drop_target_bar);

    // 4. 数据模型 & 图标管线
    mModel = LauncherModel.getModel(this);
    mIconCache = IconCache.INSTANCE.get(this);
    mModel.initialize(LauncherAppState.getInstance(this));  // 异步加载开始

    // 5. 状态机进入初始状态
    mStateManager = new StateManager<>(this, LauncherState.NORMAL);
    mStateManager.goToState(LauncherState.NORMAL);
}
```

---

## 5. 关键子系统启动顺序

| 子系统 | 入口类 | 触发时机 |
|--------|--------|----------|
| **数据模型** | `LauncherModel` (`model/LauncherModel.java`) | `Launcher.onCreate()` → `initialize()` |
| **图标缓存** | `IconCache` (`icons/IconCache.java`) | `LauncherModel` 初始化时 |
| **工作区/热座** | `Workspace`、`Hotseat` (`workspace/`) | `Launcher.onCreate()` → `bindWorkspace()` |
| **全应用列表** | `ActivityAllAppsContainerView` (`allapps/`) | 首次进入 `ALL_APPS` 状态 |
| **手势/QuickStep** | `TouchInteractionService` (`quickstep/TouchInteractionService.java`) | 系统 `BOOT_COMPLETED` 绑定 |
| **概览/任务切换** | `RecentsActivity` (`quickstep/RecentsActivity.java`) | 手势上滑 / 最近任务键 |
| **状态机** | `StateManager` (`statemanager/StateManager.java`) | `StatefulActivity.onCreate()` |

---

## 6. 状态机驱动的 UI 状态流转

```
LauncherState 枚举：
  NORMAL → OVERVIEW → ALL_APPS
    ↑         ↓           ↓
    └─────────┴───────────┘  (手势/按键/后退键触发)
```

**核心类**：
- `StateManager` — 状态转换调度、动画协调
- `StateHandler` — 每个状态的 `onEnterState` / `onExitState` / `onProgressToState`
- `LauncherState` — `NORMAL`、`OVERVIEW`、`ALL_APPS`、`SPLIT_SELECT` 等

**典型转换**：
```java
// 手势上滑进入概览
mStateManager.goToState(LauncherState.OVERVIEW, animationConfig);

// 点击图标启动应用
mStateManager.goToState(LauncherState.NORMAL, LAUNCH_ANIM_CONFIG);
```

---

## 7. 如何在 IDE 中跟踪运行流程

### 断点建议

| 场景 | 关键断点 |
|------|----------|
| **冷启动全链路** | `LauncherApplication.onCreate` → `Launcher.onCreate` → `LauncherModel.initialize` → `Workspace.bind*` |
| **图标加载** | `IconCache.getTitleAndIcon` / `IconCache.updateIcon` / `BitmapRenderer.renderIcon` |
| **手势上滑进入概览** | `TouchInteractionService.onTouchInteractionStart` → `RecentsAnimationController.startAnimation` |
| **状态切换** | `StateManager.goToState` / `StateHandler.onEnterState` |
| **拖拽/文件夹创建** | `DragController.startDrag` → `DropTarget.onDrop` / `onDropCompleted` |
| **Dagger 依赖图** | `LauncherAppComponent` / `QuickstepBaseAppComponent` 接口方法 |

### Logcat 过滤标签

```bash
adb logcat -s Launcher LauncherModel IconCache StateManager \
  TouchInteractionService RecentsAnimationController DragController
```

### 调试开关

```bash
# 运行时开启详细日志
adb shell setprop debug.launcher3.verbose true

# 或在 build.gradle 中
buildConfigField "boolean", "DEBUG_LAUNCHER", "true"
```

---

## 8. 核心文件速查表

| 功能 | 文件路径 |
|------|----------|
| Application 初始化 | `src/com/android/launcher3/LauncherApplication.java` |
| 基础 Launcher Activity | `src/com/android/launcher3/Launcher.java` |
| Quickstep 专用 Launcher | `quickstep/src/com/android/launcher3/uioverrides/QuickstepLauncher.java` |
| 状态机核心 | `src/com/android/launcher3/statemanager/StateManager.java` |
| 数据模型加载 | `src/com/android/launcher3/model/LauncherModel.java` |
| 图标缓存管线 | `src/com/android/launcher3/icons/IconCache.java` |
| 工作区布局 | `src/com/android/launcher3/workspace/Workspace.java` |
| 手势服务 | `quickstep/src/com/android/quickstep/TouchInteractionService.java` |
| 概览动画 | `quickstep/src/com/android/quickstep/RecentsAnimationController.java` |
| Dagger 组件 | `dagger/src/com/android/launcher3/dagger/LauncherAppComponent.java` |
| 设备配置 | `src/com/android/launcher3/InvariantDeviceProfile.java` |
| 启动计时 | `src/com/android/launcher3/logging/StartupLatencyLogger.java` |

---

## 9. 快速构建与运行

```bash
# 1. 编译默认 variant (withquickstep)
./gradlew :app:assembleWithquickstepDebug

# 2. 安装
adb install -r app/build/outputs/apk/withquickstep/debug/app-withquickstep-debug.apk

# 3. 启动
adb shell am start -n com.android.launcher3/com.android.launcher3.Launcher

# 4. 查看启动耗时
adb logcat -s StartupLatencyLogger
```

---

## 10. 架构要点总结

1. **进程起点** = `LauncherApplication`（全局单例 + Dagger）
2. **UI 起点** = `Launcher` / `QuickstepLauncher`（按 variant 编译期替换）
3. **核心驱动** = `StatefulActivity` 状态机 + `LauncherModel` 异步数据绑定 + `IconCache` 图标管线
4. **Quickstep 扩展** = 系统级服务 `TouchInteractionService` + `RecentsActivity` + `Taskbar` 等
5. **Variant 隔离** = Gradle `sourceSets` 实现同包名不同实现，**无运行时判断**

---

> 文档基于 `compileSdk 36` / `AGP 9.2.1` / `Kotlin 2.4.10` / `JDK 21` 环境。  
> 如需深入某子系统（手势、图标、状态机、小部件等），可单独展开调用链分析。

---

## 11. 核心实现原理深度解析

### 11.1 三大核心支柱

| 支柱 | 核心类 | 职责 |
|------|--------|------|
| **数据层** | `LauncherModel` + `BgDataModel` | 维护内存中的完整桌面数据模型（应用、快捷方式、文件夹、Widget、热座） |
| **渲染层** | `Workspace` + `IconCache` + `ItemInflater` | 把数据模型 → View 树，含图标生成、布局、动画 |
| **状态层** | `StateManager` + `LauncherState` | 管理 NORMAL/OVERVIEW/ALL_APPS 等状态切换及过渡动画 |

---

### 11.2 数据流向（单向数据流）

```
PackageManager / SystemServer
        │
        ▼
┌───────────────────────┐
│  LauncherModel        │  ← 后台线程 (MODEL_EXECUTOR)
│  - BgDataModel        │     • 扫描已安装包
│  - AllAppsList        │     • 读取/写入 LauncherProvider (SQLite)
│  - LoaderTask[]       │     • 增量更新 (PackageUpdatedTask 等)
└─────────┬─────────────┘
          │ bindWorkspace() / bindAllApps()
          ▼
┌───────────────────────┐
│  Workspace /          │  ← 主线程
│  ActivityAllAppsContainerView
│  - ItemInflater       │     • ItemInfo → View (BubbleTextView, FolderIcon, Widget)
│  - IconCache.get()    │     • 图标异步生成/复用
└───────────────────────┘
```

**关键点**：
- **后台加载、主线程绑定** —— `LoaderTask` 跑在 `MODEL_EXECUTOR`，完成后回调到主线程 `bindItems()`
- **增量更新** —— 安装/卸载/更新包触发 `PackageUpdatedTask`，只重算 diff
- **数据库即真相源** —— `LauncherProvider` (ContentProvider) 持久化 `favorites` 表，跨进程同步

---

### 11.3 图标管线（IconCache）

```
ItemInfo (ComponentName/UserHandle)
        │
        ▼
┌────────────────────────────────────┐
│ IconCache.getTitleAndIcon()        │
│  1. 内存 L1 缓存 (FastBitmapDrawable) │
│  2. 磁盘 L2 缓存 (SQLite icons.db)   │
│  3. 实时渲染 (BitmapRenderer)        │
│     - 自适应图标 / 单色图标 / 圆角     │
│     - 主题色提取 (Material You)      │
└─────────┬──────────────────────────┘
          ▼
    FastBitmapDrawable (含 Bitmap + 圆角路径 + 阴影)
```

- **三级缓存**：内存 → 磁盘 → 实时渲染
- **异步预加载**：`IconCache.updateIcon()` 提交到 `MODEL_EXECUTOR`，完成再回主线程 `onIconUpdated()`
- **共享池**：`FastBitmapDrawable` 可跨 View 复用，减少 GC

---

### 11.4 状态机驱动 UI（StateManager）

```
LauncherState 枚举（部分）：
  NORMAL          ← 默认桌面
  OVERVIEW        ← 最近任务/概览
  ALL_APPS        ← 全应用列表
  SPLIT_SELECT    ← 分屏选择
  QUICK_SWITCH    ← 快速切换
  TASKBAR_*       ← 任务栏相关状态

转换示例：
用户上滑手势
    │
    ▼
TouchInteractionService → RecentsAnimationController
    │
    ▼
StateManager.goToState(OVERVIEW, animationConfig)
    │
    ├─► StateHandler<OVERVIEW>.onEnterState()  // 显示 RecentsView、隐藏 Workspace
    ├─► PendingAnimation 应用属性动画 (alpha, translation, scale)
    └─► 动画结束 → 状态锁定
```

**每个状态持有**：
- `StateHandler`：进入/退出/进度回调
- `StateAnimationConfig`：动画参数（时长、缓动、哪些属性参与）
- `LauncherRootView` 统一分发触摸/绘制

---

### 11.5 布局系统（Workspace + CellLayout）

```
Workspace (PagedView)
    ├─ Page 0 (CellLayout)
    │    ├─ Cell (0,0) → BubbleTextView (App)
    │    ├─ Cell (1,0) → FolderIcon
    │    └─ Cell (2,0) → WidgetHostView
    ├─ Page 1 (CellLayout)
    │    └─ ...
    └─ Hotseat (独立 CellLayout，固定底部)
```

- **网格坐标系**：`CellPosMapper` 把 (page, x, y) ↔ 线性索引
- **自动重排**：拖拽时 `ReorderAlgorithm` 计算目标位置，动画飞入
- **响应式规格**：`InvariantDeviceProfile` 根据屏幕宽高/密度决定 `numColumns`、`numRows`、`iconSize`、`hotseatCount`

---

### 11.6 Quickstep 扩展（系统级能力）

| 组件 | 进程 | 通信方式 | 作用 |
|------|------|----------|------|
| `TouchInteractionService` | SystemUI (system_uid) | AIDL `IQuickStepService` | 接管手势导航、触摸流 |
| `RecentsActivity` | Launcher (同进程) | 直接调用 | 概览 UI、任务缩略图 |
| `Taskbar` | Launcher | 直接调用 | 底部任务栏、手势提示 |
| `SysUI/WM Shell` | SystemServer | `WindowManager` + `ActivityTaskManager` | 任务快照、窗口动画、分屏 |

**关键流程**：用户上滑 → `TouchInteractionService.onTouchInteractionStart()` → 请求任务快照 → 启动 `RecentsActivity` → `StateManager` 切 `OVERVIEW` → 动画由 `RecentsAnimationController` 与 `SurfaceControl` 同步。

---

### 11.7 依赖注入（Dagger/Hilt）

```
@LauncherAppSingleton
class LauncherModel @Inject constructor(
    IconCache,
    ItemInstallQueue,
    ModelTaskController,
    ModelInitializer,
    ...
)

@LauncherAppSingleton
class IconCache @Inject constructor(
    Context,
    InvariantDeviceProfile,
    BitmapRenderer,
    ...
)
```

- **编译期生成** `DaggerLauncherAppComponent` / `DaggerQuickstepBaseAppComponent`
- **单例作用域** = 进程生命周期（`LauncherApplication` 持有）
- **Variant 隔离**：`withquickstep` 用 `QuickstepBaseAppComponent`，额外绑定 `TouchInteractionService` 等

---

### 11.8 启动性能优化要点

| 优化 | 实现 |
|------|------|
| **冷启动并行** | `MainProcessInitializer` 并行初始化 10+ 单例 |
| **预绑定** | `LauncherModel` 先绑定首屏，其余分页懒加载 |
| **图标预渲染** | `IconCache` 启动时预热高频应用 |
| **Trace 埋点** | `StartupLatencyLogger` 每阶段计时，`adb logcat -s StartupLatencyLogger` 可视化 |
| **严格模式** | `DEBUG_STRICT_MODE` 捕获主线程 IO |

---

### 11.9 完整交互生命周期：点击图标启动 App

```
1. 用户点击 BubbleTextView
   │
   ▼
2. Workspace.onTouchEvent → DragController.startDrag (如果是长按)
   或直接 ItemClickHandler.onClick()
   │
   ▼
3. Launcher.startActivitySafely(v, intent, opts)
   │
   ▼
4. StateManager.goToState(NORMAL, LAUNCH_ANIM_CONFIG)
   │
   ├─► LauncherState.NORMAL handler: 无动画或轻微缩放
   ├─► ActivityOptions.makeScaleUpAnimation(v, 0, 0, w, h)
   │
   ▼
5. startActivity(intent, opts) → SystemServer → 目标 App 进程启动
   │
   ▼
6. Launcher.onPause() → StateManager 处理 onPause
   │
   ▼
7. 目标 App 绘制首帧 → WindowManager 通知 Launcher 动画结束
   │
   ▼
8. Launcher.onStop() → 可选释放非必要资源
```

---

### 11.10 关键文件架构图（按模块）

```
src/com/android/launcher3/
├── LauncherApplication.java          # 进程入口、Dagger
├── Launcher.java                     # 基础 Activity、状态机宿主
├── LauncherModel.kt                  # 数据层总管
├── model/
│   ├── BgDataModel.java              # 内存数据模型
│   ├── LoaderTask.java               # 后台加载任务抽象
│   ├── PackageUpdatedTask.java       # 增量更新
│   └── ModelWriter.java              # DB 写入
├── icons/
│   ├── IconCache.java                # 三级缓存
│   ├── BitmapRenderer.java           # 实时渲染
│   └── cache/                        # L1/L2 缓存实现
├── workspace/
│   ├── Workspace.java                # PagedView、布局、拖拽
│   ├── CellLayout.java               # 网格容器
│   ├── Hotseat.java                  # 底部固定栏
│   └── WorkspaceLayoutManager.java   # 响应式规格计算
├── statemanager/
│   ├── StateManager.java             # 状态机核心
│   ├── BaseState.java                # 状态定义
│   └── StatefulActivity.java         # Activity 基类
├── allapps/
│   ├── ActivityAllAppsContainerView.kt
│   └── AllAppsRecyclerView.java
├── folder/
│   ├── FolderIcon.java
│   └── FolderPagedView.java
├── dragndrop/
│   ├── DragController.java
│   └── DropTarget.java
└── util/                             # 线程池、日志、工具类
```

---

### 11.11 怎么继续深入？

| 目标 | 入手文件 | 关键断点 |
|------|----------|----------|
| 理解数据加载 | `LauncherModel.kt` `initialize()` | `LoaderTask.run()` |
| 理解图标渲染 | `IconCache.java` `getTitleAndIcon()` | `BitmapRenderer.renderIcon()` |
| 理解状态切换 | `StateManager.java` `goToState()` | `StateHandler.onEnterState()` |
| 理解手势导航 | `TouchInteractionService.java` | `onTouchInteractionStart()` |
| 理解拖拽排序 | `DragController.java` `startDrag()` | `ReorderAlgorithm.reorder()` |
| 理解 Widget | `WidgetManagerHelper.java` | `bindAppWidget()` |

---

### 11.12 底层哲学

> **"所有 UI 都是数据的投影，所有交互都是状态的迁移，所有性能都是异步管线的吞吐。"**

---

> 文档基于 `compileSdk 36` / `AGP 9.2.1` / `Kotlin 2.4.10` / `JDK 21` 环境。  
> 如需深入某子系统（手势、图标、状态机、小部件等），可单独展开调用链分析。
---

## 12. 构建与安装问题排查记录

### 12.1 环境基线

| 组件 | 版本 | 说明 |
|------|------|------|
| **Gradle** | 9.7.1 | `gradle/wrapper/gradle-wrapper.properties` |
| **AGP** | 8.3.1 | `gradle.properties: GRADLE_CLASS_PATH` |
| **Kotlin** | 2.0.20 | `gradle.properties: KOTLIN`（从 2.0.0 升级） |
| **compileSdk** | 36 | Android 16 (Baklava) |
| **buildTools** | 36.1.0 | SDK 已安装版本 |
| **JDK** | 21.0.12.1 | 项目自带 `jdk-21.0.12.1+1/` |

---

### 12.2 遇到的问题及修复

#### 问题 1：KAPT 与 Gradle 9.7.1 不兼容

**报错**：
```
NoSuchMethodError: 'org.gradle.api.file.FileCollection org.gradle.api.artifacts.Configuration.fileCollection(org.gradle.api.specs.Spec)'
    at org.jetbrains.kotlin.gradle.internal.Kapt3GradleSubplugin$createKaptKotlinTask$2.invoke(Kapt3KotlinGradleSubplugin.kt:405)
```

**原因**：Kotlin 2.0.0 的 KAPT 插件调用了 Gradle 9.7 中已移除的 API。

**修复**：`gradle.properties`
```properties
KOTLIN=org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.20
```

> ✅ Kotlin 2.0.20 是兼容 Gradle 9.7+ 的首个稳定版。

---

#### 问题 2：Build Tools 版本缺失

**报错**：
```
Failed to find Build Tools revision 34.0.0
```

**原因**：`compileSdk 36` 对应 Build Tools 36.x，但 `gradle.properties` 写死 `34.0.0`，且本机未安装。

**修复**：`gradle.properties`
```properties
BUILD_TOOLS_VERSION=36.1.0
```

---

#### 问题 3：子模块未继承 buildToolsVersion

**报错**：
```
Could not determine the dependencies of task ':animationlib:compileDebugJavaWithJavac'.
> Failed to find Build Tools revision 34.0.0
```

**原因**：库模块 `animationlib`、`flags`、`contextualeducationlib`、`iconloaderlib` 无 `buildToolsVersion` 配置，回退默认值。

**修复**：根 `build.gradle` 新增 `subprojects` 块统一配置：
```groovy
subprojects {
    plugins.withId('com.android.library') {
        android {
            buildToolsVersion BUILD_TOOLS_VERSION
        }
    }
}
```

---

#### 问题 4：国内网络无法访问 dl.google.com

**报错**：
```
Could not GET 'https://dl.google.com/dl/android/maven2/...'
Connect to dl.google.com:443 failed: Connection timed out
```

**原因**：Gradle 优先尝试 `google()` 仓库，国内超时。

**修复**：移除 `google()`，仅保留阿里云镜像（`build.gradle` 两处 `repositories`）：
```groovy
repositories {
    mavenCentral()
    maven { url 'https://maven.aliyun.com/repository/google' }
    maven { url 'https://maven.aliyun.com/repository/jcenter' }
    maven { url 'https://maven.aliyun.com/repository/central' }
    maven { url 'https://maven.aliyun.com/repository/gradle-plugin' }
}
```

---

#### 问题 5：Gradle 未使用项目自带 JDK 21

**报错**：
```
错误: 无效的源发行版：21
```

**原因**：系统默认 JDK 可能是 17/18，Gradle 未识别 `java.home`。

**修复**：`gradle.properties`
```properties
org.gradle.java.home=C:/Users/zhao2/Desktop/Android/Launcher3/jdk-21.0.12.1+1
```

---

#### 问题 6：Kotlin 增量编译缓存损坏

**报错**：
```
Could not close incremental caches in .../build/kotlin/compileDebugKotlin/cacheable/...: subtypes.tab, class-fq-name-to-source.tab
```

**原因**：上次构建中断导致增量缓存不一致。

**修复**：
```bash
gradlew.bat --stop
# 手动删除所有 build/ 目录
Remove-Item -Recurse -Force animationlib\build, flags\build, iconloaderlib\build, contextualeducationlib\build, build
```

---

#### 问题 7：INSTALL_FAILED_SHARED_USER_INCOMPATIBLE

**报错**：
```
INSTALL_FAILED_SHARED_USER_INCOMPATIBLE: Package com.android.launcher3 has no signatures that match those in shared user android.uid.system
```

**原因**：`AndroidManifest-common.xml` 声明 `android:sharedUserId="android.uid.system"`，要求平台签名。

**修复**：注释掉该属性（仅开发调试用）：
```xml
<!-- AndroidManifest-common.xml -->
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools"
    package="com.android.launcher3">
    <!-- android:sharedUserId="android.uid.system" -->
```

> ⚠️ 去掉后无法访问 system_uid 共享资源（部分系统 API、隐藏设置）。完整功能需平台签名或刷入 system 分区。

---

#### 问题 8：启动 Activity 报错不存在

**报错**：
```
Error: Activity class {com.android.launcher3/com.android.launcher3.Launcher} does not exist.
```

**原因**：`aospWithQuickstep` variant 实际类是 `QuickstepLauncher`（`quickstep/src/.../uioverrides/QuickstepLauncher.java`），非基础 `Launcher`。

**修复**：使用正确的 Activity 名启动：
```bash
adb shell am start -n com.android.launcher3/com.android.launcher3.uioverrides.QuickstepLauncher
```

---

### 12.3 最终可用的构建/安装/启动命令

```bash
# 1. 清理（可选，缓存异常时）
gradlew.bat --stop
Remove-Item -Recurse -Force animationlib\build, flags\build, iconloaderlib\build, contextualeducationlib\build, build

# 2. 构建默认完整版
gradlew.bat assembleAospWithQuickstepDebug --no-daemon

# 3. 安装
adb install -r build/outputs/apk/aospWithQuickstep/debug/SdLauncher3.apk

# 4. 启动（withQuickstep variant）
adb shell am start -n com.android.launcher3/com.android.launcher3.uioverrides.QuickstepLauncher
```

---

### 12.4 关键配置文件最终状态

**gradle.properties**：
```properties
org.gradle.jvmargs=-Xmx4g -XX:MaxMetaspaceSize=1g
org.gradle.java.home=C:/Users/zhao2/Desktop/Android/Launcher3/jdk-21.0.12.1+1
org.gradle.parallel=true
kotlin.daemon.jvmargs=-Xmx4g
ANDROID_X_VERSION=1+
GRADLE_CLASS_PATH=com.android.tools.build:gradle:8.3.1
PROTOBUF_CLASS_PATH=com.google.protobuf:protobuf-gradle-plugin:0.9.4
BUILD_TOOLS_VERSION=36.1.0
COMPILE_SDK=android-36
KOTLIN=org.jetbrains.kotlin:kotlin-gradle-plugin:2.0.20
```

**build.gradle 关键改动**：
- `buildscript` / `allprojects` repositories 仅用阿里云镜像
- 新增 `subprojects { plugins.withId('com.android.library') { android { buildToolsVersion BUILD_TOOLS_VERSION } } }`
- `compileSdk 36`、`buildToolsVersion BUILD_TOOLS_VERSION`

**AndroidManifest-common.xml**：
```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools"
    package="com.android.launcher3">
    <!-- android:sharedUserId="android.uid.system" -->
```

---

### 12.5 后续开发注意事项

| 场景 | 注意事项 |
|------|----------|
| **切换 variant** | 同 `applicationId`，需先卸载再装 |
| **Quickstep 手势/概览** | 需 `TouchInteractionService` 运行在 system 进程，普通安装无法工作；完整功能需平台签名或 AOSP 模拟器 |
| **系统权限 API** | 去掉 `sharedUserId` 后，`WRITE_SECURE_SETTINGS`、`MANAGE_ACTIVITY_TASKS` 等权限会被拒绝 |
| **Kotlin 升级** | 若升级 Kotlin/AGP/Gradle，需同步验证 KAPT 兼容性 |
| **增量构建异常** | 优先 `gradlew --stop` + 删 `build/` 再试 |
| **网络依赖** | 镜像源优先级：阿里云 > mavenCentral；避免 `google()`、`jcenter()` |

