# 息屏听剧（ScreenOffDrama）

面向 **Android 16**（兼容 Android 12+）的应用。

**第一阶段（已完成）**：在 YouTube / 哔哩哔哩播放视频时，点击悬浮球让屏幕变全黑、声音继续；按电源键退出息屏回到原视频。

**第二阶段（已完成）**：集成 YouTube 广告自动跳过（无障碍服务），息屏听剧时同样生效，支持连续广告逐段自动跳过。

---

## 一、核心流程

```
打开 YouTube / 哔哩哔哩播放视频
        │
        ▼
点击悬浮球 ────────────► 屏幕全黑（纯黑悬浮窗），声音继续，视频不暂停
        │
        ▼
按电源键 ──────────────► 黑屏消失，回到原来的视频画面，悬浮球重新出现

（播放 YouTube 时）────► 无障碍服务自动检测“跳过广告”按钮并点击，连续广告逐段跳过
```

## 二、已实现功能

| 功能 | 实现说明 |
| --- | --- |
| 前台服务 | `ScreenOffService`，类型 `mediaPlayback`；声明 `FOREGROUND_SERVICE_MEDIA_PLAYBACK` 权限，`startForeground` 传入 `FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK` |
| 常驻通知 | 内容「息屏听剧运行中」，提供「停止服务」按钮（`PendingIntent` 触发 `ACTION_STOP`） |
| 纯黑悬浮窗 | `BlackOverlayManager`：`TYPE_APPLICATION_OVERLAY` + `FLAG_NOT_FOCUSABLE`，不抢焦点 → 底层视频 App 不会 `onPause`；取全屏物理尺寸 + `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS`，完整覆盖状态栏/导航栏/挖孔区域 |
| 悬浮球 | `FloatingBallManager`：可拖动，松手吸附屏幕边缘；点击进入息屏 |
| 电源键退出 | 监听 `ACTION_SCREEN_OFF`：息屏模式下按电源键 → 移除黑窗、恢复悬浮球；平时不干扰锁屏 |
| 默认白名单 | `Whitelist` 写死 YouTube / 哔哩哔哩（本阶段仅常量，不做拦截） |
| 权限引导 | 悬浮窗权限、忽略电池优化、通知权限（Android 13+），文案说明用途 |
| **广告自动跳过** | `AdSkipService`（无障碍服务）：监听 YouTube 窗口变化，检测并自动点击“跳过广告”；多策略 L1 文本 / L2 资源ID / L3 父节点遍历；防重复标记 1s；连续广告天然支持 |
| **广告跳过开关** | 主界面独立开关（SharedPreferences `ad_skip_enabled`），与息屏听剧服务互不影响；服务保持运行、开关关闭时仅不执行检测 |

## 三、代码结构

```
app/src/main/java/com/screenoffdrama/
├── MainActivity.kt                  # 主界面：权限引导 + 息屏服务启停 + 广告跳过开关/无障碍引导
├── Whitelist.kt                     # 写死白名单（YouTube / 哔哩哔哩）
├── service/
│   ├── ScreenOffService.kt          # 息屏听剧前台服务：通知、悬浮球/黑窗管理、ACTION_SCREEN_OFF 监听
│   └── AdSkipService.kt             # YouTube 广告自动跳过（无障碍服务）
└── overlay/
    ├── FloatingBallManager.kt       # 可拖动悬浮球（拖动 + 吸附 + 点击回调）
    └── BlackOverlayManager.kt       # 全屏纯黑覆盖层（不抢焦点）

app/src/main/res/xml/accessibility_service_config.xml   # 无障碍服务配置（仅监听 YouTube 包名）
```

两个 Service 相互独立、并行运行：`ScreenOffService` 管理屏幕覆盖，`AdSkipService` 管理广告检测与点击；息屏听剧时广告跳过依然有效。

## 四、技术要点（针对 Android 16）

1. **前台服务类型**：Android 14+ 强制要求声明类型并申请对应权限。本项目：
   - Manifest 声明 `android:foregroundServiceType="mediaPlayback"`
   - Manifest 声明 `FOREGROUND_SERVICE_MEDIA_PLAYBACK` 权限
   - `ServiceCompat.startForeground(..., FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)`
2. **不被杀**：前台服务（媒体类型高优先级）+ `START_STICKY` + 忽略电池优化（引导用户设置）+ 不在 `onTaskRemoved` 中自杀。
3. **不抢焦点**：悬浮球与黑窗均带 `FLAG_NOT_FOCUSABLE`，保证视频 App 不进入 `onPause`。
4. **全屏黑窗**：`TYPE_APPLICATION_OVERLAY` + 显式设置全屏物理尺寸（`WindowManager.maximumWindowMetrics.bounds`）+ `FLAG_LAYOUT_IN_SCREEN` / `FLAG_LAYOUT_NO_LIMITS` + `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS`，覆盖到状态栏、导航栏与挖孔区域。
5. **edge-to-edge**：Android 15+ 强制，主界面根布局 `fitsSystemWindows="true"` 处理系统栏内边距。
6. **无障碍服务（广告跳过）**：
   - Manifest 声明 `<service android:permission="BIND_ACCESSIBILITY_SERVICE" android:exported="true">` + `meta-data` 指向 `res/xml/accessibility_service_config.xml`
   - 仅监听 `com.google.android.youtube` 包名（最小权限原则）
   - 事件：`TYPE_WINDOW_STATE_CHANGED` / `TYPE_WINDOW_CONTENT_CHANGED`，`canRetrieveWindowContent="true"`，`flagRetrieveInteractiveWindows`
   - 由系统绑定运行，不受后台启动限制影响

