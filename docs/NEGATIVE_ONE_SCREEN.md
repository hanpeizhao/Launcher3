# 负一屏实施与排障记录（跨进程覆盖层方案）

> 目标：主屏右滑拉出"负一屏"，全屏显示由独立应用渲染的信息流界面（观感对齐 MIUI 智能助理）。
> 最终架构：**Launcher 只负责手势与动效，界面内容全部由独立 companion 应用渲染**，
> 经 `SurfaceControlViewHost` 跨进程嵌入。方案分析见
> [NEGATIVE_ONE_SCREEN_ANALYSIS.md](NEGATIVE_ONE_SCREEN_ANALYSIS.md)。

## 1. 架构

```
com.android.launcher3（宿主）                com.sdlaucher.personalassistant（内容方）
┌─────────────────────────────┐              ┌──────────────────────────────────┐
│ NegativeScreenController     │   bindService │ PaService（前台服务）             │
│  ├ DragLayer 覆盖层 SurfaceView│ ◄──────────► │  ├ createSurfacePackage()        │
│  ├ 手势拦截（拉出/退出/跟手）    │  IPaService  │  │  └ SurfaceControlViewHost     │
│  ├ punch-through 显示远端画面  │  IPaCallback │  └ PaHomeView（信息流 UI）        │
│  └ 指示器虚拟页联动            │ ◄─────────── │      └ 横向滑动回调 IPaCallback   │
└─────────────────────────────┘   onSwipeHome └──────────────────────────────────┘
```

分工边界：
- **横向手势** = 宿主处理（拉出/退出覆盖层，跟手）
- **纵向手势** = companion 处理（卡片流滚动）
- **渲染** = companion 进程（崩溃不连坐桌面）；**交互模型** = 覆盖式（主屏静止，负一屏整层推入/滑出）

## 2. 构建与启动命令（Windows / PowerShell）

```powershell
# 一次构建产出两个 APK（SdLauncher3.apk + PaAssistant.apk）
.\gradlew.bat assembleAospWithQuickstepDebug --console=plain -x lint
# 产物：
#   build\outputs\apk\aospWithQuickstep\debug\SdLauncher3.apk
#   personalassistant\build\outputs\apk\debug\PaAssistant.apk

# 安装（-r 覆盖安装；companion 更新签名不一致时先 uninstall）
adb install -r build\outputs\apk\aospWithQuickstep\debug\SdLauncher3.apk
adb install -r personalassistant\build\outputs\apk\debug\PaAssistant.apk

# 重启桌面（companion 由桌面 bindService 拉起，无需单独启动）
adb shell am force-stop com.android.launcher3
adb shell am start -n com.android.launcher3/com.android.launcher3.uioverrides.QuickstepLauncher

# HyperOS 允许 companion 后台拉起（安全中心自启动页手动开启亦可）
adb shell appops set com.sdlaucher.personalassistant AUTO_START allow

# 功能开关：长按桌面空白 → 设置 → 负一屏
```

## 3. 验证与排查命令

```powershell
# 判断"崩溃重启"还是"状态异常"：操作前后 pid 不变 = 无重启
adb shell pidof com.android.launcher3            # 宿主
adb shell pidof com.sdlaucher.personalassistant  # companion

# 单进程时间线过滤（避免全量 logcat 被系统日志淹没）
adb logcat -d -v time --pid=<pid>
# 关键 TAG：NegativeScreenController（宿主）、PaService / PaHomeView（companion）

# 截图取证（adb input 触摸注入被 HyperOS 禁用，手势验证必须手动）
adb shell screencap -p /sdcard/shot.png; adb pull /sdcard/shot.png .
```

## 4. 问题与修复清单

实施过程先后经历三种交互模型（页面内嵌页 → 推入转场 → 覆盖层），以下按问题归纳。

### 4.1 GONE 下嵌入面永远绑不上（黑屏无内容）

- **现象**：覆盖层显示后一直黑屏，companion 端从未收到 bindSurface 调用。
- **排查**：双端日志对照——宿主 `NegativeScreenController` 无绑定发起日志；检查
  `requestSurfaceIfNeeded()` 的守卫条件发现 `getWidth() == 0`。
- **根因**：SurfaceView 初始 `GONE`，**不参与布局则宽高恒为 0**，绑定条件永不满足。
- **修复**：初始 `INVISIBLE`（参与布局、可拿宽高）。
- **教训**：`SurfaceView` 的 surface 生命周期与"可见性/参与布局"强相关，
  `GONE/INVISIBLE/VISIBLE` 三态在这里语义完全不同。

### 4.2 收起用 INVISIBLE 导致卡顿与黑帧

- **现象**：主屏 ↔ 负一屏来回切换卡顿，再显瞬间黑帧。
- **根因**：`INVISIBLE` 会**销毁 surface**，再显需 companion 重建 `SurfaceControlViewHost`
  并重新提交首帧，期间 hole 区域为黑。
- **修复**：收起态改为**平移到屏外**（`translationX = -screenWidth`），surface 常驻渲染，
  显示只是纯平移。Android 上 `SurfaceView` 反复可见性切换是常见的掉帧源。

