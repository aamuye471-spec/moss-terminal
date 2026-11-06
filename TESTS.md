# Moss Terminal 测试记录

测试时间：2026-11-06 00:11–00:40 HKT
测试环境：Linux x86_64 虚拟机 + qemu-aarch64-static 7.2.0（模拟目标 CPU）
目标设备：OPPO Reno12 国行（PJV110，Dimensity 8250，aarch64，Android 16）——**未在真机上测过**

## 1. 运行时可行性（核心验证）

| 测试 | 方法 | 结果 |
|---|---|---|
| musl 版 Claude Code 能跑 | qemu-aarch64-static + musl 加载器，`--version` | ✅ `2.1.318 (Claude Code)` |
| `--help` 正常 | 同上 | ✅ 完整 help 输出 |
| 以 root 身份启动 | qemu 下以 uid 0 运行 `claude -p "hello"` | ✅ 启动成功，返回 `Not logged in · Please run /login`（只缺登录） |
| root 门禁 | 同上 | ✅ 默认模式不拦 root；只有 `--dangerously-skip-permissions` 被拦（符合源码 setup.ts:395-414） |
| 显式加载器调用 | `qemu -L musl-root /lib/ld-musl-aarch64.so.1 claude` | ❌ `Not a valid dynamic program`（复现两次；也试过 `--library-path`，一样失败） |
| patchelf 改 INTERP/RUNPATH | patchelf 0.18.0 改为 Android 绝对路径 | ✅ 改完直接跑，不用 `-L`、不用 `LD_LIBRARY_PATH` |
| glibc 版对比 | 官方 glibc 二进制 | ❌ 需要 6 个库（librt、libc.so.6、ld-linux、libpthread、libdl、libm），不如 musl |

结论：musl 直跑是唯一走通的路线。Termux/proot/Debian 根文件系统方案不需要。

## 2. Android 路径端到端验证

在虚拟机上建了和手机上完全相同的目录结构，
`/data/data/com.mossfield.terminal/files/lib/` + `files/bin/`，放进 patchelf 改过的二进制：

| 测试 | 结果 |
|---|---|
| `claude --version`（Android 路径，无 LD_LIBRARY_PATH） | ✅ `2.1.318 (Claude Code)` |
| `claude -p "hello"`（Android 路径） | ✅ `Not logged in · Please run /login` |

## 3. APK 构建

| 步骤 | 工具 | 结果 |
|---|---|---|
| aapt2 compile 资源 | build-tools 34.0.0 | ✅ |
| aapt2 link（R.java + 基础 APK） | 同上 | ✅（`--extra-packages com.termux.view` 生成 Termux 的 R 类） |
| javac 编 Java | JDK 17.0.21 Temurin，`-encoding UTF-8` | ✅（修过两个问题：中文注释编码、缺 `OutputStreamWriter` import） |
| d8 转 dex | build-tools 34.0.0 | ✅（修过一个问题：d8 不接受裸 class 目录，先打 jar；修过 `java` 不在 PATH） |
| 组装 APK | zip | ✅（dex + assets 压缩 + libtermux.so 不压缩存放） |
| zipalign | build-tools 34.0.0 | ✅ |
| apksigner 签名 | build-tools 34.0.0 | ✅ v3 方案验证通过（v2 单独签也通过；v2+v3 同时开时 apksigner 只写 v3——已确认 v3 单独满足 Android 11+ 安装要求） |

构建脚本：`build.sh`（`bash build.sh`，约 45 秒）。不用 Gradle。

## 4. APK 内容验证

| 检查 | 工具 | 结果 |
|---|---|---|
| 包名 / 版本 | `aapt2 dump badging` | ✅ `com.mossfield.terminal`，versionCode 1，versionName 1.0，minSdk 29，targetSdk 34 |
| 权限 | 同上 | ✅ INTERNET、ACCESS_NETWORK_STATE、FOREGROUND_SERVICE、WAKE_LOCK、存储（≤32）、RECEIVE_BOOT_COMPLETED |
| 启动 Activity | 同上 | ✅ `com.mossfield.terminal.MainActivity`，label `Moss Terminal` |
| APK 内容 | `unzip -l` | ✅ classes.dex（166 KB）、assets/claude（321 MB）、assets/ld-musl-aarch64.so.1（723 KB）、lib/arm64-v8a/libtermux.so（9,928 字节，未压缩）、AndroidManifest.xml、resources.arsc，共 14 个文件 |
| APK 大小 | `ls` | ✅ 147,555,436 字节（约 141 MB） |
| 签名验证 | `apksigner verify --verbose` | ✅ Verifies；v3 方案 true |

## 5. APK 内二进制验证（从 APK 解出来跑）

| 检查 | 结果 |
|---|---|
| INTERP 是 Android 路径 | ✅ `/data/data/com.mossfield.terminal/files/lib/ld-musl-aarch64.so.1` |
| `--version` | ✅ `2.1.318 (Claude Code)` |
| `-p "hello"` | ✅ `Not logged in · Please run /login` |
| SHA-256 与源码 assets 一致 | ✅ `3c9743b09f5c4b3b1d7dc0f303f8b8b7fc45f82df0c08debb52e5976e33ec423` |

## 6. RootBridge 黑名单单元测试（安全关键）

JVM 上跑，用 `android.util.Log` 桩。测试文件：`tests/RootBridgeTest.java`。
结果：**39/39 通过，0 失败**。

| 类别 | 用例数 | 结果 |
|---|---|---|
| 必须拦（system 分区） | 20 | 全部拦住：`/system`、`/vendor`、`/product`、`/etc`、`/apex`、`/data/system`、`/data/misc`、remount,rw 到 system/vendor/product/根目录、`dd` 写块设备、`//system`、`/system/../system`、`/system/./etc` 等绕过写法 |
| 必须放行（合法 root 玩法） | 19 | 全部放行：`pm disable/enable`、`dumpsys`、`settings put`、`iptables`、备份打包、`cat /proc/meminfo`、`input tap`、`screencap`、显式 tmpfs 的 remount、私有目录的 bind mount |

测试中发现并修正 1 处：`mount -o remount,rw /tmp` 最初被误判为“应放行”，
实际代码拦住它是对的（Android 上没有 `/tmp`，remount 任意路径 rw 必须拦）；
测试用例已修正为期望拦截，另加 `mount -o remount,rw -t tmpfs tmpfs /data/local/tmp` 作为合法 tmpfs 放行用例。

## 7. 未在真机上验证的部分（诚实说明）

| 项目 | 状态 | 原因 / 后续 |
|---|---|---|
| 真机安装 | ❌ 未测 | 没有手机接入；APK 结构、签名、内容已验证，安装应在 Android 16 上正常 |
| 真机启动 / 终端显示 | ❌ 未测 | TerminalView 是 Termux 的成熟组件，API 经 javap 逐项核对过；但实际渲染效果只能在真机上看 |
| root 模式执行 | ❌ 未测 | 依赖手机上的 su；qemu 验证的是二进制本身，不是 su 调用链 |
| 非 root 模式 W^X | ❌ 未测 | 理论上 Android 10+ 会拦；root 模式是主路径 |
| Claude Code 登录（OAuth / API key） | ❌ 未测 | 需要用户自己的账号；App 不替用户登录 |
| 5 个 root 技能实际执行 | ❌ 未测 | 技能是 Markdown 提示词 + root 命令，逻辑在 Claude Code 侧；需在真机上逐条试 |
| 保活服务 / 开机通知 | ❌ 未测 | 标准 Android API，代码按文档写；需真机验证 |

## 8. 已知限制

- 没有 git（没找到可用的 aarch64 静态 git 二进制）。
- APK 约 141 MB，首次解压需 ~650 MB 可用空间。
- 调试密钥签名。
- 只支持 arm64。

---

## 9. v1.1 闪退修复（2026-11-06 01:16–01:40 HKT）

用户真机（OPPO Reno12，ColorOS 16，Android 16）反馈：打开 App 显示“正在释放”然后闪退。
空间够用（650MB 以上），APK 安装没问题。

### 9.1 闪退原因分析（按证据）

| 嫌疑 | 证据 | 结论 |
|---|---|---|
| 主线程 ANR | MainActivity 已在后台线程做释放（`new Thread(this::doSetupAndStart)`），主线程只显示 Toast | ❌ 排除 |
| 解压逻辑本身 | JVM 模拟测试（ExtractSimTest）：流式解压 321MB 只用 3.5 秒、堆峰值 1MB、SHA-256 通过 | ❌ 解压逻辑本身没问题 |
| **压缩资源实时解压** | v1.0 的 `assets/claude` 在 APK 里是 DEFLATE 压缩存放（147MB→321MB）；`AssetManager.open()` 要在手机上实时解压 321MB；**这条路从没在真机上验证过**（只在 Linux 上用 `unzip` 解过） | ⚠️ **最大嫌疑**：Android 16/ColorOS 上大文件实时解压是未经验证的环节 |
| `catch (Exception)` 接不住 `Error` | `doSetupAndStart` 只 catch `Exception`；一旦 `runSetup()` 抛出 OOM 等 `Error`，后台线程未捕获异常直接杀掉整个进程 = 闪退 | ⚠️ **真实存在的崩溃通道**，必须堵上 |
| 失败无提示 | 只有一个 3.5 秒就消失的 Toast，没有进度、没有错误信息 | ⚠️ 用户看不到发生了什么，只能看到闪退 |

