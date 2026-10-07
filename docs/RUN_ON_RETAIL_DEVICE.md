# 在零售机（红米 K70 / HyperOS / Android 16）上以普通应用身份运行 AOSP Launcher3

> 记录 2026-10-07 将本工程（`hpz` 分支）构建的 quickstep 桌面跑在红米 K70（23113RKC6C，Android 16 / API 36 / HyperOS）上的完整过程：排查步骤、每处代码改动的原因、以及"AOSP 系统应用如何以第三方普通应用身份运行"的原理。

---

## 一、核心问题：系统应用和普通应用之间隔了三道墙

AOSP Launcher3 在 AOSP 设备上是**平台签名的系统应用**，享受三类特殊待遇。装到零售 HyperOS 上变成普通第三方应用后，这三类待遇全部消失，构成三道墙：

### 墙 1：隐藏 API 强拦截（本次闪退的主因）

AOSP 桌面大量调用 `@hide` 的平台 API（编译时用的是 [prebuilts/libs/framework.jar](../prebuilts/libs/framework.jar)，AOSP 平台内部框架，包含隐藏 API）。在 AOSP 设备上系统应用不受限制；但 Android 9 起，**targetSdk 28+ 的普通应用**访问隐藏 API 会被 ART 运行时按名单拦截：

| 名单 | 行为 |
|---|---|
| SDK（公开 API） | 正常调用 |
| unsupported（浅灰名单） | 警告日志，允许 |
| blocked（黑名单） | **直接抛 `NoSuchMethodError` / `SecurityException`** |

本工程源码中仅 `Context.getDisplayId()` 就有 **74 处**调用（全在 quickstep/base 里），此外还有 `InteractionJankMonitor`、`HardwareRenderer.setContextPriority` 等几十处 blocked 级调用。

### 墙 2：signature / privileged 级权限

quickstep 的核心能力依赖只有系统应用才能拿到的权限（普通安装永远拿不到）：

- `MANAGE_ACTIVITY_TASKS`（查询任务栈 `getRootTaskInfo`）
- `CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS`（注册远程转场动画）
- `STATUS_BAR_SERVICE`（SystemUI 绑定 `TouchInteractionService`）
- `READ_FRAME_BUFFER`、`WATCH_APPOPS` 等

### 墙 3：厂商框架差异

HyperOS 的 framework-res.apk 和 MiuiResources 对 AOSP 框架资源表做过增删改，AOSP 侧引用的某些内部资源 ID 在小米框架上不存在；部分公开 API 行为也更严格。

**结论**：不改代码直接装，必然闪退。我们的目标是在**不 root、不刷机**的前提下，用最小改动让它在普通应用身份下可运行——能降级的降级（recents 等系统级功能本来就是 HyperOS 接管），能自救的自救。

---

## 二、排查过程还原（5 轮迭代）

### 第 0 轮：环境确认

```bash
adb devices                                  # 确认设备（首次 offline，重启 adb server 并手机授权后变 device）
adb shell getprop ro.product.model           # 23113RKC6C（红米 K70）
adb shell getprop ro.build.version.sdk       # 36（Android 16）
adb shell pm list packages | findstr launcher3
```

### 第 1 轮：抓到第一个崩溃（隐藏 API 拦截）

```bash
adb logcat -c                                                        # 清空日志
adb shell am start -n com.android.launcher3/com.android.launcher3.uioverrides.QuickstepLauncher
adb logcat -d -b crash                                               # 只读崩溃缓冲区
```

堆栈关键部分：

```
Caused by: java.lang.NoSuchMethodError: No virtual method getDisplayId()I
    in class Landroid/content/Context; (declaration in /system/framework/framework.jar)
    at com.android.quickstep.views.RecentsView.<init>(RecentsView.java:883)
```

**为什么 `NoSuchMethodError` 而不是权限警告？** 这是隐藏 API 拦截的典型表现：ART 在链接期直接让调用失败，效果等同"方法不存在"。

证据链（三步实锤，避免猜测）：

