# ROOT APK 设计文档：把 Claude Code 放进一个 Root APK

> 目标：给用户自己的 OPPO Reno12 国行（PJV110）做一个 APK，里面跑 Claude Code，root 能力全开。
> 状态：设计稿（v1.0，2026-11-06）——结论可执行，但有 6 个关键点必须在真机上验证后才能开工（见 §9.3）。
> 红线（全篇最高优先级）：**绝对不动 system 分区。** 不 remount、不写 `/system`、`/vendor`、`/product`、`/apex`、`/etc`、`/boot`。
> 范围：只针对用户自己的设备、自己的数据。不攻击第三方，不碰别人的数据，不对外扫描、不做代理出口。

## 0. 基线

| 项 | 值 | 来源 |
|---|---|---|
| 手机 | OPPO Reno12 国行 PJV110，Dimensity 8250（1×3.1 GHz A78 + 3×3.0 GHz A78 + 4×2.0 GHz A55），12/16 GB LPDDR5X，256/512 GB UFS 3.1 | 公开规格 |
| 系统 | ColorOS 16 / Android 16（API 36），aarch64 | 用户提供 |
| root | 临时 root：GhostLock / CVE-2026-43499 家族 + KernelSU late-load，锁 bootloader，**重启后 root 消失** | 用户提供 |
| Claude Code 基线 | 2.1.318（SDK 0.3.318）；2.1.88 重建源码树 | 本机已有研究 `~/workspace/claude-code-reverse/`、`~/workspace/CLAUDE_CODE_FORENSICS/` |
| 认证 | OAuth（Pro/Max 订阅）或 API key；二者只能二选一生效 | 08 章 |
| 硬件下限 | 官方要求 4 GB+ RAM、x64/ARM64；本机 12 GB+，够 | 官方文档 |

---

## 1. 可行性结论

**能做。** 但“直接把官方 Claude Code 二进制塞进 APK 就跑”这条最直觉的路是**死路**，真正的架构必须绕三个硬墙：

1. **glibc / bionic 墙（最大技术障碍）**：官方 Claude Code 是 Bun 编译的原生二进制，只发 `linux-arm64` 等 glibc 平台包，没有 Android（bionic）包。glibc 动态链接的二进制在 Android 上无法直接执行（解释器 `/lib/ld-linux-aarch64.so.1` 不存在，libc ABI 不同）。必须给它一个 glibc 用户空间（proot/chroot 里的 Debian rootfs），或者退回旧版纯 JS 形态。
2. **Android 10+ W^X 墙**：应用私有目录（`getFilesDir()`）里的文件被 SELinux 标为 `app_data_file`，`execve()` 直接报 `Permission denied`（error=13），`chmod +x` 也救不了。assets 解压到私有目录再执行的经典做法在 Android 16 上已死。唯一合规的执行位置是 `nativeLibraryDir`（`jniLibs/<abi>/` 里以 `.so` 结尾的文件由 PackageManager 解压到该目录，带 `exec_type` 标签），或 root 上下文执行。
3. **root 门禁墙（Claude Code 自带）**：`src/setup.ts:395-414`——Unix 下 uid 0 + `--dangerously-skip-permissions`（bypass 模式）= 直接 `process.exit(1)`，唯一的例外是真沙箱内（`IS_SANDBOX=1` 或 `CLAUDE_CODE_BUBBLEWRAP`）。Android 不在沙箱支持列表里（只有 macOS / Linux / WSL2+，见 21 章 §3），所以**root 身份下 bypass 模式必然被拒**。root 会话只能用默认权限模式（逐条问人），或者把 Claude Code 进程本身降回普通 app UID 跑、只把需要 root 的单条命令包进 `su -c`。

除此之外的墙都是可绕的：Phantom Process Killer（root 自己能关）、后台保活（前台服务 + 电池白名单）、终端 UI（有现成终端库）、认证（OAuth localhost 回调在 Android 上行得通）。

**一句话结论：能做，主架构是「APK + proot/chroot Debian rootfs + 官方 linux-arm64 原生二进制 + 终端 UI」，root 是增强层不是地基——没 root 时核心功能照常跑，有 root 时解锁 §5 的玩法。**

### 最大风险点（按致命程度排）

| # | 风险 | 后果 | 缓解 |
|---|---|---|---|
| RISK-1 | 官方原生二进制在 Debian proot/chroot 内跑不起来（16 KB 页大小对齐、缺共享库、客户端 attestation 校验环境） | 整个主架构推倒重来，只能退回旧版 JS | 先在真机做最小验证（§9.3 的 V0 实验），再写一行 APK 代码 |
| RISK-2 | Claude Code 的客户端证明（attestation，下沉到 Bun 的 Zig 层 HTTP 栈，claudefa.st 转述）拒绝非标准环境，或 OAuth 封禁传闻属实 | 能启动但登不上 / 账号被封 | 只用官方原版二进制、不改包；登录走用户自己的浏览器；API key 备用路线 |
| RISK-3 | 临时 root 在会话中途消失（重启、GhostLock 失效） | root shell 断连、后台任务死掉 | 启动时检测 root；root 消失自动降级非 root 模式并通知用户；关键状态落盘 |
| RISK-4 | Phantom Process Killer + ColorOS 后台管理杀掉 Claude 进程（Android 12+ 最多 32 个子进程，超了就 SIGKILL，无日志） | 会话无声死亡、MCP 子进程全灭 | 前台服务 + WakeLock + 电池优化白名单 + root 关掉 phantom 监控（R1） |
| RISK-5 | 提示注入直达 root：Claude Code 的权限门裁决“命令危不危险”，不裁决“命令是谁让下的”；CLAUDE.md / skill / MCP / 工具结果里的指令没有来源标记（SECURITY_AUDIT §6） | 一条藏在仓库文件里的指令 = root 执行 | 默认权限模式 + §6 的三层防线 + 高危 root 玩法默认关闭、逐条确认 |
| RISK-6 | APK 体积与首次下载（rootfs + 二进制数百 MB），用户手机存储与流量 | 首次启动慢、安装包过大 | APK 本体只含引导器，rootfs 与二进制首次启动时下载并校验 SHA256（§7） |

---

## 2. 关键发现（决定架构的 8 条事实）

