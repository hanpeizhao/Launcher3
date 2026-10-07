# 负一屏实现方案分析（Launcher3 @ Android 16）

> 目标：在桌面最左页提供"负一屏"（资讯/卡片/快捷功能），右滑首屏即达，观感对齐 MIUI 智能助理。

## 0. 现状盘点

- AOSP 老版 Launcher3 的负一屏机制（`Workspace.setCustomContentCallbacks`，Google Now 页）在本树已**删除**（grep 验证），方案 A 只能从旧源码移植
- MIUI 的负一屏 = `com.miui.personalassistant` 独立应用 + MIUI 桌面进程内深度集成（闭源 fork），不可复用
- Launcher3 现有可复用基建：`LauncherAppWidgetHost`（微件宿主）、Workspace 分页机制、`LauncherPrefs` 偏好

## 1. 四种方案对比

| 方案 | 架构 | 优点 | 缺点 | 评级 |
|---|---|---|---|---|
| **B：微件方案** | 自建 companion 应用发布全尺寸微件，桌面自动布到第 0 页 | 标准机制零黑科技；内容跨进程隔离，崩溃不连坐；RemoteViews 即可做卡片流 | 交互弱于原生页；用户可能删掉微件需恢复逻辑；滑动衔接略异于 MIUI | **推荐起步** |
| **C：SurfaceControlViewHost 跨进程嵌入** | 桌面 bind 服务，把对方应用的视图树经 `SurfaceControlViewHost` 嵌入第 0 页 | 交互完整（真 View）；最接近 MIUI 架构 | 触摸/焦点/性能边角多；耦合服务生命周期；实现复杂度高 | 二期演进 |
| **A：移植 CustomContentCallbacks** | 恢复 AOSP 老机制，第 0 页放进程内自定义 View | 页面行为原生（指示器/视差/回调齐全） | 老代码与 Android 16 树冲突大；内容跑在桌面进程，无隔离 | 备选 |
| **D：进程内自绘页面** | 负一屏 UI 直接写在桌面进程 | 完全可控 | 膨胀桌面进程；更新内容需自建数据通道 | 不推荐 |

## 2. 推荐路线：B 起步 → C 演进

### 阶段一（方案 B）实施步骤

1. **内容提供方**：新建 companion 模块（或独立 APK）`PersonalAssistant`，发布一个
   4×4 `AppWidgetProvider`（RemoteViews 卡片流：时间/天气/新闻/快捷开关）
2. **桌面侧自动布局**（核心改动点）：
   - [Workspace.java](../src/com/android/launcher3/Workspace.java)：加载完成回调里检测
     `prefs: negative_screen_enabled` 且第 0 页无助理微件 → `AppWidgetHost.allocateAppWidgetId()`
     + `bindAppWidgetIdIfAllowed`（自家包免授权）→ `addInScreen` 到新页
   - 删除恢复：`onProvidersChanged`/`onWidgetsChanged` 时检测被删则重建（对齐 MIUI "负一屏开关"体验）
3. **页面特化**（让它像 -1 屏而不是普通页）：
   - 该页禁用 drop 目标（不能把图标拖进去）——`CellLayout` 加"special page"标记
   - 不进最近任务概览的网格、页指示器独立处理
   - [LauncherPrefs](../src/com/android/launcher3/LauncherPrefs.kt) 加开关
     `negative_screen_enabled`，挂进 launcher_preferences.xml（套用"锁定布局"同款三步法）
4. **滑动衔接**：Workspace 从第 1 页继续左滑自然进入第 0 页（分页机制天然支持），可加
   过渡动画微调

### 阶段二（方案 C，可选）

把第 0 页的微件替换为 `SurfaceControlViewHost` 嵌入面：companion 应用提供
`getSurfacePackage()`，桌面把它贴到该页容器上——获得原生交互（滚动/点击/动效），
数据与渲染仍隔离在 companion 进程。改动集中在第 0 页容器的替换，页面机制复用阶段一。

## 3. 主要风险

- **微件交互上限**：RemoteViews 不支持复杂手势，卡片流可以但"负一屏式"沉浸体验有限
  ——这正是要演进到方案 C 的原因
- **自动布局时机**：需处理首启/数据恢复/网格变化三种时机，避免和默认工作区布局
  （default_workspace）加载竞态
- **概览与指示器**：Android 16 树的 overview 逻辑改动较多，特化第 0 页要覆盖
  quickstep 分支（有手势桌面时 -1 屏不参与概览）

## 4. 与既有改造的衔接

- 开关接入现有 [launcher_preferences.xml](../res/xml/launcher_preferences.xml)
- 图标形状/主题等改造无冲突；注意 [RUN_ON_RETAIL_DEVICE.md](RUN_ON_RETAIL_DEVICE.md)
  中 HyperOS 权限限制不涉及本功能（无新增系统权限需求）