```bash
# 1. 编译期用的 framework.jar 里有没有这个方法？—— 有（public abstract int getDisplayId()）
jdk-21.0.12.1+1\bin\javap.exe -classpath prebuilts\libs\framework.jar android.content.Context | findstr DisplayId

# 2. 公开 SDK（API 36 android.jar）里有没有？—— 没有！说明它不是公开 API，是隐藏 API
javap.exe -classpath %LOCALAPPDATA%\Android\Sdk\platforms\android-36\android.jar android.content.Context | findstr DisplayId

# 3. 运行时谁在拦？—— logcat 主缓冲区里有运行时亲自留下的证据：
adb logcat -d | findstr hiddenapi
# hiddenapi: Accessing hidden method Lcom/android/internal/jank/InteractionJankMonitor;->getInstance()
#   (runtime_flags=0, domain=platform, api=blocked) from .../base.apk (domain=app) using linking: denied
```

`api=blocked ... using linking: denied` —— 实锤是黑名单拦截。

**方案选择过程：**

| 候选方案 | 结论 |
|---|---|
| 逐处改代码避开隐藏 API | ❌ 仅 `getDisplayId` 就 74 处，修一处崩一处，不可收敛 |
| adb 全局关拦截：`settings put global hidden_api_policy 1` | ❌ HyperOS 剥夺了 shell 的 `WRITE_SECURE_SETTINGS` 权限（原生 Android 有），`SecurityException` |
| adb 按包豁免：`device_config put hidden_api_blacklist_exemptions ...` | ❌ 同样被权限封堵 |
| root 后改系统配置 | ❌ 设备未 root |
| ✅ **进程内自我豁免** | 应用在自身进程里通过 meta-reflection 把自己加入豁免名单，无需任何系统权限 |

### 第 2~5 轮：每修一处，跑得更远一截，暴露下一个问题

每轮的循环都是同一套命令：`gradlew assembleAospWithQuickstepDebug` → `adb install -r` → `am start` → `logcat -d -b crash` 读新堆栈 → 定位 → 修复。详见下表：

| 轮次 | 崩溃签名 | 根因分析 | 修复决策 |
|---|---|---|---|
| 1 | `NoSuchMethodError: Context.getDisplayId()` | 隐藏 API 黑名单拦截 | 进程内豁免（见 3.1） |
| 2 | `Resources$NotFoundException: 0x1040239`（`MiuiResources.getText`） | 引用 Pixel 专属内部资源 `config_defaultContextualSearchPackageName`，小米框架无此资源 | catch 后降级禁用该功能（见 3.2） |
| 3 | `UnsupportedOperationException: Tried to obtain display from a Context not associated with one` | 用应用级 Context（非可视化）调 `getDisplay()`；AOSP 上此路径由系统身份兜住，HyperOS 上直接抛 | fallback 到 `createDisplayContext`（见 3.3） |
| 4 | `SecurityException: Settings key <one_handed_mode_enabled> is not readable` | S+ 起 @hide 且无 @Readable 注解的 settings key 只有系统应用可读；且这是统一入口 | 在 `SettingsCache` 统一兜底，一次覆盖整类（见 3.4） |
| 5 | `SecurityException: getRootTaskInfo() requires MANAGE_ACTIVITY_TASKS` | 原代码只 catch 了 `RemoteException`，漏了 binder 权限拒绝 | 补 catch（见 3.5） |
| 5 | `SecurityException: registerRemoteAnimations requires CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS` | 远程动画注册需要系统权限；第三方身份下本来也无人会调用它 | catch 后放弃注册，用系统默认转场（见 3.6） |
| 5 | `SecurityException: Caller can't access shortcut information`（launcher-loader 线程） | 非默认桌面无权访问 `LauncherApps.getActivityOverrides()` | catch 返回空 Map；设为默认桌面后自然恢复（见 3.7） |

最终验证：`logcat -b crash` 无记录、进程稳定存活、截图确认主屏/图标/dock/搜索栏正常、`cmd package set-home-activity` 成功设为默认桌面。

---

## 三、代码改动明细（共 8 个文件，+76/-13 行）

> 原则：**只兜底、不改功能逻辑**。所有降级路径仅当"以普通应用身份运行"时才触发；在 AOSP 设备上以系统身份运行时行为与原版完全一致。

### 3.1 进程内隐藏 API 豁免（关键改动）

**文件**：[build.gradle](build.gradle)、[src/com/android/launcher3/LauncherApplication.java](../src/com/android/launcher3/LauncherApplication.java)