| # | 事实 | 出处 | 架构含义 |
|---|---|---|---|
| F1 | Claude Code ≥2.1.113 的 npm 包只是 29 KB wrapper，装的是各平台原生二进制（约 250–320 MB/平台）；Node 只在 npm 安装时需要（22+），运行时不需要 | 官方文档；08 章 §6 | 想跑它就要解决原生二进制的运行环境，不能只靠“装个 Node” |
| F2 | ≤2.1.112 的 npm 包是纯 JS、跑在用户 Node 上；之后全部改为原生二进制 | 公开资料（2026-10 核对） | 备选路线 B 只能钉死旧版本，功能、安全、attestation 全部落后 |
| F3 | Agent SDK（`@anthropic-ai/claude-agent-sdk`）不是 in-process 引擎，它是**拉起 `claude` CLI 子进程**的壳（`executable` 选项指向 CLI） | 19 章 §10 | SDK 路线省不掉原生二进制；别指望“SDK + Node 就能绕过 F1” |
| F4 | 官方不发 Android/bionic 包；Termux 有 bionic 版 Node（`nodejs-lts` 24.x）与 Bun（TUR 构建，1.4.1+），但二者都跑不了 glibc 链接的官方二进制 | 公开资料；bunproot 文档 | bionic 用户空间 ≠ glibc 用户空间，两个世界要分开装 |
| F5 | Android 10+ W^X：`getFilesDir()` 不可执行；可执行位只能在 `nativeLibraryDir`（`jniLibs/*.so`，`useLegacyPackaging=true` 强制落盘） | Pockr 实战文章；公开资料 | 所有自带可执行文件（引导器、proot、loader）必须走 `jniLibs/*.so` |
| F6 | root + bypass = 退出（`setup.ts:395-414`）；`IS_SANDBOX=1` 是例外，但 Android 无真沙箱，**不许伪造该变量骗过门禁** | 21 章 §4；20 章 R2 | root 模式禁用 bypass；无人值守自动化必须用非 root UID + 预批规则 |
| F7 | Android 12+ Phantom Process Killer：后台子进程超 32 个或占 CPU 就 SIGKILL；`adb shell settings put global settings_enable_monitor_phantom_procs false` 可关（Android 13+ 持久，OTA 可能重置） | XDA；Termux 实战 | root 玩法 R1 就是关掉它；这是 root 带来的最大稳定性收益 |
| F8 | Claude Code 的配置与状态全是文件：`~/.claude/settings.json`（用户级）、`.claude/settings.json`（项目级）、`~/.claude.json`（登录态 + MCP）、`~/.claude/.credentials.json`（OAuth token）、`~/.claude/projects/<slug>/<session>.jsonl`（会话记录，无签名、无完整性校验） | 19 章 §5–§6；08 章；SECURITY_AUDIT §2.3 | 把 `HOME` 指向应用私有目录就能整体收容；凭证文件必须 0600 且排除在备份外 |

---

## 3. 架构选型

### 3.1 推荐主架构 A：APK + Debian rootfs（proot/chroot）+ 官方原生二进制