**结论**：无法在没有真机的情况下 100% 锁定是 Android 原生解压层的问题还是 OOM，
但两条崩溃通道都是真实存在的。v1.1 用纵深防御同时堵上：
资源改不压缩存放（彻底去掉实时解压）、`catch (Throwable)`、进度条、空间检查、
原子写入 + SHA-256 校验、安全的错误重试框。

### 9.2 v1.1 改了什么

| 文件 | 改动 |
|---|---|
| `build.sh` | assets 改不压缩存放（`zip -0`）；versionCode 1→2，versionName 1.0→1.1 |
| `SetupManager.java` | SETUP_VERSION 1→2；新增进度回调；释放前检查空间（<700MB 直接报错）；写临时文件 + 校验大小 + 原子改名；SHA-256 校验不过删掉重来；chmod 改用 Java 文件权限 API（shell 只做兜底）；清掉上次失败的临时文件 |
| `MainActivity.java` | 释放时显示带进度条的对话框（阶段 + 百分比）；`catch (Exception)` 改 `catch (Throwable)`（OOM 也不闪退）；错误框加 Activity 存活检查、不可取消、说清原因、一键重试；首次进终端提示一次登录方法 |
| `strings.xml` | 新增：设置失败标题、重试、退出、登录提示 |
| `build.sh`（APK 体积） | 141MB → 约 308MB（资源不压缩的代价；装上打开就能用比体积重要） |

system 分区红线未动：RootBridge 黑名单、settings.json deny 规则、5 个 root 技能原样保留。

### 9.3 v1.1 测试结果

| 测试 | 方法 | 结果 |
|---|---|---|
| 构建 | `bash build.sh` | ✅ 约 22 秒，javac 仅 1 条 ProgressDialog 废弃警告（功能正常） |
| 包名 / 版本 | `aapt2 dump badging` | ✅ `com.mossfield.terminal`，versionCode 2，versionName 1.1，minSdk 29，targetSdk 34，启动 Activity 正常 |
| 资源存放 | `unzip -l -v` | ✅ `assets/claude`（321MB）和 `assets/ld-musl-aarch64.so.1` 都是 Stored（0% 压缩） |
| 签名 | `apksigner verify --verbose` | ✅ Verifies；v3 方案 true |
| 解压模拟 v1.1 | ExtractSimTest（ZipFile + 256KB 缓冲） | ✅ **872ms**（v1.0 是 3500ms，快 4 倍），堆峰值 1MB，SHA-256 与预期一致 |
| 原子释放流程 | AtomicExtractTest（临时文件→大小校验→改名→SHA-256） | ✅ 4 步全过 |
| APK 内二进制 | 从 v1.1 APK 解出，qemu-aarch64-static + Android 路径 | ✅ `--version` → `2.1.318 (Claude Code)`；`-p "hello"` → `Not logged in · Please run /login` |
| RootBridge 黑名单 | 重新编译运行 RootBridgeTest | ✅ **39/39 通过，0 失败**（安全防线没动） |

### 9.4 仍未在真机上验证（诚实说明）

| 项目 | 状态 |
|---|---|
| v1.1 真机安装 + 首次释放 + 进终端 | ❌ 未测，需用户装 v1.1 试 |
| 释放进度条在真机上的显示 | ❌ 未测（代码按 Android 文档写） |
| 失败重试框在真机上的表现 | ❌ 未测（同上） |
| Claude Code 登录（OAuth / API key） | ❌ 未测，需要用户自己的账号 |
| 5 个 root 技能实际执行 / 保活服务 | ❌ 未测，同 v1.0 |

### 9.5 版本对照

| 版本 | APK | 大小 | 状态 |
|---|---|---|---|
| v1.0 | `moss-terminal-v1.0.apk` | 147,555,436 字节（约 141 MB） | 真机闪退（释放环节），已备份 |
| v1.1 | `moss-terminal-v1.1.apk` | 322,647,148 字节（约 308 MB） | 真机闪退（启动环节），已备份 |
| **v1.2** | `moss-terminal.apk` | 322,655,340 字节（约 308 MB） | **当前版，待用户真机验证** |

---

## 10. v1.2 启动闪退修复（2026-11-06 01:40–02:00 HKT）

用户真机（OPPO Reno12，ColorOS 16，Android 16）反馈：v1.1 解压那步成功了，
但解压完启动 Claude Code 的时候闪退，每次打开都重复。

### 10.1 闪退的真正原因（已锁定，证据链完整）

**根因：KeepAliveService 缺 FGS 类型权限，Android 14+ 上 `startForeground()` 直接抛 SecurityException 杀进程。**

| 环节 | 证据 |
|---|---|
| manifest 声明 | v1.1 的 `AndroidManifest.xml` 里服务声明了 `android:foregroundServiceType="dataSync"`，但只声明了通用的 `FOREGROUND_SERVICE`，**没有** `FOREGROUND_SERVICE_DATA_SYNC` |
| 目标 SDK | `build.sh` 里 `--target-sdk-version 34`（Android 14），新规矩适用 |
| 调用点 | `KeepAliveService.onStartCommand()` 里调 `startForeground(NOTIFICATION_ID, notification)` |
| 调用时机 | `MainActivity.doSetupAndStart()` 里 `startService(KeepAliveService)` 在**解压之后、创建终端会话之前**——所以用户看到“解压成功，然后闪退”，终端一次都没出现过 |
| 系统行为（已验证） | 多个独立来源确认：Android 14+ 上 targetSdk 34+ 的应用，`dataSync` 类型缺对应权限时 `startForeground()` 抛 `java.lang.SecurityException: Starting FGS with type dataSync ... requires permissions: [android.permission.FOREGROUND_SERVICE_DATA_SYNC]`（`Service.startForeground()`），向上传播为 `RuntimeException: Unable to create service ...`，杀掉宿主进程。MentraOS PR #2738 是同款 manifest 模式导致的首次启动闪退 |
| 确定性 | 每次启动都走同一条路 → 每次都崩，和用户“每次打开都重复”一致 |

**排除的嫌疑**（都查过，有证据）：
| 嫌疑 | 证据 | 结论 |
|---|---|---|
| 终端 JNI 库缺失 | APK 里 `lib/arm64-v8a/libtermux.so` 在（9,928 字节，未压缩）；`readelf` 确认导出 `Java_com_termux_terminal_JNI_createSubprocess` 等 5 个函数，和 `terminal-emulator.jar` 里 `JNI` 类的 native 声明一一对应；`javap` 确认 `JNI` 静态块调 `System.loadLibrary("termux")`，`TerminalSession` 构造器 6 参数签名和 `ClaudeRunner` 的调用一致 | ❌ 不是 JNI 的问题 |
| `.setup_done` 标记没写导致反复解压 | v1.1 的 `runSetup()` 最后写标记 + SHA-256 校验；用户反馈是“解压成功后”闪退，说明标记已写；且反复的是闪退本身，不是释放 | ❌ 排除 |
| 进度框关闭的界面生命周期 | `dismissSetupDialog()` 里 `dismiss()` 包了 try-catch，且 `startSession` 在另一个 `runOnUiThread` 里排队，顺序正确 | ❌ 排除 |
| su 调用链 | `ClaudeRunner` 里 `su` 只在创建会话时用，而崩溃点在创建会话之前（保活服务先启动）；su 问题只会导致会话打不开，不会导致闪退 | ❌ 排除（但顺带修了一个 su 路径问题，见 10.2） |

### 10.2 v1.2 改了什么

| 文件 | 改动 |
|---|---|
| `AndroidManifest.xml` | **补 `FOREGROUND_SERVICE_DATA_SYNC` 权限（根因修复）**；新增 `SYSTEM_ALERT_WINDOW` 权限（悬浮窗用）；声明 `FloatingWindowService` |
| `build.sh` | versionCode 2→3，versionName 1.1→1.2 |
| `MainActivity.java` | 启动后环节（自检 / root 检测 / 保活服务 / 启动前校验）全部包进 `try-catch(Throwable)`，失败进日志 + 错误框，不杀进程；`startSession` 改 catch `Throwable`（JNI 加载失败这类 Error 也不闪退）；新增启动前校验（见下）；root 没生效时弹明确提示框（继续普通模式 / 退出），不再只是一个 Toast；保活服务启动失败不致命（记日志继续）；菜单新增“悬浮窗”和“查看启动日志”；悬浮窗权限申请流程（一键去设置 + 回来自动开）；悬浮窗 API Key 广播接收 |
| `ClaudeRunner.java` | 新增 `preflight(rootMode)`：启动前校验二进制存在 + 可执行、加载器存在、脚本存在、终端 JNI 库能加载（`Class.forName("com.termux.terminal.JNI")`）、root 模式时 su 存在；任何一项不过都返回人话错误，显示在错误框里 |
| `RootBridge.java` | su 路径修正：KernelSU 真正的 su 是 `/data/adb/ksu/bin/su`，`v1.1` 把守护进程 `/data/adb/ksud` 排在前面是错的（ksud 不是标准 su，会导致 hasRoot 误判）；新增 `/data/adb/ap/bin/su`（APatch）；ksud 留最后兜底；新增包内可见的 `getSuPaths()` 供测试 |
| `LaunchLog.java`（新增） | 启动日志：每次启动的关键步骤（释放、自检、root 检测、保活服务、启动前校验、会话创建、失败原因）记一行带时间戳到 `files/logs/launch.log`；菜单里直接能看，不用连电脑抓 logcat |
| `SessionHolder.java`（新增） | 当前终端会话的静态持有者，供悬浮窗把文字写进终端（同一进程内静态持有，不用 IPC） |
| `FloatingWindowService.java`（新增） | 内置悬浮窗（见 10.3） |
| `strings.xml` | 新增：启动失败标题、启动日志、root 缺失提示、悬浮窗说明等 |