依赖引入：

```gradle
implementation 'org.lsposed.hiddenapibypass:hiddenapibypass:6.1'
```

在 `attachBaseContext`（应用内最早的代码执行点，早于 Application.onCreate、早于所有 ContentProvider 和 Activity）设置豁免：

```java
@Override
protected void attachBaseContext(Context base) {
    super.attachBaseContext(base);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        try {
            HiddenApiBypass.setHiddenApiExemptions("L");
        } catch (Throwable ignored) {
        }
    }
}
```

**原理**：`"L"` 是所有 Java 类描述符的前缀（`Lcom/android/...;`），等效于豁免全部类。该库的巧妙之处在于：它利用"meta-reflection 豁免"漏洞——对 `java.lang.Class` 等元类做反射永远不受拦截——借道调用 `Runtime.setHiddenApiExemptions()`，从而**在无任何特权的情况下修改本进程的豁免名单**。效果仅限本进程，其他应用不受影响，普通用户设备即可使用。

### 3.2 框架资源缺失降级

**文件**：[quickstep/src/com/android/quickstep/util/ContextualSearchStateManager.java](../quickstep/src/com/android/quickstep/util/ContextualSearchStateManager.java#L101)

```java
String contextualSearchPackage;
try {
    contextualSearchPackage = mContext.getResources().getString(
            com.android.internal.R.string.config_defaultContextualSearchPackageName);
} catch (Resources.NotFoundException e) {
    contextualSearchPackage = "";   // HyperOS 无此资源，Contextual Search 本就不可用
}
```

Contextual Search 是 Pixel 专属特性，降级无损失。

### 3.3 非可视化 Context 获取屏幕参数

**文件**：[quickstep/src/com/android/quickstep/RotationTouchHelper.java](../quickstep/src/com/android/quickstep/RotationTouchHelper.java#L150)

```java
mOrientationTouchTransformer = new OrientationTouchTransformer(resources, mMode,
        () -> {
            try {
                return QuickStepContract.getWindowCornerRadius(mContext);
            } catch (UnsupportedOperationException e) {
                // 应用/服务上下文不关联 Display，改用 createDisplayContext 获取
                Display display = mContext.getSystemService(DisplayManager.class)
                        .getDisplay(DEFAULT_DISPLAY);
                return QuickStepContract.getWindowCornerRadius(
                        mContext.createDisplayContext(display));
            }
        });
```

**说明**：此处的 corner radius 是**手势触摸变换用的屏幕圆角**，不是图标圆角。公开 API 规定非可视化 Context（Application/Service）调用 `getDisplay()` 抛 `UnsupportedOperationException`，改用 `createDisplayContext(DEFAULT_DISPLAY)` 得到关联屏幕的 Context 再取，行为等价。

### 3.4 Settings 读取统一兜底

**文件**：[src/com/android/launcher3/util/SettingsCache.java](../src/com/android/launcher3/util/SettingsCache.java#L158)

```java
try {
    ... // Settings.System/Global/Secure.getInt 原逻辑不变
} catch (SecurityException e) {
    Log.w("SettingsCache", "Settings key not readable: " + key, e);
    newVal = defaultValue == 1;   // 按默认值处理并写入缓存，避免反复抛异常
}
```

选在这里兜底是因为 `SettingsCache` 是全部 settings 读取的统一入口（一处修复覆盖整类问题），且"读不到"的合理语义就是"按未开启处理"。

### 3.5 PIP 查询权限拒绝

**文件**：[quickstep/src/com/android/quickstep/RecentsAnimationDeviceState.java](../quickstep/src/com/android/quickstep/RecentsAnimationDeviceState.java#L198)

```java
} catch (RemoteException | SecurityException e) {   // 原来只 catch RemoteException
```

### 3.6 远程转场动画注册/注销

**文件**：[quickstep/src/com/android/launcher3/QuickstepTransitionManager.java](../quickstep/src/com/android/launcher3/QuickstepTransitionManager.java#L1198)

`registerRemoteAnimations()` 与 `unregisterRemoteAnimations()` 各包一层 `catch (SecurityException)`。第三方身份下系统本来就不会调用我们的远程动画（注册无意义），降级为系统默认转场。

### 3.7 Activity Overrides 访问

**文件**：[quickstep/src/com/android/launcher3/uioverrides/SystemApiWrapper.kt](../quickstep/src/com/android/launcher3/uioverrides/SystemApiWrapper.kt#L61)

```kotlin
override fun getActivityOverrides(): Map<String, LauncherActivityInfo> =
    try {
        mContext.getSystemService(LauncherApps::class.java)!!.activityOverrides
    } catch (e: SecurityException) {
        emptyMap()   // 仅默认桌面可访问；被设为默认后自动恢复正常
    }
```

### 3.8 点击图标提示"未安装该应用"（启动附加远程动画被拒）

**文件**：[quickstep/src/com/android/launcher3/QuickstepTransitionManager.java](../quickstep/src/com/android/launcher3/QuickstepTransitionManager.java#L332)、[quickstep/src/com/android/launcher3/uioverrides/SystemApiWrapper.kt](../quickstep/src/com/android/launcher3/uioverrides/SystemApiWrapper.kt#L69)

**现象**：桌面正常显示，但点击任何图标都弹"未安装该应用。"，应用无法启动。

**排查**：点击后 `logcat` 出现 `ActivityContext: Unable to launch ... SecurityException: Permission Denial: starting Intent {...} with remoteAnimationAdapter`。

**根因**：§3.6 只处理了"注册"远程动画，但 quickstep 桌面**每次打开应用**都会通过 `getActivityLaunchOptions()` 给 `startActivity` 的 options 附加 `RemoteAnimationAdapter`（自定义开屏转场），而携带它启动同样需要 `CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS` 系统权限 → 每次 launch 都被拒 → 落入 `ActivityContext.startItemActivity` 的 catch 弹出兜底提示。

**修复**：无权限时不附加远程动画，返回普通启动选项（系统默认转场）：

```java
public ActivityOptionsWrapper getActivityLaunchOptions(View v, ItemInfo itemInfo) {
    if (mLauncher.checkSelfPermission(
            android.Manifest.permission.CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS)
            != PackageManager.PERMISSION_GRANTED) {
        return new ActivityOptionsWrapper(ActivityOptions.makeBasic(), new RunnableList());
    }
    ... // 原逻辑（AOSP 系统桌面行为不变）
}
```

`SystemApiWrapper.createFadeOutAnimOptions()`（添加小组件流程使用）同理按权限降级。权限判断用 `checkSelfPermission`——在 AOSP 设备上系统签名身份拥有该权限，走原逻辑，行为无差异。

### 3.9 长按菜单点"应用列表"崩溃（CLOSE_SYSTEM_DIALOGS 被拒）

**文件**：[quickstep/src/com/android/quickstep/TaskUtils.java](../quickstep/src/com/android/quickstep/TaskUtils.java#L118)

**现象**：长按桌面弹出菜单，点"应用列表"后**整个桌面进程崩溃重启**（FATAL EXCEPTION: UiThreadHelper）。

**排查**：`adb logcat -b crash`：

```
SecurityException: Permission Denial: android.intent.action.CLOSE_SYSTEM_DIALOGS broadcast
  from com.android.launcher3 requires android.permission.BROADCAST_CLOSE_SYSTEM_DIALOGS
  at ActivityManagerWrapper.closeSystemWindows(ActivityManagerWrapper.java:223)
  at TaskUtils.lambda$closeSystemWindowsAsync$1(TaskUtils.java:120)
```

**根因**：打开应用列表前，桌面会先调 `ActivityManagerWrapper.closeSystemWindows()`（binder 到 AMS `closeSystemDialogs`，收起通知面板/系统弹窗）。Android 12 起该调用要求系统权限 `BROADCAST_CLOSE_SYSTEM_DIALOGS`（signature|privileged）。异常抛在 `UI_HELPER_EXECUTOR`（名为 "UiThreadHelper" 的 HandlerThread）上且无人捕获 → 直接杀死进程。全工程有 11 处调用（HOME 键、quickswitch、TouchInteractionService 等）全部汇聚到 `TaskUtils.closeSystemWindowsAsync` 这一个方法。

**修复**：在唯一汇聚点包 `catch (SecurityException)` 跳过——仅损失"启动前自动收起通知面板"的非必要行为，其余 11 处调用场景一并修复。

### 3.10 微件周围"黑框"（结论：AOSP 添加微件后自动弹出的调整大小框，已封堵）

**现象**：添加微件后，微件外围出现一圈黑色描边框；只在"第一次添加"时出现，"从拖动/编辑状态回到正常桌面的瞬间"浮现，点击屏幕空白处一下就消失；HyperOS 系统桌面无此现象。

**排查过程（四轮假设，三轮被证伪——本轮排查最大的教训）**：

1. **假设 A：微件自绘** → 隐藏微件子树中最可疑的背景 ImageView（`background_Imageview`，二分实验：清空微件全部背景），黑边仍在 → 证伪
2. **假设 B：拖拽轮廓残留**（CellLayout Canvas 绘制，uiautomator 不可见）→ OutlineTrace 日志插桩（visualizeDropLocation/onDragExit/onDrop/ReorderAlarm 四点埋点）证实拖拽收尾时序完全干净；且门控修复（无活跃拖拽目标时跳过绘制轮廓）后现象仍在 → 证伪
3. **假设 A'：微件自绘内容**（连续截图时间线"无桌面事件黑边自现"）→ 后续对照实验表明时间线误读，且隐藏 ImageView 后黑边依旧 → 证伪
4. **最终结论：微件调整大小框（AppWidgetResizeFrame）**——AOSP 桌面在添加微件后自动弹出调整框（圆角描边+贴合微件边界，视觉上与放置轮廓无法区分）。它与"点击空白消失"（AbstractFloatingView 点击外部关闭）、"只在首次添加出现"（仅 add 流程自动弹出）、"编辑状态回到正常桌面的瞬间出现"（Launcher 专门等 SPRING_LOADED→NORMAL 过渡完成才显示）完全吻合。MIUI 桌面添加微件后不弹此框，故对比实验显示"系统桌面没黑边"。排查绕了四轮的客观原因：该框显示在 DragLayer 浮层，不在微件子树内，历次 uiautomator 抓取均未覆盖；且现象与拖拽轮廓在视觉上完全同构

**修复**（两处实证调用点封堵，长按微件→调整大小功能保留）：

- [Launcher.java `completeAddAppWidget`](../src/com/android/launcher3/Launcher.java)：微件绑定完成入桌后不再调用 `showWidgetResizeFrame`——**免配置微件首添弹框的来源**（不检查 resizeMode、无条件显示）
- [Launcher.java 配置完成分支](../src/com/android/launcher3/Launcher.java)：配置类微件（有设置向导）保存后 `PendingAppWidgetHostView` 重膨胀为真身，随即对新视图调 `showWidgetResizeFrame`——**"点击保存后伴随黑边"的来源**
- Workspace 的 `getWidgetResizeFrameRunnable`（drop/拖拽取消路径）曾两度怀疑、两度排除：有 `resizeMode != RESIZE_NONE` 前置条件且从未实测弹框，**保持 AOSP 原样**——教训：封堵后现象仍在的路径就不是元凶，不要留投机防御代码

```
弹框链路（实锤）：
  免配置微件：drop → 绑定完成 → Launcher.completeAddAppWidget → addInScreen → 弹框
  配置类微件：drop → 配置向导 → 点击保存 → completeRestoreAppWidget 重膨胀 → 弹框
  两处最终都汇入 AppWidgetResizeFrame.showForWidget → DragLayer 浮层描边框
  点击空白 → AbstractFloatingView 点击外部关闭 → 框消失
```

**方法论沉淀**：① Canvas 绘制与浮层都不在 uiautomator 微件子树里，层级抓取必须覆盖全窗口（含 DragLayer）；② "与拖拽轮廓视觉同构"的元素要靠**行为特征**区分（出现时机、消失条件、出现流程），而非外观；③ 多路径特性（同一效果两个入口）修一处不等于修复，要枚举所有调用点。

**遗留防线**：CellLayout 的 mDragCell 门控曾在假设 B 阶段加入，最终证伪后已还原为 AOSP 原始代码。

---

## 四、构建、安装、设为默认

```bash
# 构建（aospWithQuickstep 变体，产物重命名为 SdLauncher3.apk）
.\gradlew.bat assembleAospWithQuickstepDebug -x lint

# 安装
adb install -r build\outputs\apk\aospWithQuickstep\debug\SdLauncher3.apk

# 设为默认桌面（HyperOS 未封堵此命令）
adb shell cmd package set-home-activity com.android.launcher3/com.android.launcher3.uioverrides.QuickstepLauncher

# 验证
adb shell dumpsys window | findstr mCurrentFocus
adb logcat -b crash
```

---

## 五、图标形状说明

### 5.1 图标形状的渲染机制

- 应用提供自适应图标（AdaptiveIcon，前景+背景两层），**裁剪形状由蒙版（mask）决定**
- 默认蒙版 = 系统框架资源 `config_icon_mask`（AdaptiveIconDrawable 自带）。HyperOS 的这个值圆角很小，所以 AOSP 桌面上图标观感"偏直角"；MIUI 桌面则是自己画蒙版，所以显得圆润
- 本代码树内置了完整的形状自定义机制：`ThemeManager`（形状状态中心）→ `ShapeDelegate`（形状实现：圆形/圆角矩形/任意路径）→ `LauncherIcons.getShapePath()`（图标渲染时强制套用），且 `FeatureFlagsImpl.enableLauncherIconShapes()` 已为 true
- 之前图标仍是直角的原因：[ThemeManager.parseIconState](../src/com/android/launcher3/graphics/ThemeManager.kt) 在用户未选择形状时回退到系统 `config_icon_mask`

### 5.2 本次修改（小米风格超椭圆）

在 `ThemeManager` 中新增 `DEFAULT_ICON_SHAPE`（n=4 超椭圆 path，坐标 [0,100]），`parseIconState` 在 `icon_shape_model` 偏好为空时默认使用它，替代系统蒙版：

- 生效范围：桌面/抽屉/文件夹里所有自适应图标 + 文件夹预览（`LauncherIcons.getShapePath` / `drawAdaptiveIcon` / `folderShape`）
- 图标缓存自动失效：`IconState.toUniqueId()` 包含 `iconMask.hashCode()`，蒙版变化后 `LauncherIconProvider.mSystemState` 变化，缓存自动重建
- **可切换**：形状系统仍尊重用户选择——把 `icon_shape_model` 偏好设为 `ShapesProvider` 里的 key（`circle`/`square`/`arch` 等）即可换回其他形状；删除该偏好则回到默认超椭圆
- 若想调整圆润程度：修改 `ThemeManager.DEFAULT_ICON_SHAPE` 的 pathString（重新生成超椭圆折线即可，n 值越大越方，一般 3.5~5）

---

## 六、已知限制（普通应用身份的固有边界，非 bug）

| 功能 | 状态 | 原因 |
|---|---|---|
| 主屏/抽屉/文件夹/小组件 | ✅ 正常 | — |
| 上滑手势回桌面 | ✅ 正常 | HyperOS SystemUI 发 HOME intent |
| 最近任务 | 显示 HyperOS 自家多任务界面 | 多任务由 HyperOS SystemUI 接管；Launcher3 的 recents 集成需要 `STATUS_BAR_SERVICE` 等系统权限 |
| 远程转场动画（快开快切） | 使用系统默认转场 | `CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS` 无法获取 |
| Contextual Search 等Pixel 特性 | 自动禁用 | 依赖 Pixel 框架资源 |

**若想解锁完整系统桌面能力**：需解锁 BootLoader 并将 APK 以 privileged 应用方式放入 `/system/priv-app/`（配 privapp-permissions 白名单），或使用设备平台密钥签名。这属于刷机范畴，与本文档的"免 root 运行"是两条路线。

---

## 七、HyperOS 上 adb 的能力边界（实测）

| 命令 | 结果 |
|---|---|
| `adb install` / `cmd package set-home-activity` | ✅ 可用 |
| `settings put global ...` | ❌ shell 无 `WRITE_SECURE_SETTINGS` |
| `device_config put ...` | ❌ 同上被封堵 |
| `input keyevent` / `input tap` | ❌ shell 无 `INJECT_EVENTS`（需开发者选项中开启"USB 调试（安全设置）"） |
| `logcat` / `dumpsys` / `screencap` | ✅ 可用 |