```
┌─────────────────────────────────────────────────────────────────┐
│ APK（Kotlin，targetSdk 36，minSdk 29）                            │
│  ┌──────────────┐  ┌──────────────┐  ┌───────────────────────┐  │
│  │ 终端 UI       │  │ 前台服务      │  │ Root 桥（su 包装器）    │  │
│  │ （终端仿真器   │  │ （保活+通知   │  │ 检测 su / 发 root 命令  │  │
│  │  + 键盘/手势） │  │  + WakeLock） │  │ 黑名单过滤 / 审计日志   │  │
│  └──────┬───────┘  └──────┬───────┘  └───────────┬───────────┘  │
│         │ PTY            │                       │ su -c        │
│  ┌──────▼───────────────────────────────────────────────────┐  │
│  │ 引导器 libbootstrap.so（jniLibs，nativeLibraryDir）        │  │
│  │ 检测 root → 有 root 用 chroot，否则 proot；挂载 /proc /dev   │  │
│  │ /sys、bind /sdcard 与工作目录；设 HOME/PATH/TERM            │  │
│  └──────┬───────────────────────────────────────────────────┘  │
│         │                                                       │
│  ┌──────▼───────────────────────────────────────────────────┐  │
│  │ Debian arm64 rootfs（首次启动下载，~/files/rootfs/）         │  │
│  │  bash / git / coreutils / curl / node 22+ / python3         │  │
│  │  └─ ~/.local/bin/claude（官方 linux-arm64 原生二进制）       │  │
│  │  └─ HOME=/root 或 /home/user → .claude/ 全套配置与会话        │  │
│  └───────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

**为什么选它：**

- **唯一跑得动官方原版二进制的路线**（F1+F4）。官方二进制 = 客户端证明完整、自动更新完整、功能最新。不改包、不重打包、不碰 attestation，RISK-2 最小。
- **root 是增强层，不是地基**。proot 不需要 root 就能跑（ptrace 用户空间隔离），所以没 root 时 Claude Code 照常工作；有 root 时引导器改走 chroot（更快、更稳、真 UID 0 上下文），并解锁 §5 的玩法。临时 root 消失时自动降级回 proot（RISK-3）。
- **Claude Code 要的 Unix 用户空间一次给齐**。它不只是一个二进制——Bash 工具要调 `bash/sh/git/grep/rg`，hook 要跑 shell 脚本，MCP stdio 服务器要跑 Node/Python。Debian rootfs 一次性提供全套，比在 bionic 上逐个补工具链干净得多。
- **隔离天然存在**。rootfs 就是沙盒边界：Claude Code 的读写默认只发生在 rootfs 内 + 明确 bind 进来的目录（工作区、`/sdcard` 指定目录），§6 的“system 不动”在挂载层面就先收了一道。

**代价**：首次启动需下载 rootfs（约 150–300 MB）+ Claude 二进制（约 250–320 MB），且 proot 有 ptrace 开销（约 10–30% 性能损耗，chroot 模式无此损耗）；APK 构建与真机验证工作量最大。

### 3.2 备选架构 B：bionic 路线（Termux prefix + 旧版纯 JS Claude Code ≤2.1.112 + Node 24 LTS）

- 做法：APK 内嵌 Termux bootstrap（bionic 用户空间）+ Termux 的 `nodejs-lts`（24.x，满足 npm 的 Node 22+ 要求）+ 钉死 `npm install -g @anthropic-ai/claude-code@2.1.112`（最后一个纯 JS 版），`node` 二进制以 `libnode.so` 形态放 `jniLibs` 落盘到 `nativeLibraryDir` 执行（F5 的合规做法）。
- 优点：全 bionic，无 glibc 问题；APK 体积小（约 70–130 MB，见 §7）；Termux 生态现成（git、python、bash 都有包）。
- 缺点（致命级）：版本冻结在 2025 年的 2.1.112，缺失一年以上的新功能与安全修复；旧版可能过不了客户端证明 / 被服务端拒绝；`~/.claude` 行为与新版有差异。**只建议作为“主架构验证失败时的演示/过渡品”，不建议当正式路线。**

### 3.3 备选架构 C：glibc loader 垫片（bun-termux 式）+ Termux 用户空间混搭

- 做法：把 glibc 的 `ld-linux-aarch64.so.1` + `libc.so.6` + `libstdc++.so.6` + `libgcc_s.so.1` 等以 `.so` 形态放 `jniLibs`，写一个 bionic 小 stub 做 userland exec（mmap 动态链接器、重建栈与 auxv、跳转入口），让官方二进制在 Termux 用户空间里跑起来。bun-termux 项目已证明该模式可行（wrapper.c + `bunfs_shim.so`，并用 `LD_PRELOAD` 把被 Android 屏蔽的 `openat2`/`fchmodat2` 转成 `ENOSYS`）。
- 优点：比主架构轻（约 200 MB），不用整个 rootfs。
- 缺点：脆弱——Claude 二进制依赖的库要逐个补（NSS、libresolv、librt……），每次官方升级都可能新增依赖；还要处理 Termux 与 glibc 两套工具链混用。**列为技术储备，不首选。**

### 3.4 备选架构 D：套壳 Termux（最快原型）

- 做法：直接复用 Termux 应用做底座（它的终端 + 包管理 + bootstrap），在其上装 Node 与旧版 JS Claude Code。优点是几天就能跑通演示；缺点是 Termux 应用是 GPL-3.0，合规要求公开源码，且形态上等于“给 Termux 加了个插件”，不是独立产品。**只用于 RISK-1 的前置验证（§9.3 V0），不作为交付形态。**

### 3.5 对照表

| 维度 | A 主架构（推荐） | B bionic+旧版 JS | C loader 垫片 | D 套壳 Termux |
|---|---|---|---|---|
| 跑官方最新二进制 | ✅ | ❌（钉死 2.1.112） | ✅ | ❌ |
| 客户端证明完整 | ✅ | ❌ 未知 | ⚠️ 大概率 ✅ | ❌ 未知 |
| 无 root 可用 | ✅（proot） | ✅ | ✅ | ✅ |
| 有 root 收益 | chroot + §5 全玩法 | §5 全玩法 | §5 全玩法 | §5 部分 |
| APK/首次下载体积 | 约 500–700 MB（首次下载） | 约 70–130 MB | 约 200 MB | 约 50 MB + 应用商店装 Termux |
| 性能 | proot 慢 10–30%，chroot 原生 | 原生 | 原生 | 原生 |
| 维护成本 | 中（rootfs 更新） | 低但版本冻结 | 高（逐库排障） | 低 |
| 许可证注意 | Debian 自由软件 ✅ | Termux GPL 注意 | 无 | GPL-3.0，必须开源 |

---

## 4. Claude Code 在手机上的运行方式

### 4.1 启动命令

rootfs 内（`HOME` 已指向应用私有目录映射后的位置，见 §4.3）：

```bash
# 交互式（终端 UI 里跑的命令）
claude
# 指定工作目录启动
claude --add-dir /workspace/<项目> /workspace/<项目>
# 非交互（后台任务 / 自动化脚本调）
claude -p "把 /workspace/notes 里今天的待办整理成清单" --output-format stream-json
# 指定模型
claude --model sonnet
# 危险：root 身份下绝对不要加 --dangerously-skip-permissions（会被 setup.ts 门禁直接退出，见 F6）
```

APK 侧拉起流程（伪代码级描述，不贴完整实现）：

1. 前台服务启动 → 引导器检查 root（`su -c id` 返回 `uid=0` 即有 root）→ 选择 chroot（有 root）或 proot（无 root）。
2. 挂载：`mount --bind /proc /dev /sys`（chroot 模式，root）；bind 工作目录与 `/sdcard/Android/data/<包名>/files` 进 rootfs。
3. 在 rootfs 内以 PTY 拉起 `claude`，终端 UI 接管 stdin/stdout/stderr。
4. 会话结束或 root 消失 → 杀掉 PTY、落盘会话状态、按 §5-R14 降级。

### 4.2 认证方式

| 方式 | 怎么做 | 备注 |
|---|---|---|
| **OAuth（主推）** | 手机上跑 `claude login` → 打印授权 URL → APK 用 Custom Tabs 在用户自己的浏览器里打开 → 用户在浏览器里登录 Anthropic 账号并授权 → 回调到 `http://127.0.0.1:<随机端口>/callback`，由 APK 内嵌的 localhost 监听器接住 code → 换 token 存入 `~/.claude/.credentials.json` | 登录只在用户自己的浏览器里完成，APK 不碰账号密码；localhost 回调在 Android 上行得通（Termux 里同款流程已有人跑通）。若 Custom Tabs 拦截不到回调，退到“用户手动把 code 粘回终端”的备用流程（待真机验证） |
| **API key（备用）** | 用户在 APK 的安全输入框里贴 `ANTHROPIC_API_KEY`（只进内存 + 写进 `~/.claude/settings.json` 的 `env` 块，文件 0600），或设 `ANTHROPIC_AUTH_TOKEN` 走中转 | key 绝不在聊天记录、日志、备份里出现；`settings.json` 的 `env` 块优先级高于 shell 环境变量（08 章） |
| 中转/BASE_URL | `ANTHROPIC_BASE_URL` + `ANTHROPIC_AUTH_TOKEN`，同样放 `settings.json` 的 `env` 块 | 注意 05 章 §10 的隐写标记事件：`ANTHROPIC_BASE_URL` 指向非官方地址且时区为 `Asia/Shanghai`/`Asia/Urumqi` 时曾触发标记实验——本机时区是 `Asia/Hong_Kong`，不在该事件的触发条件里，但仍建议优先用官方地址 |

OAuth token 寿命与刷新由 Claude Code 自己管（401 时清缓存、强制刷新后重试，见 08 章 §7.1）。APK 不实现自己的刷新逻辑。

### 4.3 工作目录与配置文件位置

`HOME` 指向应用私有目录（rootfs 内映射），全部状态收进一处：