### 10.3 悬浮窗（一键自动填入，v1.2 新增）

- 悬浮窗是一颗可拖动的小球，点一下展开成一排快捷按钮，点按钮就把对应内容自动填进终端并回车，不用手动打字。
- 按钮：登录（`/login`）、体检、备份、查流量、清缓存、中断（Ctrl+C）、API Key（打开设置框，不把 key 打进终端）、关闭悬浮窗。
- 开启方式：菜单 → 悬浮窗。没权限时先弹框讲清楚是干什么的，再一键去系统设置里开，回来自动开。
- 权限：`SYSTEM_ALERT_WINDOW`（悬浮窗），只用来显示按钮，不偷看其他应用。
- 安全：自动填入的文字都只是终端输入，最终执行的 root 命令仍要过 RootBridge 黑名单 + settings.json deny，悬浮窗本身不绕过任何防线。`OverlaySafetyTest` 逐条验证过。

### 10.4 v1.2 测试结果

| 测试 | 方法 | 结果 |
|---|---|---|
| 构建 | `bash build.sh` | ✅ 约 21 秒，只有 1 条 ProgressDialog 废弃警告（功能正常，和 v1.1 一样） |
| 包名 / 版本 | `aapt2 dump badging` | ✅ `com.mossfield.terminal`，versionCode 3，versionName 1.2，minSdk 29，targetSdk 34，启动 Activity 正常 |
| 权限 | 同上 | ✅ 含 `FOREGROUND_SERVICE_DATA_SYNC` 和 `SYSTEM_ALERT_WINDOW` |
| APK 内容 | `unzip -l` | ✅ classes.dex（188,564 字节）、assets/claude（321 MB，Stored）、assets/ld-musl-aarch64.so.1、lib/arm64-v8a/libtermux.so，共 14 个文件 |
| 新类进 dex | `strings classes.dex` | ✅ `FloatingWindowService`、`SessionHolder`、`LaunchLog` 都在 |
| 签名 | `apksigner verify --verbose` | ✅ Verifies；v3 方案 true |
| APK 内二进制 | 从 v1.2 APK 流式读出算 SHA-256（不落盘） | ✅ 和期望值完全一致 `3c9743b0…e33ec423`，二进制和 v1.1 是同一个（v1.1 已用 qemu 验证过 `--version` → `2.1.318`；本机 qemu 已不在，无法重跑，SHA 一致即等价） |
| **ManifestFgsTest（回归测试）** | JVM 直跑，解析 manifest | ✅ **v1.2 manifest 通过**；**v1.1 风格 manifest（去掉 DATA_SYNC 权限）失败**，exit=1，证明这个测试确实能抓住 v1.1 的 bug |
| **OverlaySafetyTest** | JVM 直跑 | ✅ 5 条自动填入指令全部过 RootBridge 黑名单；SU_PATHS 顺序正确（ksu/bin/su 在 ksud 前面） |
| RootBridge 黑名单 | 重新编译运行 RootBridgeTest | ✅ **39/39 通过，0 失败**（su 路径改动没影响黑名单） |

### 10.5 仍未在真机上验证（诚实说明）

| 项目 | 状态 |
|---|---|
| v1.2 真机安装 + 首次启动 + 进终端 | ❌ 未测，需用户装 v1.2 试（**最关键的一步**：这次修的是启动环节，之前两次都是这一步崩） |
| 悬浮窗真机表现 | ❌ 未测（代码按 Android 文档写；权限申请流程、拖动、按钮填入都要真机试） |
| 启动日志菜单、root 缺失提示框 | ❌ 未测（同上） |
| Claude Code 登录（OAuth / API key） | ❌ 未测，需要用户自己的账号 |
| 5 个 root 技能实际执行 / 保活服务 | ❌ 未测，同 v1.0/v1.1 |

### 10.6 版本对照（更新）

| 版本 | APK | 大小 | 状态 |
|---|---|---|---|
| v1.0 | `moss-terminal-v1.0.apk` | 147,555,436 字节（约 141 MB） | 真机闪退（释放环节），已备份 |
| v1.1 | `moss-terminal-v1.1.apk` | 322,647,148 字节（约 308 MB） | 真机闪退（启动环节，缺 FGS 权限），已备份 |
| v1.2 | `moss-terminal-v1.2.apk` | 322,655,340 字节（约 308 MB） | 真机报错（su 参数错，见 §11），已备份 |
| **v1.3** | `moss-terminal.apk` | 322,655,340 字节（约 308 MB） | **当前版，待用户真机验证** |

---

## 11. v1.3：su 调用格式（真机上报的新错误）

### 11.1 用户真机上报的错误

v1.2 装上后，终端里显示：

```
error: unrecognized subcommand 'exec /system/bin/sh /data/user/0/com.mossfield.terminal/files/run-claude.sh'

Usage: -c <COMMAND>

For more information, try '--help'.

[Process completed (code 2) - press Enter]
```

### 11.2 根因（已锁定，证据链完整）

**传给 Termux 会话的 args 缺了 argv[0]。**

1. `ClaudeRunner.createSession()`（root 模式）传的是：
   `shellPath = su`，`args = {"-c", "exec /system/bin/sh <脚本>"}`。
2. `TerminalSession.initializeEmulator()` 原样把这两个参数交给
   `JNI.createSubprocess(mShellPath, mCwd, mArgs, mEnv, ...)`
   （terminal-emulator 0.118.0，`TerminalSession.java:229`）。
3. 原生层 `termux.c`（同版本，`terminal-emulator/src/main/jni/termux.c:236-262`）
   是把 Java 的 `args` 数组**原样当成 argv**：
   `argv[i] = strdup(args[i])`，然后 `execvp(cmd, argv)`——
   它**不会**把 `cmd` 再拼成 argv[0]。
4. 所以 su 实际收到 `argv[0]="-c"`（程序名槽位）、
   `argv[1]="exec /system/bin/sh /data/.../run-claude.sh"`。
   su 是 Rust clap 程序，解析器跳过 argv[0]，把 argv[1] 当成子命令：
   `error: unrecognized subcommand 'exec ...'` + `Usage: -c <COMMAND>`，
   退出码 2——和用户上报的报错逐字一致。
5. 同样的毛病也藏在普通模式里：`args = {<脚本>}` 导致
   `execvp("/system/bin/sh", [<脚本>])`，sh 收不到脚本参数，
   只会打开一个空白交互 shell，Claude Code 永远起不来（静默失败）。

**结论**：Termux 的这个 API 的约定是 args 数组本身就是完整的 argv，
args[0] 必须就是程序本身（Termux 自己就是这么调的）。v1.2 漏了 argv[0]。

### 11.3 v1.3 改了什么

| 文件 | 改动 |
|---|---|
| `ClaudeRunner.java` | **核心修复**：新增 `buildArgv()`，root 模式返回 `{su, "-c", "exec /system/bin/sh <脚本>"}`，普通模式返回 `{"/system/bin/sh", <脚本>}`；`createSession()` 两处都改用它。`-c` 的值是单个字符串，su 收到后由它的 shell 解析；脚本路径是 App 自己生成的固定路径，不含空格和特殊字符，不需要再加引号。 |
| `MainActivity.java` | 新增“会话刚启动就退出”检测：记录会话创建时间，`onSessionFinished` 里如果会话 10 秒内非零退出（且是当前会话、还没弹过框），记启动日志并弹明白的错误框 + 一键重试，不再只剩终端里一行报错 + 一个 Toast。正常退出（退出码 0）或跑了一阵子才退出的，走原来的 Toast。 |
| `build.sh` | versionCode 3→4，versionName 1.2→1.3 |
| `tests/SuArgvTest.java`（新增） | Java 单元测试：断言 `buildArgv()` 的 root/普通输出（含 argv[0] 就是程序本身）；用 clap 风格的模拟检查验证旧 argv 必报 unrecognized subcommand、新 argv 必通过 |
| `tests/mock-su.c`、`tests/su_argv_exec_test.py`（新增） | exec 级测试：mock su 是按 clap 格式解析 argv 的 C 程序；Python 用 `subprocess(executable=)` 精确控制 argv[0]，旧 argv 必须复现用户报错（退出 2），新 argv 必须通过（退出 0） |
| `README.md` / `TESTS.md` | 文档同步到 v1.3 |

