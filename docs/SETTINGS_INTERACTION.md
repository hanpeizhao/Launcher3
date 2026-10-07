# 设置 ↔ 桌面：系统设置如何控制 Launcher3

> 源码基于当前 hpz 分支（Android 16 版 Launcher3），所有引用均带文件行号锚点。

## 1. 一句话原理

设置 app 和桌面是**两个独立进程**，它们从不直接调用彼此的代码。所有"控制"都通过
**系统级中介**完成：设置 app 写入 SystemServer 的存储（SettingsProvider / RoleManager），
桌面通过**观察者/回调**收到变化再自我调整。数据只有一个权威副本，所以两端永远一致。

```
设置 app（com.android.settings）          Launcher3（本工程）
        │  写                                     │  读/监听
        ▼                                        ▼
┌────────────────── SystemServer 中介层 ───────────────────┐
│  SettingsProvider（settings.db：Secure/System/Global）    │
│  RoleManager（默认桌面角色）   WindowManager/Configuration │
└──────────────────────────────────────────────────────────┘
```

## 2. 系统 → 桌面：五条下行通道

### 2.1 默认桌面（角色机制）

- 声明入口：[AndroidManifest.xml](../AndroidManifest.xml#L62-L65)——主 Activity 携带
  `android.intent.action.MAIN` + `android.intent.category.HOME`。这是"我是候选桌面"的
  唯一声明，**没有任何代码参与**。
- 选择动作：设置 → 应用 → 默认应用 → 桌面。设置 app 调 `RoleManager.createRequestRoleIntent(ROLE_HOME)`，
  系统把选中的包记入 `RoleManager`（`role_com.android.home`），按下 Home 键时
  `ActivityTaskManager` 解析 HOME category，命中该包。
- 桌面侧的主动请求：首次启动时申请成为默认（`HomeRoleRequest` 流程），用户拒绝后不再骚扰。

### 2.2 系统设置项观察（ContentObserver + 缓存）

[SettingsCache.java](../src/com/android/launcher3/util/SettingsCache.java) 是桌面的
"系统设置雷达"——把关心的设置项 URI 注册成 ContentObserver，变化即时回调：

| URI | 行号 | 控制的功能 |
|---|---|---|
| `Settings.Secure/notification_badging` | [L64-L65](../src/com/android/launcher3/util/SettingsCache.java#L64-L65) | 图标通知角标开关 |
| `Settings.Secure/hide_privatespace_entry_point` | [L72-L73](../src/com/android/launcher3/util/SettingsCache.java#L72-L73) | 私密空间入口隐藏 |
| `Settings.System/ACCELEROMETER_ROTATION` | [L74-L75](../src/com/android/launcher3/util/SettingsCache.java#L74-L75) | 自动旋转 |
| `Settings.System/touchpad_natural_scrolling` | [L77](../src/com/android/launcher3/util/SettingsCache.java#L77) | 触控板手势方向 |

任何模块需要响应某设置项：`SettingsCache.INSTANCE.get(context).register(uri, listener)`，
读取走缓存（`getValue(uri)`），无需各自开 Observer。设置 app 改了值 → SettingsProvider
发 notifyChange → 缓存失效 + 监听者回调，全程跨进程由 binder 完成。

### 2.3 广播通道

`AndroidManifest.xml` 里静态注册的系统广播：时间/时区变化（重排日历图标）、
`LOCALE_CHANGED`（重建图标缓存与字符串）、`SCREEN_ON/OFF`、
`CONFIGURATION_CHANGED` 等。设置 app 改语言 → 系统发广播 → 桌面重建。

### 2.4 系统配置直通（Configuration）

深色模式、字体大小、显示大小这三类不走广播，走 `onConfigurationChanged` +
主题系统：设置 app 改值 → `WindowManager.updateConfiguration` → 桌面收到新
`Configuration`，[ThemeManager.kt](../src/com/android/launcher3/graphics/ThemeManager.kt)
按 `uiMode` 切深浅色，`DeviceProfile` 按密度重算网格。

### 2.5 图标形状（三层取值）

[ThemeManager.iconShape](../src/com/android/launcher3/graphics/ThemeManager.kt#L170)
的取值顺序：用户偏好 `icon_shape_model`（桌面自身存储）→ 系统蒙版（厂商 overlay）
→ 默认超椭圆（我们为 HyperOS 实机加的兜底）。系统侧形状由 RRO（运行时资源覆盖）
下发，桌面只在启动时读一次。

## 3. 桌面 → 自身 / 系统：上行通道

### 3.1 桌面自己的设置页

[SettingsActivity.java](../src/com/android/launcher3/settings/SettingsActivity.java)
（长按桌面空白 → 设置）：加载 launcher:xml 偏好定义，写入 **launcher 私有的
SharedPreferences**（`/data/data/com.android.launcher3/shared_prefs/`）。

关键声明：[AndroidManifest-common.xml#L148](../AndroidManifest-common.xml#L148)
注册了 `android.intent.action.APPLICATION_PREFERENCES`——这是 Android 的
"本应用自带设置页"标准契约。系统设置 app 遇到"应用设置/默认应用旁的齿轮"等入口时，
会向**当前默认桌面**解析这个 intent 并直接启动对方的设置 Activity。
fragment 指向由 [config.xml#L28](../res/values/config.xml#L28) 的
`settings_fragment_name` 配置（`LauncherSettingsFragment`）。

### 3.2 反向写系统设置

个别桌面开关实际写的是系统存储——例如通知角标开关最终落
`Settings.Secure.notification_badging`，写回去后设置 app 的界面（同样观察这个 URI）
也会跟着变。**双向控制的本质就是共享同一行数据库**，谁写谁触发通知。

### 3.3 桌面提供的服务面

微件宿主（`AppWidgetHost`）、壁纸回调、`SystemUiProxy`（binder 到系统 UI）
等是系统"伸进"桌面的回调通道，设置 app 间接通过它们影响桌面行为。

## 4. 持久化状态收敛表

| 存储 | 属主 | 举例 | 谁写 | 谁读 |
|---|---|---|---|---|
| SettingsProvider（Secure/System/Global） | 系统进程 | notification_badging、旋转 | 设置 app、系统 | 桌面（SettingsCache）、设置 app |
| RoleManager | 系统进程 | 默认桌面 | 设置 app、桌面请求 | ActivityTaskManager（Home 解析） |
| launcher SharedPreferences | 桌面进程 | icon_shape_model、网格偏好 | 桌面设置页 | 桌面 |
| DeviceConfig / FeatureFlags | 系统进程 | 桌面 flag | 服务端/实验系统 | 桌面启动时 |

## 5. 排查技巧

```bash
adb shell settings get secure notification_badging   # 查系统设置当前值
adb shell settings put secure notification_badging 0 # 模拟设置 app 修改
adb shell cmd role holders android.app.role.HOME     # 当前默认桌面
adb shell dumpsys role                               # 角色详情
```

改完 `settings put` 后无需重启桌面——ContentObserver 应立即触发（验证 2.2 链路的快捷方法）。

## 6. HyperOS 实机备注

MIUI 的"设置 → 桌面"页面控制的是 **MIUI 自己的桌面**（私有设置存储），不会作用于本工程。
本工程能被系统控制的范围 = 上文标准 Android 通道：默认桌面角色、深色/字号、
`SettingsCache` 覆盖的设置项、语言广播。图标形状的系统 overlay 在 HyperOS 上
取值异常，已由 ThemeManager 兜底（见 [RUN_ON_RETAIL_DEVICE.md §5](RUN_ON_RETAIL_DEVICE.md)）。

## 7. 为什么不同桌面的设置选项不同

因为**系统设置 app 不拥有"桌面设置"的内容，它只是个转发器**：

1. 每个桌面 app 用 `ACTION_APPLICATION_PREFERENCES` intent-filter 自报家门
   （本工程的声明在 [AndroidManifest-common.xml#L148](../AndroidManifest-common.xml#L148)）
2. 设置 app 的"桌面设置"入口向**当前默认桌面**（RoleManager 持有者）解析该 intent，
   启动的是对方自己的设置 Activity——我们的是 `SettingsActivity`（内容 =
   launcher:xml 偏好），MIUI 桌面是 MIUI 的偏好页，Nova 是 Nova 的
3. 换默认桌面 → 解析到不同组件 → 选项列表完全不同。选项本身存进各自的
   私有 SharedPreferences，互不相通

HyperOS 特例：小米在 MIUI 设置 app 里硬编码了"设置 → 桌面"私有页面，
不走上面的转发机制，所以那一页永远显示 MIUI 的选项、只控制 MIUI 桌面；
第三方桌面只能出现在"设置 → 应用 → 默认应用 → 桌面"的角色选择列表里。

另外注意选项来源的层级：通知角标、深色模式这类**系统级设置**所有桌面都跟随
（走 §2.2/§2.4 通道）；网格布局、图标形状、手势这类**桌面私有设置**只有
提供者自己能改——这就是为什么某些开关"换桌面后位置不同甚至消失"。