| 逻辑路径（rootfs 内） | 实际落盘位置（Android 侧） | 内容 |
|---|---|---|
| `$HOME/.claude/` | `getFilesDir()/home/.claude/`（经 bind 进 rootfs） | `settings.json`、`CLAUDE.md`、skills/agents/commands、会话记录 |
| `$HOME/.claude.json` | 同上 | 登录态、MCP 服务器配置、项目表 |
| `$HOME/.claude/.credentials.json` | 同上，**0600，排除在备份外** | OAuth token（最敏感文件） |
| `$HOME/.claude/projects/<slug>/<session>.jsonl` | 同上 | 会话记录（无签名，见 F8——只放私有目录，不共享、不上传） |
| `$HOME/.local/bin/claude` | `getFilesDir()/bin/` | 官方原生二进制 |
| 工作区 `/workspace/<项目>` | 默认 `getExternalFilesDir()/workspace/<项目>`（即 `/sdcard/Android/data/<包名>/files/workspace/<项目>`），用户可改到 `/sdcard` 上任何自有目录 | 用户代码、笔记、Claude 的工作现场 |
| 临时目录 | rootfs 内 `/tmp` + `$HOME/.claude/` 下的 `claude-<uid>` 临时目录（Unix 按 uid 隔离，root 是 `claude-0`，root 可读其他用户——本机单用户，无实际问题） | 会话临时文件 |

**关键**：`HOME` 不指向 Android 的 `/data/data/<包名>` 根目录本身，而是指向其下的 `home/` 子目录，避免 Claude Code 的扫描逻辑（如 skill 目录扫描、gitignored 判断）与 APK 自身文件互相干扰。

### 4.4 需要的环境变量（写进引导器的 env，不依赖用户 export）

| 变量 | 值 | 作用 |
|---|---|---|
| `HOME` | rootfs 内映射的应用私有 home | 决定 `.claude/` 落点（§4.3） |
| `PATH` | `/usr/local/bin:/usr/bin:/bin:$HOME/.local/bin`（rootfs 内） | 让 Bash 工具找到 git/bash/ rg/node |
| `TERM` | `xterm-256color` | 终端 UI 的颜色与控制序列 |
| `LANG` / `LC_ALL` | `C.UTF-8` | 中文输入输出不乱码 |
| `SHELL` | `/bin/bash` | 默认 shell（Android 的 `platform.ts` 判定为 linux，走 Bash 工具） |
| `ANTHROPIC_API_KEY` / `ANTHROPIC_AUTH_TOKEN` / `ANTHROPIC_BASE_URL` / `ANTHROPIC_MODEL` | 按 §4.2，用户配置时才设 | 认证与模型覆盖 |
| `CLAUDE_CODE_MAX_TOOL_USE_CONCURRENCY` | `4`（手机上从默认 10 下调） | 只读工具并行上限，省内存、省电（04 章 §4） |
| `CLAUDE_CONFIG_DIR` | 待验证是否支持；若支持则指向 `$HOME/.claude`，否则靠 `HOME` 兜底 | 配置目录显式指定 |

**禁止设置的变量**：`IS_SANDBOX`、`CLAUDE_CODE_BUBBLEWRAP`（伪造沙箱骗门禁，见 F6）、`CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC` 以外的遥测开关不建议动（遥测默认开着，837 个 `tengu_` 事件；如用户要求关，改 `settings.json` 的遥测项，不用环境变量硬关）。

### 4.5 权限模式与 root 的配合（核心安全设计）

- **交互式 root 会话**：权限模式固定 `default`。危险操作逐条问人，终端 UI 弹出批准/拒绝。`--dangerously-skip-permissions` 在 root 下会被门禁退出，APK 侧直接禁止拼接该 flag（代码级硬禁 + 启动自检）。
- **后台无人值守任务**（定时整理、备份、续期等）：必须用**非 root UID**跑（proot 模式 + app UID），此时 bypass 允许；权限靠 `settings.json` 的 `permissions.allow/deny/ask` 规则 + `permissionPrompts: 'none'`（无人时该问的一律拒绝）预先框死。root 能力在后台任务里**只通过白名单内的单条 `su -c` 调用**使用，不整进程提权。
- **沙箱**：Android 上没有（F6），不配 `sandbox.enabled`，也不配 `failIfUnavailable`（配了会直接拒绝启动）。权限门 + hook（§6）就是全部防线，如实告诉用户，不假装有沙箱。
- **MCP 服务器**：只接用户自己写的本地 MCP（stdio，跑在 rootfs 内）；不接来路不明的远程 MCP（MCP 的 `instructions` 直塞系统提示词、无任何清洗，SECURITY_AUDIT §4——接一个恶意 MCP 等于把 root 交出去）。

### 4.6 终端 UI

- 终端仿真器：复用 Termux 的终端库（GPL-3.0，合规要求见 §7.4）或自研最小 VT100 解析器 + Jetpack Compose 渲染；首选复用（已验证、支持手势/选中/复制）。
- 键盘：系统输入法 + 底部快捷键栏（Tab、Ctrl、Esc、方向键、`/` 常用命令），因为手机上敲 `Ctrl+C`/`Esc` 是刚需。
- 会话管理：多标签（一个标签一个 `claude` 进程 + 一个 PTY）；标签页状态在前台服务里，App 被滑掉不丢会话。
- 通知：后台任务完成 / 需要批准时发系统通知，点通知回到对应标签。

---

## 5. Root 玩法清单（16 条，只针对用户自己的设备）

通用规则：每条玩法默认关闭或默认只读；写操作一律要用户在终端里明确确认一次（或写进 `settings.json` 的 allow 规则）；所有 root 命令走 APK 的 su 包装器（黑名单过滤 + 审计日志，见 §6.3）；**任何一条标注“碰 system”的都不做**。