### 11.4 v1.3 测试结果

| 测试 | 方法 | 结果 |
|---|---|---|
| 构建 | `bash build.sh` | ✅ 约 24 秒，只有 1 条 ProgressDialog 废弃警告（功能正常，和 v1.2 一样） |
| 包名 / 版本 | `aapt2 dump badging` | ✅ `com.mossfield.terminal`，versionCode 4，versionName 1.3，minSdk 29，targetSdk 34，启动 Activity 正常；权限含 `FOREGROUND_SERVICE_DATA_SYNC` 和 `SYSTEM_ALERT_WINDOW` |
| APK 内容 | `unzip -l` + Python 流式读 | ✅ assets/claude（321,810,696 字节，Stored）、assets/ld-musl-aarch64.so.1（723,480 字节，Stored）、lib/arm64-v8a/libtermux.so（9,928 字节，Stored） |
| 新代码进 dex | `strings classes.dex` | ✅ `buildArgv`、`sessionExitDialogShown`、`sessionStartMs` 都在 |
| 签名 | `apksigner verify --verbose` | ✅ Verifies；v3 方案 true |
| APK 内二进制 | Python 流式算 SHA-256（不落盘） | ✅ 和期望值完全一致 `3c9743b0…e33ec423`，二进制和 v1.2 是同一个（v1.2 已用 qemu 验证过 `--version` → `2.1.318`） |
| **SuArgvTest（回归测试）** | JVM 直跑，调真实 `ClaudeRunner.buildArgv` | ✅ **10/10 通过**：root argv = `[su, "-c", "exec /system/bin/sh <脚本>"]`；普通 argv = `[/system/bin/sh, <脚本>]`；旧 argv 被模拟 su 拒绝（unrecognized subcommand），新 argv 通过 |
| **su_argv_exec_test（exec 级测试）** | Python + mock su 二进制，精确控制 argv[0] | ✅ **5/5 通过**：旧 argv 退出 2，stderr 和用户上报的报错逐字一致；新 argv 退出 0，su 收到 -c 并执行命令 |
| RootBridge 黑名单（回归） | 重新编译运行 RootBridgeTest | ✅ **39/39 通过，0 失败**（本次改动没碰黑名单） |

### 11.5 仍未在真机上验证（诚实说明）

| 项目 | 状态 |
|---|---|
| v1.3 真机安装 + 首次启动 + 进终端 | ❌ 未测，需用户装 v1.3 试（**最关键的一步**：这次修的是 su 调用格式，之前三次都是启动环节崩） |
| “刚启动就退出”错误框真机表现 | ❌ 未测（代码逻辑已测，弹框显示要真机试） |
| 悬浮窗真机表现 | ❌ 未测（同 v1.2） |
| Claude Code 登录（OAuth / API key） | ❌ 未测，需要用户自己的账号 |
| 5 个 root 技能实际执行 / 保活服务 | ❌ 未测，同 v1.0/v1.1/v1.2 |

### 11.6 版本对照

见 §10.6（已更新：v1.3 为当前版，v1.2 已备份为 `moss-terminal-v1.2.apk`）。

## 12. v1.4（2026-11-06）

测试时间：2026-11-06 02:15–03:00 HKT
测试环境：Linux x86_64 虚拟机（JVM 直跑新代码）+ APK 内容检查
目标设备：OPPO Reno12 国行（PJV110，Dimensity 8250，aarch64，Android 16）——**未在真机上测过**

### 12.1 v1.4 测试结果

| 测试 | 方法 | 结果 |
|---|---|---|
| 构建 | `bash build.sh` | ✅ 约 24 秒，只有 1 条 ProgressDialog 废弃警告（功能正常，和 v1.2/v1.3 一样） |
| 包名 / 版本 | `aapt2 dump badging` | ✅ `com.mossfield.terminal`，versionCode 5，versionName 1.4，minSdk 29，targetSdk 34，启动 Activity 正常 |
| 签名 | `apksigner verify --verbose` | ✅ Verifies；v3 方案 true（v1/v2/v3.1/v4 false，和 v1.3 一样） |
| APK 内容 | `unzip -l` + Python 流式读 | ✅ assets/claude（321,810,696 字节，Stored）、assets/ld-musl-aarch64.so.1（723,480 字节，Stored）、lib/arm64-v8a/libtermux.so（9,928 字节，Stored）；classes.dex 202,852 字节 |
| APK 内二进制 | Python 流式算 SHA-256 | ✅ 和 v1.3 完全一致 `3c9743b0…e33ec423`，loader 也一致；二进制没动过，v1.0–v1.3 的 qemu 验证仍然有效 |
| 新代码进 dex | `strings` + Python 字节搜索 classes.dex | ✅ `NetworkDiagnostics`、`ANTHROPIC_BASE_URL=`、`isValidBaseUrl`、`rewriteLauncherScript`、`▶ 快捷`、`★ API Key`、`中转地址已保存`、`粘贴`、`清除已保存`、`再测一次` 都在 |
| 新字符串进 resources.arsc | Python 字节搜索 | ✅ `设置中转地址`、`网络诊断`、`GrowthBook`、`隐写标记`、`Asia/Hong_Kong` 都在（中转设置框的 caveat 全文在 resources.arsc 里） |
| 顶部常驻按钮进布局 | `aapt2 dump xmltree` + R.java | ✅ 布局顶部 4 个 Button（菜单/API Key/悬浮窗/网络诊断）+ 底部 9 个按键；R.java 有 `btn_menu_top`、`btn_api_key_top`、`btn_floating_top`、`btn_net_top` |
| 网络诊断实跑 | JVM 直跑 `NetworkDiagnostics.run("")` 和 `run("https://example.com")` | ✅ 跑出完整人话报告：DNS 解析、443 连通、中转域名解析+连通、结论都正常输出；`hostOf("https://proxy.example.com:8443/v1")` → `proxy.example.com`，`hostOf("garbage")` → null。注意：本机 VM 的 DNS 把 api.anthropic.com 解析到 198.18.103.103（沙箱网络），443 能连上——这是 VM 的网络，不是手机的；手机上的结果要用户实测 |
| 中转地址校验 | JVM 直跑 `SetupManager.isValidBaseUrl` | ✅ **18/18 通过**：合法地址（带端口/路径、http、IP、末尾斜杠）全放行；空、缺协议、空格、`$()`、反引号、`"`、`'`、`\`、`;`、`|`、`&`、`<`、`>`、ftp 全拦住 |
| 中转地址写进启动脚本 | JVM 直跑真实 `SetupManager.rewriteLauncherScript()`（Context/SharedPreferences 桩） | ✅ **5/5 通过**：填 `https://proxy.example.com:8443/v1` → 脚本里有 `export ANTHROPIC_BASE_URL=https://proxy.example.com:8443/v1`；恶意地址 `https://x.com/$(rm -rf /)` 不写入；清空后脚本里无该变量（恢复默认）；末尾斜杠被去掉；`exec …/bin/claude` 行正常 |
| API Key 换 key / 清 key | JVM 跑与 MainActivity.applyApiKey() 逐字相同的正则 | ✅ 替换旧 key、清空删行、首次插入三步都通过，改完三次 JSON 都合法 |
| RootBridge 黑名单（回归） | 重新编译运行 RootBridgeTest | ✅ **39/39 通过，0 失败**（本次改动没碰黑名单） |

### 12.2 仍未在真机上验证（诚实说明）

| 项目 | 状态 |
|---|---|
| v1.4 真机安装 + 首次启动 + 进终端 | ❌ 未测，需用户装 v1.4 试 |
| 网络诊断在真机上的结果（手机 DNS 到底能不能解析 api.anthropic.com） | ❌ 未测——这是 v1.4 最关键的一步，用户点顶部“网络诊断”就能看到 |
| 填中转地址后 Claude Code 能不能连上（取决于用户自己找的中转站支不支持 Anthropic API 格式） | ❌ 未测，需要用户自己的中转地址 + 重启会话后实测 |
| 顶部四个按钮 / 悬浮窗开关 / API Key 弹框的真机显示和点击 | ❌ 未测（代码和布局已验证，显示效果要真机试） |
| 悬浮窗真机表现 | ❌ 未测（同 v1.2/v1.3） |
| Claude Code 登录（OAuth / API key） | ❌ 未测，需要用户自己的账号 |
| 5 个 root 技能实际执行 / 保活服务 | ❌ 未测，同 v1.0–v1.3 |
| v1.3 已备份为 `moss-terminal-v1.3.apk`，v1.4 为当前版 `moss-terminal.apk` | ✅ |

---

## 13. v1.5 螃蟹桌宠移植（2026-11-06 02:21–02:45 HKT）

来源：shdy0602-glitch/clawd-on-badge，提交 70af12e。只拿了 GIF 形象 +
`docs/guides/state-mapping.md` 的映射逻辑，没搬 Electron / Node 依赖。
许可证见 `app/assets/clawd/ATTRIBUTION.md`：源码 AGPL-3.0，图片素材另有
`LICENSE-ARTWORK.txt`（保留所有权利、Clawd 角色属 Anthropic、粉丝自制、
不可商用、仅限个人使用）——APK 不要公开发布或商用。