## 五、广告跳过：检测与防重复

- **L1 文本匹配**：`跳过广告 / 跳过 / Skip Ad / Skip ads / Skip`；带倒计时的按钮（如 `Skip Ad 5`）同样命中。
- **L2 资源ID匹配**：`skip_ad_button`、`skip_ad_button_view`、`ytv_skip_ad`、`skip_ad`（优先于文本，误判最少）。
- **L3 父节点遍历**：命中节点不可点击时，向上最多 12 层找可点击祖先再点击；倒计时中按钮未启用则跳过，等下一个事件再试。
- **防重复**：点击成功后 `isSkipping=true`，1 秒后复位，避免同一广告被反复点击。
- **连续广告**：第一段跳过 → YouTube 刷新加载第二段 → 触发新 `TYPE_WINDOW_CONTENT_CHANGED` → 再次检测点击，天然支持。

## 六、构建

要求：JDK 17、Android SDK（platform 36）。

方式一：命令行（已含 Gradle Wrapper，国内镜像源）

```bat
gradlew.bat :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`（已签名，可直接安装测试）。

方式二：Android Studio 打开项目根目录，等待 Sync 后 Run。

> 若换环境构建，需确认 `local.properties` 中 `sdk.dir` 指向本机 SDK 路径（Android Studio 会自动生成）。

## 七、使用步骤

1. 安装 APK，打开「息屏听剧」。
2. 按引导依次开启：悬浮窗权限 → 忽略电池优化 → 通知权限（Android 13+）。权限满足后息屏服务自动启动，屏幕出现悬浮球。
3. 广告跳过（可选）：主界面「YouTube 广告自动跳过」开关默认开启；若显示“未开启”，点「去开启无障碍服务」到系统设置中打开。**Android 13+ 首次需先允许受限设置**：设置 → 应用 → 息屏听剧 → 右上角“⋮”菜单 → 允许受限设置，再回来开启无障碍服务。
4. 打开 YouTube 或哔哩哔哩播放视频。
5. 点击悬浮球 → 屏幕全黑、声音继续；此时 YouTube 广告仍会自动跳过。
6. 按电源键 → 黑屏消失，回到视频画面，悬浮球重新出现。
7. 通知栏可点「停止服务」结束息屏服务；广告跳过与息屏服务相互独立，互不影响。

## 八、已知行为与边界

- **屏幕自然超时**：息屏状态下若屏幕因超时自动熄灭，同样触发 `ACTION_SCREEN_OFF`，会退出息屏（移除黑窗）。这是与「按电源键退出」一致的既定行为。
- **物理熄屏后的音频**：屏幕真正熄灭后，视频 App 是否继续播放取决于其自身后台播放策略（如 YouTube 需 Premium/后台播放，哔哩哔哩视其设置），本应用不干预。
- **黑屏防误触**：黑窗会拦截触摸（不穿透到底层 App），防止误触导致视频暂停；退出只能通过电源键或通知停止服务。
- **广告跳过生效范围**：仅对 `com.google.android.youtube` 生效；只跳过“可跳过”广告，非跳过广告无法干预；跳过按钮倒计时结束后才会被点击（未启用时不点）。
- **无障碍受限设置**：安卓 13+ 对侧载应用开启无障碍有“受限设置”门槛，已提供引导文案；安装时若出现“此应用可能有风险”提示，属无障碍类应用通用提示，选择“仍要安装”即可。
- **国产 ROM**：小米/华为/OPPO/vivo 等系统可能还有「自启动」「后台运行」限制，建议在系统设置中将本应用设为「允许自启动 / 无限制」，进一步防止被杀。
- **服务重启**：进程被系统回收后依赖 `START_STICKY` 由系统重建；此场景不保证 100% 存活（Android 后台机制决定）。
- **Play 商店**：`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 与无障碍服务的用途受 Play 商店政策限制；本应用面向本地侧载安装，故直接使用。若后续上架需补充审核用途说明。

## 九、版本历史

- **v1.1**：新增 YouTube 广告自动跳过（无障碍服务 `AdSkipService`）+ 主界面独立开关 + 无障碍开启引导。
- **v1.0**：第一阶段最小可用版：息屏听剧全流程（前台服务 / 纯黑悬浮窗 / 悬浮球 / 电源键退出 / 权限引导）。