| # | 玩法 | 做什么 | 怎么实现 | 风险 | 碰 system？ |
|---|---|---|---|---|---|
| R1 | 幽灵进程杀手自灭 | 关掉 Android 12+ Phantom Process Killer，让 Claude 的子进程（Bash、MCP、hook）不再被无声 SIGKILL；同时把 APK 加进电池优化白名单 | root 下执行 `settings put global settings_enable_monitor_phantom_procs false`；`dumpsys deviceidle whitelist +<包名>`；配合前台服务 + WakeLock | OTA 可能重置该设置，需每次启动自检并重设；关掉后后台耗电略增 | 否（`settings` 全局设置，不在 system 分区） |
| R2 | Root Shell 模式 | Claude Code 的 Bash/Read/Write/Edit 全部以 root 身份执行：能读任何自有目录、能调任何系统命令 | 引导器以 `su -c` 拉起整个 claude 进程（chroot 模式）；权限模式锁死 `default`（F6，bypass 会被门禁退出） | 注入即 root 执行（RISK-5）；只在交互会话开，绝不在后台任务开 | 否（进程身份变化，不写 system） |
| R3 | 全盘工作区 | 工作区不再局限于应用私有目录：Claude 可以整理 `/sdcard` 全域、读取自己其他 app 的私有数据做备份/迁移 | root 下 bind mount：把 `/sdcard/Android/data/<包名>/files/workspace` 映射为 rootfs 内 `/workspace`；读其他 app 私有目录时只读用户自己的数据 | 误读/误删别的 app 数据——限定为用户明确指定的 app 与目录；写操作逐条确认 | 否（bind mount 不写分区） |
| R4 | 手机体检 | 一句话生成整机健康报告：电量/温度/内存/存储/后台耗电排行/权限索取排行 | SessionStart hook 或 skill `/doctor-phone` 跑 `dumpsys battery/meminfo/netstats`、`getprop`、`cat /proc/meminfo`、`cmd appops`，把结果以 additionalContext 注进会话 | logcat 含其他 app 的日志碎片——只取本机系统级统计，不主动翻别的 app 的日志内容 | 否（只读） |
| R5 | 流量与抓包分析 | 看今天哪个 app 最耗流量；对自家流量抓包定位异常连接 | `dumpsys netstats` 做日报；`tcpdump -i any -w /sdcard/.../cap.pcap`（root）抓包后让 Claude 分析（只分析用户自己的设备流量） | 抓包文件含敏感信息——只存私有目录、分析完即删，不上传 | 否（只读 + 写私有目录） |
| R6 | 本机防火墙 | 按 app 断网/放行：比如“让某 app 只能走 Wi-Fi”“睡觉时断掉某 app 的后台联网” | root 下 `iptables -A OUTPUT -m owner --uid-owner <uid> -j DROP` 或 `nft` 规则；规则文件存在私有目录，开机/启动时重放 | 规则写错会断网——每条规则先 `iptables -C` 查重、提供一键清空；只动 OUTPUT 链，不动系统自带规则 | 否（iptables 规则在内存，不写 system 分区） |
| R7 | 备份管家 | 完整备份自己 app 的数据（含私有目录），恢复时一键还原 | root 下 `tar -czf /sdcard/Android/data/<包名>/files/backup/<app>-<日期>.tar.gz /data/data/<app>`；恢复时解回；用 Claude 做“哪些 app 该备份、备份是否成功”的管家 | 备份含账号 token——加密后存放（用户口令派生密钥），不上传云端 | 否（读写 `/data/data/<自有 app>` 与 `/sdcard`） |
| R8 | 自然语言手机操作 | “把微信冻结”“亮度调到最低”“打开勿扰”“截个屏发给我”——Claude 直接操作手机 | root 下 `pm disable-user <包>`、`settings put system screen_brightness 1`、`cmd notification`、`input tap/swipe/keyevent`、`screencap -p`；每条操作映射成 skill，执行前回显命令给用户确认 | 误操作（如冻结错 app）——冻结/卸载类操作默认二次确认；`pm` 操作限定用户点名的包 | 否（`pm`/`settings`/`input` 是运行时状态，不写 system 分区） |
| R9 | 应用冻结 / 系统 app 瘦身 | 冻结不用的系统 app（停用而非删除），释放后台与内存；随时可恢复 | `pm disable-user --user 0 <包>`；恢复 `pm enable <包>`；**不用 `pm uninstall --user 0` 除非用户逐个点名**（卸载仍是可逆的 `pm install-existing`，但风险更高） | 冻错关键组件（如系统 UI、电话）会导致功能异常——内置“别碰”清单（launcher、systemui、telephony 等），命中直接拒绝 | 否（`pm disable` 只改用户级启用状态，`/system` 里的 APK 文件原样不动） |
| R10 | 性能调控 | 看 CPU 调频/温度/内存压力；需要时降温限频或清内存 | 读：`/sys/devices/system/cpu/cpu*/cpufreq/scaling_governor`、`/sys/class/thermal/`、`/proc/pressure/`；写（默认关闭、二次确认）：`scaling_governor`、`echo 3 > /proc/sys/vm/drop_caches` | 写错 governor 会卡顿/发热——只提供预设档位（省电/均衡/性能），不开放自由写 `/sys` | 否（`/sys` 与 `/proc/sys` 是运行时内核接口，不在 system 分区；但写操作仍需二次确认） |
| R11 | 权限审计 | 找出过度索权的 app：谁在后台读位置、录音、读剪贴板 | `cmd appops --op ...`、`dumpsys appops`、`dumpsys permission` 拉清单；Claude 出报告；用户点头后 `pm revoke <包> <权限>` 收回 | 误收权限导致 app 罢工——只建议、不自动执行，逐条确认 | 否（运行时权限状态） |
| R12 | 任务完成通知 | 后台任务跑完（备份完成、整理完成）发系统通知，点通知回终端看结果 | 前台服务 + `NotificationManager`；Claude 通过 hook/脚本调 APK 的通知接口（本地 socket 或 `am broadcast` 到 APK 的 receiver） | 无实质风险 | 否 |
| R13 | 存储与挂载 | 把工作区 bind 到 `/sdcard` 显眼位置方便文件管理器查看；挂载 OTG U 盘 / tmpfs 高速暂存 | root 下 `mount --bind <私有目录> /sdcard/ClaudeCode`、`mount -t tmpfs`；卸载用 `umount` | 挂载点残留——退出时自动 umount 清理；绝不挂载到 `/system`、`/vendor` 之下 | 否（bind/tmpfs，不写 system） |
| R14 | Root 自检与消失降级 | 每次启动与每小时自检 root 状态；root 消失（重启、GhostLock 失效）自动降级、不断会话 | 检测 `su -c id`、`getenforce`、KernelSU 状态；root 消失 → 杀掉 root 上下文的 claude 进程 → 用 proot 在 app UID 下重起会话 → 发通知告诉用户“已降级为非 root 模式” | 降级瞬间正在跑的 root 命令会失败——失败的命令记进审计日志，用户回来可重跑 | 否（只读检测 + 进程管理） |
| R15 | 手机做 MCP 工具端（二期） | PC/服务器上的 Claude Code 通过 MCP 连手机，让手机执行 root 动作：手机变成一台“带 root 的工具服务器” | APK 内跑一个本地 MCP 服务器（stdio over 本地 socket 或 HTTP loopback），工具集 = R1–R14 的受控封装；PC 端 `.mcp.json` 配 `url: http://<手机局域网 IP>:<端口>/mcp`；鉴权用一次性 token + 局域网限定 | MCP 指令直塞系统提示词（SECURITY_AUDIT §4）——工具只暴露白名单动作；局域网外不可达；token 每次启动轮换 | 否（网络服务在应用层） |
| R16 | 通知与剪贴板桥（待验证） | Claude 读用户自己的通知内容、剪贴板，做“刚才那条验证码帮我填一下”这类接力 | `NotificationListenerService`（免 root，走系统授权）+ 剪贴板读取（Android 10+ 后台受限，root 辅助）；**先验证再承诺**，验证失败就砍掉 | 通知与剪贴板含敏感信息——只在用户当面触发时读，不后台常驻监听 | 否 |