### 13.1 移植内容

| 项 | 结果 |
|---|---|
| GIF 进包 | ✅ 24 个 `assets/clawd/clawd-*.gif`，共 1,947,672 字节，和上游字节数逐个一致；APK 内逐字节 SHA-256 比对全部一致，GIF89a 魔数，Stored（不压缩）和 claude 二进制一样 |
| 许可证文件进包 | ✅ `assets/clawd/ATTRIBUTION.md` + `LICENSE-AGPL-3.0.txt` + `LICENSE-ARTWORK.txt`，共 27 个 clawd 条目 |
| 新代码 | `ClawdPet.java`（纯 Java 状态机）、`ClawdPetView.java`（系统自带 `android.graphics.Movie` 播 GIF，零第三方依赖）、`ClawdStateBus.java`（终端→螃蟹静态通道）；`FloatingWindowService.java` 小球换成螃蟹；`MainActivity.java` 只加 3 行钩子（输出/会话结束/会话启动） |
| GIF 帧数 | ✅ PIL 核对：每张 45–90 帧，302×300，动画是真的多帧，不是静态图 |
| 版本 | ✅ `aapt2 dump badging`：`com.mossfield.terminal`，versionCode 6，versionName 1.5，minSdk 29，targetSdk 34 |
| 签名 | ✅ `apksigner verify`：Verifies，v3 true（v1/v2/v3.1/v4 false，和 v1.4 一样）；APK 324,665,138 字节 |
| 新代码进 dex | ✅ dexdump：`ClawdPet`、`ClawdPet$State`、`ClawdPetView`、`ClawdStateBus`、`FloatingWindowService`、`MainActivity`、`RootBridge`、`SessionHolder` 都在；`android/graphics/Movie` 被引用 2 处 |

### 13.2 状态映射（ClawdPet，JVM 单测 `tests/ClawdPetTest.java`）

| 上游事件 | 状态 | GIF | 单测 |
|---|---|---|---|
| 无活动 | idle | clawd-idle.gif | ✅ 默认就是 idle，接不上也回 idle |
| 随机空闲（25 秒） | idle | clawd-idle-reading.gif | ✅ 25 秒→看书，20 秒后回 idle |
| 用户提交提示 | thinking | clawd-thinking.gif | ✅ |
| 工具运行 1 会话 | working | clawd-typing.gif | ✅ |
| 工具运行 2 会话 | working | clawd-headphones-groove.gif | ✅ |
| 工具运行 3+ 会话 | working | clawd-building.gif | ✅ |
| 子代理 1 个 | juggling | clawd-headphones-groove.gif | ✅ |
| 子代理 2+ 个 | juggling | clawd-juggling.gif | ✅ |
| 子代理结束 | working | clawd-typing.gif | ✅ |
| 工具失败 | error | clawd-error.gif | ✅；连续第 2 次失败 → clawd-debugger.gif（加分项） |
| 结束 / 压缩后 | happy | clawd-happy.gif | ✅ 4 秒后回 idle |
| 权限请求 | notification | clawd-notification.gif | ✅ 6 秒后回 idle / 处理后回 idle |
| 压缩前 | sweeping | clawd-sweeping.gif | ✅ |
| 建 worktree | carrying | clawd-carrying.gif | ✅ |
| 60 秒无操作 | sleeping | clawd-sleeping.gif | ✅ 60 秒准时睡，有活动就醒 |
| 会话结束 | idle | clawd-idle.gif | ✅ |
| 迷你模式 | mini | clawd-mini-*.gif | ✅ happy→mini-happy，notification→mini-alert，其余→mini-idle；进迷你播 mini-enter，点一下 mini-peek，拖动 mini-crabwalk |
| 连点彩蛋 | — | clawd-bubble / react-annoyed / react-double-jump | ✅ 第 2/3/4 下连点依次触发 |
| 上游已退役 | conducting | clawd-conducting.gif | 接口保留，未接入主映射（上游 2+ 子代理已改 juggling） |

**单测结果：`ClawdPetTest` 88/88 通过，0 失败。**

### 13.3 本 App 里真实能驱动的状态（诚实说明）

Claude Code 跑在终端子进程里，没有把 hook 事件发给 App 的通道，所以
“工具运行几会话 / 子代理几个 / 权限请求 / 压缩 / worktree”这几档目前
**只有状态机 + 单测，没有真实事件源**，API 已预留在 `ClawdPet` 上，
以后接上 Claude hook 再调它们。真实跑通的驱动链（代码已接好）：

| 驱动 | 效果 | 验证 |
|---|---|---|
| 点悬浮窗快捷按钮发提示 | thinking | 代码：`fillText()` 调 `onUserPromptSubmit()`；单测覆盖 thinking |
| 终端有输出（`onTextChanged`） | typing | 代码：`MainActivity` 调 `ClawdStateBus.noteTerminalOutput()`；单测覆盖输出→typing |
| 输出停 3 秒 | happy 4 秒 → idle | 单测覆盖 |
| 60 秒无操作 | sleeping，有活动就醒 | 单测覆盖 |
| 会话启动 / 结束 | idle | 代码已接；单测覆盖 |

### 13.4 原有功能回归

| 测试 | 方法 | 结果 |
|---|---|---|
| RootBridge 黑名单 | 重新编译运行 RootBridgeTest | ✅ **39/39 通过，0 失败**（v1.5 没碰黑名单，RootBridge.java 未改） |
| 悬浮窗按钮还在 | 源码 + dex 检查 | ✅ `QUICK_ACTIONS` 5 个原样（登录/体检/备份/查流量/清缓存）+ `★ API Key` + `中断` + `关闭悬浮窗`，共 8 个按钮；面板展开/收起、拖动逻辑未改；只是“▶ 快捷”大按钮换成了螃蟹 |
| 顶部四个按钮 / 网络诊断 / 中转入口 / 一键填 API Key | 未改动 | ✅ `MainActivity` 只加 3 行螃蟹钩子，其余代码未动 |
| 构建 | `bash build.sh clean` | ✅ 约 26 秒；只有 1 条 javac 废弃 API 提示（`Movie` 是废弃 API，但 API 34 仍可用，和 v1.4 的 ProgressDialog 提示同类） |

### 13.5 仍未验证（需真机 / 用户装）

| 项目 | 状态 |
|---|---|
| 螃蟹在真机上能不能显示、动起来 | ❌ 未测——本机无 adb、无模拟器；GIF 本身是合法多帧 GIF89a，`Movie` 是系统内置解码器，dex 已确认引用，但真机渲染只能装后看 |
| 状态切换在真机上的实际观感（思考/打字/开心/睡觉切得顺不顺） | ❌ 未测，需用户装 v1.5 后用一会儿看 |
| 迷你模式长按切换、连点彩蛋的真机手感 | ❌ 未测（逻辑已单测） |
| v1.4 已备份为 `/tmp/moss-terminal-v1.4-backup.apk`；v1.5 为当前版 `moss-terminal.apk`，另存 `moss-terminal-v1.5.apk` | ✅ |

## 14. v1.6 DeepSeek 后端切换（2026-11-06）

用户不要连 Anthropic 的 API，改用 DeepSeek。按 DeepSeek 官方文档（Claude Code
接入 DeepSeek）照抄 8 个环境变量。

### 14.1 改了什么

| 文件 | 改动 |
|---|---|
| `SetupManager.java` | 新增 DeepSeek 常量（`DEEPSEEK_BASE_URL=https://api.deepseek.com/anthropic`、`DEEPSEEK_MODEL_FLASH_1M=deepseek-flash[1m]`、`DEEPSEEK_MODEL_FLASH=deepseek-flash`、`DEEPSEEK_EFFORT_LEVEL=max`、`DEEPSEEK_AUTO_COMPACT_WINDOW=786432`、`KEY_DEEPSEEK_MIGRATED`、`KEY_API_KEY`）；`getSavedBaseUrl()` 没填返回 DeepSeek 默认地址，不再是 `""`（不再回落到 api.anthropic.com）；新增 `migrateToDeepSeekDefaults()`（升级时一次性清掉 v1.4 时代保存的中转地址，`KEY_DEEPSEEK_MIGRATED` 标志防重复）；新增静态纯函数 `buildLauncherScript(files, baseUrl)`（8 个 DeepSeek export，模型值双引号，baseUrl 无效/为空回落 DeepSeek、去尾部斜杠；脚本里不写任何 Key）；新增静态纯函数 `applyAuthTokenToSettingsJson(json, key)`（写 ANTHROPIC_AUTH_TOKEN，删旧 ANTHROPIC_API_KEY，绝不产生重复 env 键，key 为空删 AUTH_TOKEN，已正确返回原文不变，JSON 损坏抛 IOException）；`writeLauncherScript()` 改走 `buildLauncherScript()` |
| `ClaudeRunner.java` | `buildEnv()` 重构成静态 `buildEnv(files, baseUrl, apiKey)`：8 个 DeepSeek 变量 + 有 Key 时加 `ANTHROPIC_AUTH_TOKEN`（只走内存，不写文件；和 settings.json env 块是同一个值，不冲突）；无 Key 时不写；不再写 ANTHROPIC_API_KEY；实例 `buildEnv()` 改调静态版 |
| `MainActivity.java` | 删本地 `KEY_API_KEY` / `KEY_BASE_URL` 定义，改用 `SetupManager` 的（sed 替换，grep 核对无残留）；`applyApiKey()` 重写走 `SetupManager.applyAuthTokenToSettingsJson()`（只在内容变化时写，写后 chmod 600）；`showNetworkDiagnostics()` 传生效 URL；`showBaseUrlDialog()` 预填生效 URL；`saveBaseUrl()` 提示改成“已恢复默认 DeepSeek 地址”；`doSetupAndStart()` 在 `rewriteLauncherScript()` 前调 `migrateToDeepSeekDefaults()` |
| `NetworkDiagnostics.java` | `run()` 改测生效后端（默认 DeepSeek）的 DNS + 443，不再写死 api.anthropic.com；报告说人话，网络通但还不通时提示 Key 是剩余变量 |
| `strings.xml` | `login_hint`（改提 DeepSeek API Key）、`base_url_hint`（“留空 = 用默认 DeepSeek 地址”）、`base_url_caveat`（DeepSeek 默认；中转的 GrowthBook host 上报 + 2026-06-30 隐写标记事件提醒保留，Asia/Hong_Kong 不在受影响时区）、`api_key_hint`（“输入 DeepSeek API Key（只存本机，不上传）”）、`about_text`（v1.6 说明前置） |
| `build.sh` | versionCode 6→7，versionName 1.5→1.6 |