### 4.3 覆盖层滑动中与页面动画"打架"（混合错位）

- **现象**：负一屏挂回 Workspace 页面内时，滑动过程中页面位移与 SurfaceView 位移两个
  动画叠加，出现内容错位、观感割裂；脱离页面后又失去翻页跟手。
- **结论**：嵌页模型下"页面位移"与"surface 跟手"天然耦合，调不平。
- **修复**：架构改为**覆盖层**——主屏不插负一屏页，翻页行为完全不变；
  覆盖层独立于 Workspace 挂在 DragLayer 顶层，主屏静止、负一屏整层推入/滑出。
  （中途尝试的"停稳后从右推入"转场模型被覆盖式取代：用户要求主屏完全不动。）

### 4.4 拉出手势误拦所有页面的右滑

- **现象**：任何一页右滑都拉出负一屏，正常翻页失效。
- **根因**：DragLayer 拦截手势时未判断起点页码。
- **修复**：仅当 `Workspace` 停在**第一页**（`getCurrentPage() == 0`）时拦截右滑。

### 4.5 覆盖层内滑不回去（退出判定）

- **现象**：能进负一屏，慢滑永远退不出。
- **根因**：最初退出依赖 companion 手势回调（fling 速度阈值 1200），慢拖不满足；
  且横向手势先被 companion 内容区消费。
- **修复**：**横向手势统一由宿主处理**（跟手平移 + 半屏判定/速度甩动均可退出），
  companion 只保留纵向滚动。边界更清晰：横向 = 宿主，纵向 = 内容方。

### 4.6 方向反了

- **现象**：拉出方向与手指运动方向相反。
- **根因**：`translationX` 符号约定搞反（覆盖层从右缘推入写成了左缘，或反之）。
- **修复**：覆盖式模型约定：`translationX = -offset`，offset 从 0（全开）到
  `-screenWidth`（收起在屏外左缘），拉出即 offset 由 -w → 0。

### 4.7 指示器没有"原生灵动"

- **用户判定**：手绘负一屏点 + 手动平移动画不够好，应在**页数计算处多传一个值**。
- **重构**：`PageIndicatorDots` 引入视觉页数 `mVisualPages = mNumPages + 1`，
  负一屏成为第 0 个 marker；Workspace 滚动映射整体 +1（`visualPage()`）；
  覆盖进度经 `setNegativeProgress()` 直接写原生位置体系。
  胶囊延伸、Overshoot 回弹全部走原生代码，删除全部 special-case 绘制分支。
- **教训**：扩展原生 UI 时优先"喂给原生体系一个新输入"，而不是在绘制函数里加分支。

### 4.8 首次进入负一屏桌面内容被"清空"

- **现象**：第一次拉出负一屏时主页所有内容消失，片刻后恢复。
- **排查**：`pidof` 前后一致（非崩溃重启）；单进程日志无 LoaderTask 重跑、无 recreate
  （非数据重绑）。排除桌面内部状态问题后锁定合成时序。
- **根因**：SurfaceView 首次 `INVISIBLE → VISIBLE` 的瞬间三件事并发——
  punch-through 打孔、companion 首帧渲染、HyperOS 合成器 z 序重排；
  间隙期桌面窗口被挖空 = "内容清空"。
- **修复**：**预热**——`ensureOverlay()` 创建 SurfaceView 时先平移到屏外再 `VISIBLE`，
  surface 建立与首帧提交都发生在屏外（用户无感），首次拉出只剩纯平移。

### 4.9 companion 被 HyperOS 后台管控拦截

- **现象**：重启桌面后服务绑定不上/嵌入面拿不到。
- **根因**：HyperOS 对第三方应用的后台拉起管控。
- **修复**：`adb shell appops set com.sdlaucher.personalassistant AUTO_START allow`，
  或安全中心 → 自启动管理手动开启。

### 4.10 静态壁纸模糊不可用（权限降级）

- **现象**：companion 取壁纸做模糊背景失败。
- **根因**：Android 13+ `WallpaperManager.getDrawable()` 需要
  `READ_EXTERNAL_STORAGE`，HyperOS 上对第三方拒绝。
- **修复**：降级用 `WallpaperColors`（无需权限）取壁纸主色做**色调渐变背景**，
  深色卡片流在主色调渐变上观感接近原生负一屏；动态壁纸场景本来就是取色路径。

## 5. 已知边界

- RTL 布局不启用负一屏虚拟页（指示器与手势均按 LTR 设计）
- 概览/桌面编辑态下覆盖层自动隐藏，退出后恢复
- companion 版本更新后需同时重装两端（AIDL 与 signature 权限绑定包名签名）
- 双进程内存开销略增（一个整屏 surface buffer 常驻）

## 6. 相关提交

| 提交 | 内容 |
|---|---|
| 658906a2 | companion 应用（个人助理）与跨进程 AIDL 契约 |
| 21af2e90 | 桌面宿主端负一屏覆盖层（手势/预热/沉浸式/开关） |
| 0820c152 | 页指示器接入负一屏虚拟页（原生动画体系） |