**明确不做的“脏玩法”（红线外但主动排除）**：改 `/system/etc/hosts` 做广告过滤（要 remount system rw，红线）——替代方案是应用层 VPNService 做本地 DNS 过滤；改 `build.prop`（`/system/build.prop`，红线）——不做；删 `/system` 里的 APK 文件（红线）——只用 R9 的用户级停用；`resetprop` 改系统属性（依赖 Magisk 内存补丁，不做）；任何形式的系统分区 remount（`mount -o remount,rw /system`，一律拒绝，见 §6.3）。

---

## 6. System 分区不动的保证方案

### 6.1 写入白名单（只允许写这些地方）

| 位置 | 用途 |
|---|---|
| `/data/data/<包名>/`（应用私有目录，rootfs 内映射为 `$HOME` 等） | 全部应用状态：rootfs、二进制、配置、会话、日志 |
| `/sdcard/Android/data/<包名>/`（`getExternalFilesDir`） | 工作区、备份、抓包文件（用户能在文件管理器里看见） |
| `/data/local/tmp/` | 临时中转（重启清空；root 可写） |
| rootfs 内 `/tmp`、`/workspace`、`/root`、`/home/user` | Claude Code 的工作现场 |
| 用户明确指定的 `/sdcard` 下自有目录（如 `/sdcard/Download/ClaudeCode`） | 用户点名的工作区扩展 |
| 运行时状态（非文件）：`settings` 全局设置、`iptables` 规则、`pm` 用户级状态、`input`/`am`/`cmd` 调用 | R1/R6/R8/R9 的操作面——改的是内存与运行时数据库，不是分区内容 |

### 6.2 禁止路径清单（命中即拒绝，三层防线都认同一份清单）

```
绝对禁止（写、删、改、remount、bind 覆盖一律禁止）：
  /system /system/* /vendor /product /odm /oem /system_ext
  /apex /etc（/system/etc 的 symlink） /init /init.* /boot /recovery
  /dev/block/*（块设备直写） /metadata
  /data/system（系统服务数据库：读可做诊断，写禁止）
  /data/misc（同上：读可做诊断，写禁止）
  任何含 "remount" + ("system"|"vendor"|"product") 的命令
  任何含 "mount -o remount,rw" 且目标不是 tmpfs/私有目录的命令

默认禁止、写操作需二次确认（不在自动流程里出现）：
  /sys/**（除 R10 预设档位外的写） /proc/sys/**（除 drop_caches 预设外）
  /data/data/<其他包名>/** 的写（R7 备份恢复时除外，且只限用户点名的包）
```

### 6.3 三层防线

| 层 | 位置 | 机制 |
|---|---|---|
| L1 APK 层 | su 包装器（所有 root 命令的唯一出口） | 命令先过黑名单正则（§6.2 清单）+ 白名单动作表；命中直接拒绝并记审计日志；`su` 交互 shell 只接受包装器拼好的命令，不接受 Claude 原样透传的任意字符串 |
| L2 Hook 层 | Claude Code 的 `PreToolUse` hook（`settings.json` 的 `hooks` 键） | hook 跑在权限门之外，是真正的硬拦截：Bash 命令含禁止路径 → 返回 `permissionDecision: deny` + 告诉模型“这条路被封了，不要绕”；hook 脚本本身放在私有目录、只读挂载，Claude 改不了它 |
| L3 规则层 | `settings.json` 的 `permissions.deny` | `deny` 规则：`Bash` 访问禁止路径、`Write`/`Edit` 写禁止路径；deny 恒胜 allow（05 章 §2）；bypass 模式下写 `.git`/`.claude`/`.bashrc` 仍会问人（1g safety check，bypass-immune，见 SECURITY_AUDIT §2.1）——三层里最弱的一层，只当兜底 |

三层互相独立：即使某一层被绕过（例如模型用 `su -c` 拼新命令），另外两层仍在。审计日志（每条 root 命令：时间、命令、结果、触发会话）写进私有目录，用户随时可查；日志本身 0600。

### 6.4 启动自检（每次冷启动跑一遍，不过就降级或拒绝启动）

1. `mount | grep ' /system '` 确认 `/system` 是 `ro`；若是 `rw` → 拒绝进入 root 模式并告警（说明别的工具动过 system）。
2. `su -c id` 确认 root 是否在；`getenforce` 记录 SELinux 状态。
3. 校验 rootfs 与 claude 二进制的 SHA256（首次下载时记录，之后每次启动比对）。
4. 检查 `settings.json` 的 deny 规则与 hook 脚本是否被改过（改过 → 恢复默认并告警）。
5. 检查 phantom killer 设置是否被 OTA 重置（R1），是则重设。

---

## 7. 构建依赖与体积预估

### 7.1 构建依赖（本机 VM 可装，见 §8.1）

| 依赖 | 版本 | 说明 |
|---|---|---|
| JDK | 17（Temurin 优先） | AGP 8.x 要求 JDK 17；本机 VM 现无 Java，需安装 |
| Android SDK | cmdline-tools + platform-tools + platforms;android-36 + build-tools 36.0.0 | compileSdk 36（Android 16），targetSdk 36，minSdk 29 |
| Gradle | 8.7+（Wrapper 锁定版本） | 与 AGP 配对 |
| Android Gradle Plugin | 8.5+ | Kotlin DSL（`build.gradle.kts`） |
| Kotlin | 2.0+ | APK 主语言 |
| NDK | r27（仅当需要编译引导器 stub 时；proot 与 loader 优先用预编译产物） | 引导器若用 C 写则需要 |
| 终端库 | Termux terminal-view/emulator（GPL-3.0）或自研 | 见 §4.6 |
| Node.js（构建机） | 22+（本机 VM 已有 24.22.1，见 §8.1） | 仅用于构建期工具（如打包脚本），不进 APK |