### 14.2 Key 流向（修好的两处 + 一处新补）

| 环节 | 之前（v1.5） | 现在（v1.6） |
|---|---|---|
| 名字 | 写 `ANTHROPIC_API_KEY`（错的） | 写 `ANTHROPIC_AUTH_TOKEN`（DeepSeek 文档用的） |
| settings.json env 块 | 只在没有 Key 时才写，换 Key 不生效（v1.4 已修换 Key）；清 Key 后再存会插第二个 env 块（重复 JSON 键，v1.4 的 bug） | `applyAuthTokenToSettingsJson` 用 org.json 重写：换 Key 替换、清 Key 删行、旧名迁移掉、**永远只有一个 env 键**；已正确时原文不动；写后 chmod 600 |
| 会话环境变量 | `buildEnv()` 根本不带 Key——Key 只走 settings.json，Claude Code 读不读得到 settings.json 的 env 是另一回事 | `buildEnv()` 也带 `ANTHROPIC_AUTH_TOKEN`（只走内存），Key 确实传给 Claude Code 进程；和 settings.json 是同一个值，不冲突 |
| 启动脚本 | 无 Key（对的，脚本里不该有 Key） | 仍无 Key；只含 8 个 DeepSeek 环境变量 |

**Key 绝不写进 APK、脚本、配置文件、日志或任何工作文件。** 单测用的 Key
是假占位符 `TEST-KEY-PLACEHOLDER-1234` / `TEST-KEY-PLACEHOLDER-5678`，
不是真 Key。APK 内 dex + resources.arsc 扫 `sk-[A-Za-z0-9]{10,}` 零命中。

**org.json 注**：Android SDK 的 `android.jar` 里的 `org.json` 是 Stub（运行时
`RuntimeException: Stub!`，已实测确认）。JVM 单测用 Maven Central 下载的真
`json-20240303.jar`（78,332 字节，roundtrip 实测通过），放在
`tests/json-20240303.jar`，**只供单测，不进 APK**（build.sh 只打包
assets/lib/dex）。APK 里 org.json 仍是 Android 系统自带的真实现，手机上没问题。

### 14.3 单测（`tests/DeepSeekTest.java`，50/50 通过）

编译：`javac -cp tests/json-20240303.jar:<android.jar>:app/libs/terminal-emulator.jar:app/libs/terminal-view.jar:tests/stub -d tests/classes app/src/.../ClaudeRunner.java app/src/.../SetupManager.java app/src/.../RootBridge.java tests/stub/android/util/Log.java tests/DeepSeekTest.java`；
运行：`java -cp tests/classes:app/libs/terminal-emulator.jar:app/libs/terminal-view.jar:tests/json-20240303.jar com.mossfield.terminal.DeepSeekTest`（**运行时不带 android.jar**，否则 org.json Stub 会顶掉真 jar）。

| 组 | 覆盖 | 结果 |
|---|---|---|
| 启动脚本（默认） | 8 个 DeepSeek export 全在且值对；无 api.anthropic.com；无 ANTHROPIC_API_KEY / ANTHROPIC_AUTH_TOKEN；仍 `exec …/bin/claude "$@"` | ✅ 12/12 |
| 启动脚本（自定义 baseUrl） | 只改 ANTHROPIC_BASE_URL，模型变量仍是 DeepSeek | ✅ 3/3 |
| 启动脚本（无效/空 baseUrl） | 回落 DeepSeek；尾部斜杠去掉 | ✅ 3/3 |
| buildEnv | 8 个 DeepSeek 变量 + 有 Key 时 ANTHROPIC_AUTH_TOKEN、无 Key 时不写、无 ANTHROPIC_API_KEY、无 api.anthropic.com；自定义 baseUrl 只改 BASE_URL | ✅ 14/14 |
| applyAuthTokenToSettingsJson | 默认 settings.json 上写 Key（env 块新建、AUTH_TOKEN 对、恰好 1 个 env 键、permissions/cleanupPeriodDays 原样保留）；换 Key；清 Key；旧 ANTHROPIC_API_KEY 迁移；清 Key 后再存仍 1 个 env 键（v1.4 bug 回归）；已正确返回原文不变；已有其他 env 变量保留；JSON 损坏抛 IOException | ✅ 18/18 |

**单测结果：`DEEPSEEK_TEST: passed=50 failed=0`。** 实际生成的默认脚本（单测打印）：

```
export ANTHROPIC_BASE_URL=https://api.deepseek.com/anthropic
export ANTHROPIC_MODEL="deepseek-flash[1m]"
export ANTHROPIC_DEFAULT_OPUS_MODEL="deepseek-flash[1m]"
export ANTHROPIC_DEFAULT_SONNET_MODEL="deepseek-flash[1m]"
export ANTHROPIC_DEFAULT_HAIKU_MODEL="deepseek-flash"
export CLAUDE_CODE_SUBAGENT_MODEL="deepseek-flash"
export CLAUDE_CODE_EFFORT_LEVEL="max"
export CLAUDE_CODE_AUTO_COMPACT_WINDOW="786432"
```

### 14.4 回归

| 测试 | 方法 | 结果 |
|---|---|---|
| SuArgvTest（v1.3 su argv） | 重新编译运行 | ✅ **10/10 通过，0 失败**（`buildEnv` 重构没碰 `buildArgv`） |
| RootBridge 黑名单 | 重新编译运行 | ✅ **39/39 通过，0 失败**（RootBridge.java 未改） |
| ClawdPet（v1.5 螃蟹） | 重新编译运行 | ✅ **88/88 通过，0 失败**（ClawdPet.java 未改） |
| 源码残留检查 | `grep ANTHROPIC_API_KEY` | ✅ 只剩注释 + 迁移/删除逻辑；`grep api.anthropic.com` 只剩注释和诊断文案（“不再连”） |
| 悬浮窗 | grep | ✅ 只发 broadcast 打开 API Key 对话框，不直接碰 Key |

### 14.5 构建与包验证

| 项 | 结果 |
|---|---|
| 构建 | ✅ `bash build.sh clean` 约 24 秒；只有 1 条 javac 废弃 API 提示（和 v1.4/v1.5 同类） |
| 版本 | ✅ `aapt2 dump badging`：`com.mossfield.terminal`，versionCode **7**，versionName **1.6**，minSdk 29，targetSdk 34 |
| 签名 | ✅ `apksigner verify`：Verifies，v3 true（v1/v2/v3.1/v4 false，和 v1.5 一样）；1 个签名者 |
| 大小 | 324,665,138 字节（和 v1.5 同字节数是巧合——包体由 321 MB 二进制 + 资源主导，dex 只 ~216 KB；MD5 已变：`2ebfe52ebe2922e8bbc06de4a61463af`，v1.5 是 `f0e1d76fd48e9fbea883036cc242662c`） |
| claude 二进制 | ✅ APK 内 `assets/claude` SHA-256 `3c9743b09f5c4b3b1d7dc0f303f8b8b7fc45f82df0c08debb52e5976e33ec423`，和预期一致 |
| 螃蟹 GIF | ✅ 24 个 `assets/clawd/clawd-*.gif` 还在 |
| DeepSeek 进 dex | ✅ dexdump：`https://api.deepseek.com/anthropic`、`ANTHROPIC_MODEL=deepseek-flash[1m]` 等常量在 dex 里；`buildLauncherScript`、`applyAuthTokenToSettingsJson`、`getSavedApiKey`、`migrateToDeepSeekDefaults` 四个新方法在 dex 里且有调用点 |
| DeepSeek 进资源 | ✅ resources.arsc 含 `api.deepseek.com`（2 处）、`DeepSeek API` |