### 7.2 体积预估

| 部分 | 大小 | 备注 |
|---|---|---|
| APK 本体（Kotlin + 终端库 + 引导器 + 界面） | 约 8–15 MB | 不含 rootfs 与 claude 二进制 |
| 引导器/loader（`libbootstrap.so`、`libproot.so` 等，放 `jniLibs`） | 约 5–10 MB | 必须以 `.so` 形态落盘到 `nativeLibraryDir`（F5） |
| Debian arm64 rootfs（minbase + bash/git/curl/node/python3） | 约 150–300 MB | 首次启动下载，不进 APK；用 `debootstrap --variant=minbase` 或官方 rootfs tarball |
| Claude Code 官方 linux-arm64 原生二进制 | 约 250–320 MB（官方 2.1.318 实测约 320 MB） | 首次启动从官方渠道下载并校验 SHA256；之后走官方自更新（`~/.local/share/claude/versions/`） |
| 会话与配置（随用随长） | 初期 <50 MB；桌面实测数月可长到约 2.2 GB（会话 .jsonl 为主） | 私有目录需做自动清理（只留最近 30 天会话，见 §8.3） |
| **合计** | **APK 约 15–25 MB；首次启动后设备占用约 450–700 MB** | 备选 B 约 70–130 MB；备选 C 约 200 MB（见 §3.5） |

### 7.3 首次启动下载清单（全部走官方/可信源，校验 SHA256）

1. Debian rootfs：Debian 官方 rootfs 或 `debootstrap` 在构建机上生成后放自有对象存储（推荐后者：构建期锁定版本，哈希写进 APK）。
2. Claude Code linux-arm64：官方分发（`claude.ai/install.sh` 同源的版本清单接口，`@anthropic-ai/claude-code-linux-arm64`），不走第三方镜像（05 章 §9 的 x402 篡改镜像教训：只认官方源 + SHA256）。
3. Termux Node（仅备选 B）：Termux 官方 `.deb`。

### 7.4 许可证注意

- Termux 终端库与 bootstrap：GPL-3.0——用了就要按 GPL 要求提供源码（APK 内放许可证 + 源码获取方式）。
- Debian rootfs：内含大量自由软件，按各包许可证保留声明即可。
- Claude Code 二进制：Anthropic 专有，按其条款使用，不逆向、不重打包、不转售。

---

## 8. 测试方案

### 8.1 本机 VM 里能测什么（已验证本机环境）

本机 VM 实测：Node v24.22.1、npm 10.9.4 可用；Java 未安装（需装 JDK 17）；磁盘 148 GB 可用；内存 7.7 GB；x86_64（**不能直接跑 aarch64 二进制**，需 `qemu-user-aarch64-static` 或真机）。

| 测试 | 怎么做 | 通过标准 |
|---|---|---|
| T-VM-1 构建链 | 装 JDK 17 + Android SDK + Gradle，编一个空壳 APK（终端 UI + 前台服务 + su 包装器桩） | `./gradlew assembleDebug` 通过，APK 装包体积符合 §7.2 |
| T-VM-2 路径守卫单元测试 | L1 包装器黑名单（§6.2）与 L2 hook 脚本的纯 JVM 单元测试：喂 100+ 条命令（含绕过变体：`//system`、`/system/../system`、symlink、Unicode 混淆、`;` 拼接、`\r` 注入） | 全部禁止路径被拦，合法命令全放行；重点回归 05 章 §6 的 `\r` 解析器差异 |
| T-VM-3 rootfs 构建 | `debootstrap --variant=minbase` 生成 arm64 rootfs（x86_64 宿主 + qemu-user-static），装 bash/git/node，算 SHA256 | rootfs <300 MB，`chroot` 进去 `git --version`、`node --version` 正常（qemu 下） |
| T-VM-4 Claude 二进制静态检查 | 下载官方 linux-arm64 包，`readelf` 看动态链接器与依赖库清单，确认 rootfs 里都有；核对 SHA256 与官方清单一致 | 依赖闭合；无第三方镜像 |
| T-VM-5 SDK/CLI 行为模拟 | x86_64 Linux 上跑官方 linux-x64 二进制（或 SDK），测启动、权限门、hook、settings 级联、会话落盘 | 权限模式切换、deny 规则、PreToolUse hook 拦截均按 05 章行为工作 |
| T-VM-6 终端 UI | 模拟器/桌面端测 VT100 渲染、快捷键栏、多标签切换 | 中文不乱码、Ctrl+C/Esc 可用 |

### 8.2 只有手机上才能测的

| 测试 | 怎么做 | 通过标准 |
|---|---|---|
| T-PH-1 RISK-1 最小验证（V0，先做） | 方案 D：Termux 里 proot 跑 Debian rootfs + 官方 linux-arm64 二进制，`claude --version`，再跑 `claude -p "1+1=?"`（需先登录） | 二进制能启动、能调 API、工具能跑；失败则主架构推倒重来 |
| T-PH-2 W^X 验证 | 确认 `nativeLibraryDir` 里的 `.so` 可执行、`getFilesDir()` 里 `chmod +x` 的文件报 error=13 | 与 F5 一致 |
| T-PH-3 root 门禁 | root 下 `claude --dangerously-skip-permissions` 应直接退出；默认模式正常 | 与 F6 一致 |
| T-PH-4 su 调用 | `su -c id` 返回 uid=0；交互式 `su` + DataOutputStream 长会话稳定；KernelSU 授权弹窗只弹一次 | su 包装器稳定工作 |
| T-PH-5 16 KB 页大小 | `getconf PAGESIZE`；rootfs 内二进制与 `.so` 能否 exec（不对齐会直接起不来） | 全部能 exec；否则重编/换对齐的构建 |
| T-PH-6 Phantom Killer | 后台挂 40 个子进程，看是否被杀；再执行 R1 的关闭命令，看是否不再被杀 | R1 生效；OTA 重置后自检能发现 |
| T-PH-7 OAuth 登录 | `claude login` → Custom Tabs → localhost 回调接住 code → token 落盘 | 全程不用电脑；token 在 `.credentials.json` 且 0600 |
| T-PH-8 ColorOS 后台 | 锁屏/切后台 30 分钟，前台服务 + WakeLock 下 claude 进程是否存活；ColorOS 的“后台冻结/耗电保护”是否要手动关 | 给出明确的系统设置清单（电池优化“无限制”、允许后台、锁定任务） |
| T-PH-9 真实使用 | 手机上跑一天：写代码、整理文件、R4 体检、R7 备份、R8 自然语言操作 | 无闪退、无会话丢失、耗电可接受 |
| T-PH-10 root 消失降级 | 重启手机（root 消失）→ 打开 APK | 自动降级 proot 模式 + 通知，不崩溃、不丢会话 |