### 14.6 仍未验证（需真机 / 用户）

| 项目 | 状态 |
|---|---|
| 真机安装 + 首次进终端 | ❌ 未测（同 v1.2–v1.5 的限制） |
| 用用户自己的 DeepSeek Key 连通 | ❌ 未测——**取决于用户自己的 DeepSeek Key 是否有效；本 App 不提供、不验证 Key**。用户需在 App 里用 API Key 按钮一键粘贴 Key，重启会话后看是否连通；如果还不通，用顶部网络诊断的结果 + 启动日志判断 |
| 中转入口改填自定义地址后是否生效 | ❌ 未测（逻辑和 v1.4 一样，只改了默认值） |
| 升级安装时旧中转设置的清理（`migrateToDeepSeekDefaults`） | ❌ 未测真机升级路径（逻辑已单测：标志位防重复；首次启动时清） |
| v1.5 已备份为 `moss-terminal-v1.5.apk`；v1.6 为当前版 `moss-terminal.apk`，另存 `moss-terminal-v1.6.apk` | ✅ |

---

## 15. v1.7 代码里清除 api.anthropic.com（2026-11-06）

用户真机截图：v1.5 还在连 api.anthropic.com，报 EAI_AGAIN + 启动失败。
用户要求“直接在代码里清除”——不要让用户改设置，从代码上保证 App 再也不会去连它。

### 15.1 v1.6 审计：4 条运行时路径能产出 api.anthropic.com

| # | 路径 | 结论 |
|---|---|---|
| 1 | **旧 run-claude.sh 残留（主因）** | ✅ 确认是漏洞。`SETUP_VERSION` 仍是 2，v1.5→v1.6 升级跳过 `runSetup()`；唯一重写脚本的 `rewriteLauncherScript()` 包在 try-catch 里，失败只记日志（“重写启动脚本失败（不影响终端）”），会话照常启动，旧脚本就活下来了。且没有任何地方校验脚本**内容**，菜单“重启会话”更是直接调 `startSession()`，连重写都不经过。v1.5 脚本根本没有 `ANTHROPIC_BASE_URL` 这一行 → Claude Code 用内置默认连 api.anthropic.com → EAI_AGAIN，和用户截图完全一致 |
| 2 | `getSavedBaseUrl()` 原样返回 | ✅ 确认。SharedPreferences 里若存了 `https://api.anthropic.com`（v1.4/v1.5 中转框填过），`isValidBaseUrl` 只查格式不查 host，直接写进脚本 + 会话 env；`migrateToDeepSeekDefaults()` 只清一次，之后再填没人拦 |
| 3 | `ClaudeRunner.buildEnv()` 同款漏洞 | ✅ 确认。会话环境变量也会带上它——脚本修好了，env 这一路还能带回来 |
| 4 | settings.json env 块 | ✅ 确认（潜伏路径）。`applyAuthTokenToSettingsJson` 从不碰 `ANTHROPIC_BASE_URL`，若 env 里有指向 api.anthropic.com 的值会永久保留（Claude Code 会读 settings.json env 块） |
| — | NetworkDiagnostics | ✅ 已干净：v1.6 已改测生效后端，不再写死 api.anthropic.com（入口再过一次 resolve，双保险） |
| — | strings.xml | ✅ 只有说明性文案（“不再连”、“之前连不上是因为…”），无运行时默认值，不用改 |

### 15.2 v1.7 改了什么

| 文件 | 改动 |
|---|---|
| `SetupManager.java` | 新增 `ANTHROPIC_API_HOST`（封禁常量）；新增 `isAnthropicApiUrl()`（host 精确相等才算，`api.anthropic.com.evil.com` / `evil-api.anthropic.com` 不算，URI 解析失败退化为子串判断）；新增 `resolveBaseUrl()`（全 App 唯一 choke point：空、非法、指向 api.anthropic.com 的一律回落 DeepSeek）；`getSavedBaseUrl()` 改走它；`buildLauncherScript()` 改走它（脚本注释也不再出现该字面串，否则自检会误判）；新增 `scriptNeedsRewrite()`（null / 含 api.anthropic.com / 缺 `export ANTHROPIC_BASE_URL=` 行都要重写）；新增 `ensureLauncherScript()`（静态版可单测 + 实例版：读现有脚本 → 需要就重写 → 写完读回校验，返回 boolean）；`applyAuthTokenToSettingsJson()` 顺手清掉 env 块里指向 api.anthropic.com 的 `ANTHROPIC_BASE_URL` |
| `MainActivity.java` | `doSetupAndStart()`：`rewriteLauncherScript()` 改成 `ensureLauncherScript()`，**失败就硬停**（弹明白的错 + 重试，不启动会话），不再静默跑旧脚本；`startSession()` 开头再验一次（覆盖菜单“重启会话”、root 缺失后继续等所有入口）；`saveBaseUrl()` 拒收 api.anthropic.com（手填也不保存，按恢复默认处理 + 提示） |
| `ClaudeRunner.java` | 静态 `buildEnv()` 改走 `SetupManager.resolveBaseUrl()` |
| `NetworkDiagnostics.java` | 入口过 `resolveBaseUrl`，永不测 api.anthropic.com；文案 v1.6→v1.7 |
| `build.sh` | versionCode 7→8，versionName 1.6→1.7 |

### 15.3 单测（`tests/AnthropicPurgeTest.java`，103/103 通过）

编译/运行方式同 DeepSeekTest（javac 带 android.jar + termux jars + stub + json jar；运行不带 android.jar）。

| 组 | 覆盖 | 结果 |
|---|---|---|
| isAnthropicApiUrl | 精确 host（大小写/端口/路径）、近似域名不算、null/空不算 | ✅ 11/11 |
| resolveBaseUrl | null/空/非法/Anthropic（含大小写/尾部斜杠）→ DeepSeek；合法自定义中转保留 | ✅ 11/11 |
| scriptNeedsRewrite | null / v1.5 无 BASE_URL 行 / 含 Anthropic 地址要重写；干净的 DeepSeek/自定义中转不重写 | ✅ 5/5 |
| ensureLauncherScript（模拟 v1.5 旧脚本残留） | 无 BASE_URL 行的 v1.5 脚本 → 重写，8 个 DeepSeek 变量全在、api.anthropic.com 零出现；含 Anthropic 地址的旧脚本 → 同样重写干净（即使传入的 baseUrl 本身就是 Anthropic 地址）；干净脚本字节不变；合法自定义中转脚本不动；缺失脚本新建干净 | ✅ 38/38 |
| buildLauncherScript / buildEnv（Anthropic 输入） | 传入 Anthropic 地址也被换成 DeepSeek，8 变量全在、零出现；Key 仍正常传 | ✅ 17/17 |
| settings.json 清理 | 含 Anthropic 地址的 env 被清掉，AUTH_TOKEN 和其他变量保留；指向 DeepSeek 的不动；无 env 块的不动 | ✅ 7/7 |

**单测结果：`ANTHROPIC_PURGE_TEST: passed=103 failed=0`。**

修测试时踩过一个坑：第一版新脚本头的注释里写了 `api.anthropic.com` 这个字面串，
导致自检把它当脏脚本（写完读回校验失败）。已改成“本脚本只连 DeepSeek，
不连 Anthropic 官方地址”，生成的脚本里不再出现该字面串。

### 15.4 回归 + 包验证

| 测试 | 结果 |
|---|---|
| DeepSeekTest（v1.6） | ✅ **50/50 通过** |
| SuArgvTest（v1.3 su argv） | ✅ **10/10 通过** |
| RootBridge 黑名单 | ✅ **39/39 通过**（没碰黑名单） |
| ClawdPet（v1.5 螃蟹，见 §16 更新为 94/94） | ✅ 通过 |
| 包版本 | ✅ `aapt2 dump badging`：`com.mossfield.terminal`，versionCode **8**，versionName **1.7** |
| 签名 | ✅ `apksigner verify`：Verifies，v3 true；1 个签名者 |
| 大小 / MD5 | 324,673,330 字节，MD5 `6487796e9f00cd4b0e7d00aaaded722d` |
| claude 二进制 | ✅ APK 内 `assets/claude` SHA-256 `3c9743b0…e33ec423`，和预期一致 |
| 螃蟹 GIF | ✅ 24 个 `assets/clawd/clawd-*.gif` 还在 |
| 新代码进 dex | ✅ `CrabWander`、`CrabScale`、`ClawdPet$Transform`、`ensureLauncherScript`、`scriptNeedsRewrite`、`resolveBaseUrl` 都在 |
| dex 残留检查 | `api.anthropic.com` 出现 3 次，来源已逐一核对：①封禁常量 `ANTHROPIC_API_HOST`（只用于比较）；②保存中转地址时的禁用提示文案；③网络诊断报告文案。`ANTHROPIC_BASE_URL=https://api.anthropic.com` **零出现**——没有任何代码路径会把 base URL 设成它。resources.arsc 里 1 次是 strings.xml 的说明文案（“之前连不上是因为…”），不是运行时默认值 |

### 15.5 仍未验证（需真机 / 用户）