### 8.3 验收清单（交付前逐项打勾）

- [ ] V0（T-PH-1）通过：官方二进制在 rootfs 里能跑、能登录、能调 API。
- [ ] root 下 bypass 被拒、默认模式正常（T-PH-3）。
- [ ] `mount | grep ' /system '` 全程 `ro`；审计日志里无任何写 system 记录。
- [ ] 三层防线单元测试全过（T-VM-2），且真机上实测拦截 `rm -rf /system` 等 10 条危险命令。
- [ ] 锁屏 30 分钟会话不死（T-PH-8）；root 消失降级不丢会话（T-PH-10）。
- [ ] 首次启动下载可断点续传、SHA256 校验失败拒绝安装。
- [ ] `.credentials.json` 0600 且不在任何备份/同步范围里。
- [ ] 会话自动清理（只留 30 天）生效，存储不无限膨胀。

---

## 9. 风险总表与未验证清单

### 9.1 风险总表

| 风险 | 等级 | 说明 | 状态 |
|---|---|---|---|
| RISK-1 官方二进制跑不起来 | 致命 | 16 KB 页对齐 / 缺库 / attestation | V0 实验前置（T-PH-1） |
| RISK-2 登录/attestation 被拒 | 高 | 客户端证明、OAuth 封禁传闻（未验证） | 只用官方原版 + 用户自有浏览器登录 |
| RISK-3 临时 root 中途消失 | 高 | GhostLock 特性，重启即失 | R14 自动降级 |
| RISK-4 后台被杀 | 高 | Phantom Killer + ColorOS | R1 + 前台服务 + 白名单 |
| RISK-5 提示注入直达 root | 高 | 权限门不裁决意图来源（SECURITY_AUDIT §6） | 默认模式 + 三层防线 + 高危玩法默认关 |
| RISK-6 体积与下载 | 中 | 首次 450–700 MB | 首次下载 + 断点续传 + 哈希校验 |
| RISK-7 ColorOS 厂商限制 | 中 | OPPO 后台管理比原生 Android 更激进 | T-PH-8 实测后出设置清单 |
| RISK-8 账号风险 | 中 | OAuth 账号若被判定“非常规客户端”可能受限 | 不改包、不伪造客户端标识；出问题立即停用并走 API key 路线 |

### 9.2 不做的事（再强调一次）

- 不动 system 分区（§6）。
- 不伪造 `IS_SANDBOX` / `CLAUDE_CODE_BUBBLEWRAP` 骗 root 门禁。
- 不用第三方/泄露源码镜像（x402 篡改教训），不从源码重建 Claude Code。
- 不把 token、密码、身份证、卡号写进聊天、日志、文件、备份。
- 不碰别人的设备与数据；R3/R7 只读用户自己的 app 与数据。

### 9.3 未验证清单（开工前必须逐项验证，不许默认“应该可以”）

1. V0：官方 linux-arm64 二进制在真机 proot/chroot 里能否启动并调通 API（T-PH-1）。
2. `CLAUDE_CONFIG_DIR` 环境变量是否被当前版本支持。
3. OAuth localhost 回调在 ColorOS + Chrome Custom Tabs 下能否被 APK 接住；手动粘贴 code 的备用流程是否存在。
4. Reno12 的内核页大小（`getconf PAGESIZE`）与 16 KB 对齐影响。
5. Termux `nodejs-lts` 的确切版本与 `libnode.so` 形态打包的可行性（仅备选 B 需要）。
6. `claude login` 在 rootfs 内是否需要额外的 `xdg-open` / 浏览器调用链。
7. ColorOS 16 的后台冻结细节与“电池优化无限制”是否足够保活。
8. Debian rootfs 内 `node` 的版本（MCP stdio 服务器需要 Node 22+ 时，Debian 官方源的 node 版本是否够用，不够则用 NodeSource 或 Termux 的 node 补）。

---

## 10. 分阶段路线图

| 阶段 | 内容 | 交付物 | 退出标准 |
|---|---|---|---|
| P0 验证（1–2 天） | T-PH-1 V0 实验：Termux + proot + 官方二进制跑通 + 登录 | 验证报告 | RISK-1 有结论 |
| P1 最小可用（1–2 周） | APK 壳：终端 UI + 前台服务 + 引导器 + rootfs 下载 + claude 拉起 + OAuth 登录；权限模式锁死 default；§6 的 L1+L3 | 能装能跑的 debug APK | T-PH-2/3/4/7 通过 |
| P2 Root 玩法（1 周） | R1–R14 逐个实现（R9/R10 写操作默认关）；审计日志；启动自检 | Beta APK | §8.3 验收清单全过 |
| P3 加固与联动（按需） | R15 手机 MCP 端；R16 验证；会话清理；自动更新 | 正式版 | 真实使用一周无事故 |

---

## 附录：资料来源

- 本机研究：`~/workspace/claude-code-reverse/`（04 工具系统、05 权限与安全、08 API 与认证、14 环境注入与隐私、19 文件扩展点、21 Windows 11 实战组合）、`~/workspace/CLAUDE_CODE_FORENSICS/SECURITY_AUDIT.md`。
- 官方文档：`code.claude.com/docs/en/setup`（系统要求：macOS 13+、Windows 10 1809+、Ubuntu 20.04+、Debian 10+、Alpine 3.19+；4 GB+ RAM；npm 方式需 Node 22+，原生安装不需要 Node）。
- 公开资料（2026-11-06 检索，未登录任何账号）：Termux `nodejs`/`nodejs-lts` 包与 phantom-process-killer 机制、Android 10+ W^X 与 `jniLibs/*.so` 执行方案（Pockr 实战）、Bun 的 Android 构建（Termux TUR、bunproot 的 `LD_PRELOAD` 垫片）、glibc/bionic 不兼容、Claude Code 配置文件位置（`~/.claude/settings.json`、`~/.claude.json`、`~/.claude/.credentials.json`）、OPPO Reno12 PJV110 规格（12/16 GB LPDDR5X、Dimensity 8250）。
- 本机 VM 实测：Node v24.22.1、npm 10.9.4；Java 未安装；磁盘 148 GB 可用。