| 项目 | 状态 |
|---|---|
| v1.5 升 v1.7 后旧脚本被强制重写（真机升级路径） | ❌ 未测——逻辑已单测（模拟 v1.5 旧脚本残留 103/103），真机升级要用户装 v1.7 看启动日志 |
| 用用户自己的 DeepSeek Key 连通 | ❌ 未测（同 v1.6 的限制） |
| v1.6 已备份为 `moss-terminal-v1.6.apk`；v1.7 为当前版 `moss-terminal.apk`，另存 `moss-terminal-v1.7.apk` | ✅ |

---

## 16. v1.7 螃蟹活起来了（2026-11-06）

用户连补四个要求，一并做到 v1.7：能移动、锁屏也有它、动作多一点、能缩放、
翻跟头翻一半自己晕倒、跟着手移动。**24 张 GIF 没变，全是复用组合 +
旋转/缩放变换，不新加素材。**

### 16.1 改了什么

| 文件 | 改动 |
|---|---|
| `ClawdPet.java` | 状态数 24→30（新加 `LOOK_AROUND` / `DOZE` / `WAVE` / `STRETCH` / `SPIN` / `SOMERSAULT`，GIF 全复用已有文件）；新增 `Transform`（旋转/翻面/压扁/缩放目标）；空闲动作轮换（10 秒宽限期后按表轮换：看书→张望→挥手→伸懒腰→打盹→发呆，每个 2–4 秒；v1.5 的“25 秒→看书、20 秒后回 idle”已并入轮换表）；单点反应（每 3 次单点：跳→转圈→挥爪）；工作状态变换区分（思考起伏/出错抖动/成功长大）；翻跟头三段（翻 1.2s→晕 1.8s→爬起来 1s，共 4s；空闲满 8 轮自动来一次；连点第 3 次双跳换成翻跟头；晕时点按没反应）；`setLogical` 进 idle 时总是重启宽限期（会话结束那一刻看到的总是 plain IDLE）；新增 `isIdleFamily()` / `getSomersaultPhase()` / `isSomersaulting()` / `isDizzy()` / `getDisplayTransform()`；`gifFor` 迷你映射补 WAVE/STRETCH→mini-happy |
| `CrabWander.java`（新增，纯 Java） | 位置控制器：空闲溜达（每 100ms 走 3px，约 30px/秒，碰边反射 ±20° 扰动，9–15 秒歇 2.5 秒）；弹簧跟手（每步走剩余距离的 22%，封顶 48px/步，不瞬移；手指停下后滑到位；松手停落点、2 秒后恢复溜达）；朝向（facingScaleX 翻面 + 倾斜角，封顶 ±15°，换方向 300ms 转身信号）；外部暂停（翻跟头时服务层置 true）；随机方向可注种子（测试可复现） |
| `CrabScale.java`（新增，纯 Java） | 尺寸控制器：状态目标缩放（开心/兴奋 1.12，睡觉 0.85，其余 1.0），300ms smoothstep 平滑插值；捏合尺寸（64–180dp 夹紧，无效因子原样返回）；`SizeStore` 持久化接口 + 内存实现（单测用，服务层用 SharedPreferences 实现） |
| `ClawdPetView.java` | 新增 `setTransform()` / `setDrawScale()` / `getDrawScale()`；`onDraw` 里变换绕 View 中心做（旋转/翻面/缩放只改画出来的螃蟹，View 尺寸和点按区域不动） |
| `FloatingWindowService.java` | 新增 `OVERLAY_FLAGS`（`FLAG_NOT_FOCUSABLE \| FLAG_SHOW_WHEN_LOCKED`，锁屏也显示，不解开锁屏）；新增 `isPanelAllowed()`（锁屏上不展开面板，点按唤醒到应用内，系统先要求解锁）；溜达循环（每 100ms，面板展开/睡觉/翻跟头时不动）；弹簧跟手（触摸监听只更新目标，位置由 100ms 步进慢慢追）；双指捏合（`ScaleGestureDetector`，调大小 64–180dp，松手存 SharedPreferences，下次打开还用它；捏合时不走拖动、不触发点击）；长按迷你保留（迷你=缩到最小 64dp，恢复回用户捏合的尺寸）；`applyPetVisual()`（每 tick：状态 + 溜达 crabwalk 覆盖（仅空闲系）+ 变换 + 状态缩放）；触摸只在碰到螃蟹本身时才收，不加全屏透明层 |

### 16.2 单测

| 测试 | 覆盖 | 结果 |
|---|---|---|
| **ClawdPetActsTest（新增）** | 空闲轮换（10 秒宽限期、60 秒内 ≥4 种动作、不闪切、看书在轮换里）；单点反应（第 3 次跳、第 6 次转圈、第 9 次挥爪、第 12 次循环回跳）；工作状态变换各不相同；翻跟头完整跑完（翻 180°→晕 90°贴地→爬起来→4 秒回 idle，期间点按被忽略，恢复后正常）；空闲满 8 轮自动翻一次（200 秒内恰好 1 次，约 150 秒时）；连点第 3 次双跳换成翻跟头 | ✅ **59/59 通过** |
| **CrabWanderTest（新增）** | 溜达（位置随时间变化、不出界、每步 ≤3px）；跟手（每步 ≤48px，最终到手指处 2px 内）；快速甩动（第一步也被封顶，不瞬移，之后加速追上）；松手（停落点 2 秒不动，之后恢复溜达）；朝向（往右朝右、往左朝左）；暂停（翻跟头时不动，恢复后继续） | ✅ **639/639 通过** |
| **CrabScaleTest（新增）** | 状态目标缩放（开心 1.12、睡觉 0.85、其余 1.0）；平滑过渡（300ms smoothstep，中途 1.06，单调，到位后不动）；捏合夹紧（64–180，无效因子原样返回）；SizeStore 存取往返 | ✅ **37/37 通过** |
| **FloatingOverlayTest（新增）** | OVERLAY_FLAGS 含 FLAG_SHOW_WHEN_LOCKED + FLAG_NOT_FOCUSABLE，不含 FLAG_DISMISS_KEYGUARD；锁屏不展开面板（locked→false，unlocked→true） | ✅ **5/5 通过** |
| **ClawdPetTest（更新）** | 状态数 24→30（6 个新状态全复用已有 GIF，GIF 文件仍是 24 个不重复）；25 秒规则并入轮换表（9 秒宽限期、10 秒开始轮换、25/45 秒都在轮换中）；“59 秒看书”→空闲系、“通知过期回 idle”→空闲系；其余断言原样保留 | ✅ **94/94 通过** |

**单测总计：1036 项断言全部通过**（103 + 50 + 10 + 94 + 59 + 639 + 37 + 39 + 5）。

修测试时踩过两个坑：① `ClawdPetTest` 的“会话结束回 idle”——`onSessionEnd` 时 logical 本来已是 IDLE，`setLogical` 是 no-op，宽限期没重启，显示停在轮换动作上。已改成进 idle（或明确重置回 idle）时总是重启宽限期。② `CrabWanderTest` 的跟手循环在 t=0 第一步（初始化、不移动）就 break 了——已改成 t>0 才判断到达。

### 16.3 回归

| 测试 | 结果 |
|---|---|
| AnthropicPurgeTest（§15） | ✅ **103/103 通过** |
| DeepSeekTest（v1.6） | ✅ **50/50 通过** |
| SuArgvTest（v1.3 su argv） | ✅ **10/10 通过** |
| RootBridge 黑名单 | ✅ **39/39 通过**（system 分区三层防御未动） |
| 构建 | ✅ `bash build.sh clean` 约 26 秒；只有 1 条 javac 废弃 API 提示（和 v1.4/v1.5 同类） |

### 16.4 仍未验证（需真机 / 用户）

| 项目 | 状态 |
|---|---|
| 螃蟹溜达在真机上的实际观感（速度、碰边、坐下、转身顺不顺） | ❌ 未测——逻辑已单测，真机手感要用户装 v1.7 看 |
| 弹簧跟手的手感（滞后、追上、松手落点） | ❌ 未测（同上） |
| 捏合缩放的手感 + 尺寸持久化（下次打开还是这个大小） | ❌ 未测（逻辑已单测，SharedPreferences 读写要真机走一遍） |
| 锁屏显示（锁屏上看得到螃蟹、点按唤醒到应用内、不展开面板） | ❌ 未测——FLAG_SHOW_WHEN_LOCKED 在目标 SDK 34 上对悬浮窗窗口生效是按文档写的，需真机锁屏实测；如果该 flag 不够，改用等效的锁屏可见方案 |
| 空闲轮换 / 翻跟头 / 状态缩放的真机观感 | ❌ 未测（逻辑已单测，GIF + 变换的实际观感要真机看） |
| 100ms 溜达步进的耗电 | ❌ 未测——只是每 100ms 改一次 LayoutParams（位置变了才调），GIF 本来就在 50ms 循环重绘，理论上增加的耗电很小；真机用一会儿看 |
| v1.6 已备份为 `moss-terminal-v1.6.apk`；v1.7 为当前版 `moss-terminal.apk`，另存 `moss-terminal-v1.7.apk` | ✅ |
