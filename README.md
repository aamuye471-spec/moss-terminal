# Moss Terminal — Claude Code for Rooted Android

一个把 **Claude Code 2.1.318** 完整装进 APK 的终端应用，专为已 root 的 Android 手机设计。
打开就是终端，Claude Code 已经在里面等着，root 权限直接可用。

- 包名：`com.mossfield.terminal`
- 应用名：Moss Terminal（中性名字，不暴露用途）
- 版本：1.7（versionCode 8）
- 大小：约 310 MB（Claude Code 二进制 321 MB 不压缩存放，装上打开就能用）
- 目标：Android 14（API 34），最低 Android 10（API 29）
- 架构：arm64-v8a（你的 Dimensity 8250 就是这个架构）
- 签名：APK Signature Scheme v3（调试密钥，可自行重签）

> v1.7 两件事。一是**代码里彻底清除 api.anthropic.com**：每次启动都强制校验
> 启动脚本内容，脏脚本（v1.5 残留、手改过、含 Anthropic 地址）一律重写，
> 写不干净就不启动会话（再也不静默跑旧脚本）；中转地址、会话环境变量、
> 网络诊断、settings.json env 块全部过同一个兜底，任何情况下都不返回
> api.anthropic.com；中转框里手填这个地址也不保存。
> 二是**螃蟹活起来了**：空闲时在屏幕上慢慢溜达（碰边反射、偶尔坐下），
> 手指拖动时它用弹簧追着手指走；双指捏合调大小（64–180dp，松手记住）；
> 锁屏上也显示（只显示螃蟹，不展开面板）；空闲动作轮换（看书/张望/挥手/
> 伸懒腰/打盹/发呆，每个 2–4 秒）；单点偶尔跳一下/转个圈/挥爪；
> 空闲满 8 轮或连点第 3 次双跳会翻个跟头、翻一半自己晕倒、过会儿爬起来；
> 开心时长大一点、睡觉时缩一点，缩放都平滑过渡。
> 24 张 GIF 没变，全是复用组合 + 旋转/缩放变换，不新加素材。

> v1.6 默认走 DeepSeek API，不再连 Anthropic：启动脚本和会话环境变量里按
> DeepSeek 官方文档写死 8 个环境变量（ANTHROPIC_BASE_URL=https://api.deepseek.com/anthropic，
> 模型 deepseek-flash[1m] / deepseek-flash 等）。API Key 用 DeepSeek 的 Key，
> 走 ANTHROPIC_AUTH_TOKEN（以前写错成 ANTHROPIC_API_KEY，已改；旧的会被迁移掉）。
> Key 只存本机 settings.json env 块（0600）+ 会话环境变量（内存），绝不写进脚本和 APK。
> 菜单里的中转入口还在，默认就是 DeepSeek；旧的中转设置升级时会被清掉，不会再覆盖它。

> v1.1 修了 v1.0 的闪退：打开显示“正在释放”然后闪退。
> 原因是 321MB 二进制在 APK 里是压缩存放的，手机上要实时解压（这条路从没验证过），
> 加上失败时没有接住所有异常。v1.1 把资源改成不压缩存放（释放变纯拷贝），
> 失败时显示明白的错误 + 一键重试，绝不闪退。傻瓜式：装上打开就能用，
> 不用手动移文件、不用手动解压、不用调设置。

## 架构：为什么能跑

Claude Code 是 Bun 编译的命令行工具，官方只发 Linux/macOS/Windows 版。
这台手机是 Android，但它本质是 Linux 内核 + aarch64 CPU。
关键发现：官方 npm 包 `@anthropic-ai/claude-code-linux-arm64-musl` 里的二进制是 **musl 静态链接风格**，
只依赖一个动态库 `libc.musl-aarch64.so.1`。

所以不需要 Termux、不用 proot、不用 Debian 根文件系统——直接把官方二进制塞进 APK：

| 组件 | 来源 | 作用 |
|---|---|---|
| `assets/claude` | 官方 npm 包 2.1.318（arm64 musl） | Claude Code 本体，patchelf 改过 INTERP/RUNPATH |
| `assets/ld-musl-aarch64.so.1` | Alpine Linux musl 1.2.5-r12 | musl 加载器 + libc，二进制唯一的动态依赖 |
| `libs/arm64-v8a/libtermux.so` | Termux terminal-view 0.118.0 | 终端 PTY 的 JNI 层 |
| `libs/terminal-emulator.jar` + `terminal-view.jar` | Termux 0.118.0（JitPack） | 终端模拟器 + 终端视图 |

启动流程：

```
打开 App → 首次运行把 assets 解压到应用私有目录
  /data/data/com.mossfield.terminal/files/bin/claude      (0755)
  /data/data/com.mossfield.terminal/files/lib/ld-musl-aarch64.so.1
  /data/data/com.mossfield.terminal/files/lib/libc.musl-aarch64.so.1  (软链)
→ 生成启动脚本 files/run-claude.sh
→ 以 root（su）或普通 shell 启动脚本 → 脚本 exec claude
→ 终端里就是 Claude Code
```

Claude Code 的二进制被 patchelf 改过，解释器写死为
`/data/data/com.mossfield.terminal/files/lib/ld-musl-aarch64.so.1`，
所以不需要 `LD_LIBRARY_PATH`，直接就能跑。
（显式传加载器会失败；直接用 patchelf 改 INTERP 是唯一走通的办法。）

root 执行时绕过 Android 10+ 的 W^X 限制（root 可以执行应用私有目录里的二进制）。
普通模式（无 root）下，Android 10+ 的 W^X 可能阻止执行——这是已知限制，root 模式是主路径。

## 怎么装

1. 手机上允许“安装未知来源应用”（设置 → 应用 → 特殊访问权限）。
2. 把 `moss-terminal.apk` 传到手机（USB、微信文件传输、网盘都行）。
3. 点 APK 安装。**不需要 root 权限就能装**；root 只在运行 Claude Code 时才用到。
4. 首次打开：App 自动在后台释放 Claude Code（321 MB，几秒钟），有进度条；
   万一失败会显示明白的错误，点“重试”就行，不会闪退。放好后直接进终端。
5. 首次运行 Claude Code 时需要 DeepSeek API Key：
   - 菜单 → 设置 API Key，粘贴你的 DeepSeek API Key（存在应用私有目录的
     `~/.claude/settings.json` 的 env 块，0600 权限，只有本 App 能读；
     会话启动时也会以 ANTHROPIC_AUTH_TOKEN 传给 Claude Code 进程，只走内存）。
   - 不再用 Anthropic 的 OAuth 登录（v1.6 默认后端是 DeepSeek）。
   - Key 只在你自己的手机上，不会发到任何地方；本 App 不提供、不验证 Key。

## 怎么用

- 打开就是终端，Claude Code 自动启动。直接打字对话。
- 顶部常驻一排大按钮（v1.4，一直可见）：`☰ 菜单`（绿色） `API Key` `悬浮窗：开/关` `网络诊断`，点一下就行，不用找菜单。
- 底部按键栏：`ESC` `TAB` `CTRL`（开关） `↑` `↓` `←` `→` `粘贴` `菜单`。
- 按手机返回键 = 发 ESC（Claude Code 里常用来取消/退出）。
- 菜单（顶部绿色的 `☰ 菜单` 按钮，或按键栏的菜单键）：
  - **网络诊断**：一键测生效后端（默认 DeepSeek）的 DNS 解析和 443 连通，结果说人话。
  - **设置中转地址**：填一个讲 Anthropic API 格式的中转地址（ANTHROPIC_BASE_URL），保存后重启会话生效；点“恢复默认”回到 DeepSeek 地址。
  - **设置 API Key**：填 DeepSeek API Key（走 ANTHROPIC_AUTH_TOKEN）。
  - **重启会话**：重开一个 Claude Code 会话。
  - **切换 root/普通**：在 root 会话和普通 shell 会话之间切换。
  - **悬浮窗**：开/关一键自动填入的悬浮窗（见下）。顶部也有常驻开关，显示开/关状态。

## 连不上？先说清楚（v1.7：代码里已清除 Anthropic 地址）

v1.6 起默认后端是 DeepSeek（ANTHROPIC_BASE_URL=https://api.deepseek.com/anthropic），
不再连 Anthropic 的 api.anthropic.com。v1.7 又补了一刀：**每次启动都强制校验
启动脚本内容**——v1.5 升级残留的旧脚本、手改过的脚本、含 Anthropic 地址的脚本，
一律按当前生效地址重写；写不干净就不启动会话，弹明白的错 + 重试，
再也不会静默跑旧脚本去连 api.anthropic.com。之前 v1.3 截图里的
`Unable to connect to Anthropic services / Failed to connect to api.anthropic.com: EAI_AGAIN`
（手机解析不了 api.anthropic.com 的 DNS 失败）不会再出现——因为根本不连那个地址了。

- **没有“国家检查”在拦你**：拆解报告（`~/workspace/claude-code-reverse/`、`~/workspace/CLAUDE_CODE_FORENSICS/`）里全文搜“supported countries / not available in your country / country check / region check”零相关命中，客户端源码里没有单独的国家/地区可用性检查。截图里那句“might not be available in your country”只是连不上时附的一句通用提示，不是检查。本 App 里没有任何“跳过国家检查”开关（不存在的东西不编）。
- **现在连不上就是两件事**：一是网络到不了 api.deepseek.com（点顶部 **网络诊断** 看 DNS 和 443 哪一步不通）；二是 DeepSeek Key 无效或没填（菜单 → 设置 API Key 检查）。Key 要自己去 DeepSeek Platform 申请，本 App 不提供、不验证 Key。
- **想改目的地**：菜单 → 设置中转地址，把 ANTHROPIC_BASE_URL 指向一个讲 Anthropic API 格式的中转/代理地址，保存后重启会话生效；点“恢复默认”回到 DeepSeek。中转站要自己找，本 App 不提供、不推荐任何中转站。
- **用中转地址前必须知道的两件事（App 里的设置框也写了，不瞒着）**：
  1. 客户端会把中转地址的 host 名单独上报给 GrowthBook（只含 host，不含 path/query/凭证，growthbook.ts:439-448）。
  2. 2026-06-30 有用户披露过一起隐写标记事件（05-权限与安全.md:266）：中转地址 + 本机时区为 Asia/Shanghai 或 Asia/Urumqi 时，代理域名会与一份隐藏清单比对，结果藏进系统提示词。用户时区是 Asia/Hong_Kong，不在那两个里面。

先点顶部 **网络诊断** 看看到底是哪一步不通（DNS 还是 443），再决定换网络还是检查 Key。

## API Key 一键填（v1.4；v1.6 改名成 DeepSeek Key）

顶部常驻 **API Key** 按钮（悬浮窗展开后也有 `★ API Key` 按钮），点一下弹出输入框：点 **粘贴** 就从剪贴板填入，点 **保存** 就存本机（settings.json 的 env 块，0600），不用找菜单。已保存的 key 不回显原文；点 **清除已保存** 可删掉。
v1.6：Key 走的是 **ANTHROPIC_AUTH_TOKEN**（DeepSeek 官方文档用的名字），以前写错成 ANTHROPIC_API_KEY，已改；旧的 ANTHROPIC_API_KEY 会被迁移掉。Key 还会以 ANTHROPIC_AUTH_TOKEN 传进会话环境变量（只走内存），双保险。
v1.4 顺手修了旧 bug：以前换 key 不生效（只在没有 key 时才写），现在换 key 会替换、清空会删行。v1.6 又修了一个：以前清掉 key 后再存会插出第二个 env 块（JSON 重复键），现在不会了。
  - **查看启动日志**：看每次启动都干了什么、哪一步出问题，不用连电脑抓 logcat。
  - **关于**：版本信息。
  - **退出**：关闭 App。

## 悬浮窗（一键自动填入，v1.2 新增；v1.4 变显眼了；v1.5 变成螃蟹；v1.7 活起来了）

顶部 **悬浮窗：开/关** 按钮一点就开/关（显示当前状态），或菜单 → 悬浮窗。
打开后屏幕上浮着一只可拖动的螃蟹桌宠 Clawd（clawd-on-badge 的 GIF，
24 张共 1.9 MB，见 `app/assets/clawd/ATTRIBUTION.md`），点一下展开成一排快捷按钮，
点按钮就把对应内容自动填进终端并回车，不用手动打字。

**v1.7 螃蟹会动了：**
- **空闲溜达**：没事时在屏幕上慢慢溜达（约 30px/秒，碰到屏幕边缘就反射换方向，
  偶尔坐下歇 2.5 秒），溜达时配 crabwalk 动作、换方向时转身、停下时刹住；
  面板展开、睡觉、翻跟头时不动，不挡终端操作。
- **跟手**：手指拖动它时不硬贴在手指下，用弹簧追着手指走（滞后 + 每步封顶，
  手指甩得快它就跟不上、顿一下再加速追上，不瞬移）；移动时朝手指方向倾斜/转身；
  松手后停在落点，过 2 秒恢复溜达。只在碰到螃蟹本身时才收触摸，不加全屏透明层，
  不挡其他应用的操作。
- **捏合缩放**：双指捏合调螃蟹大小（64–180dp），松手记住尺寸，下次打开还是这个大小；
  长按切换迷你/正常保留，迷你就是缩到最小 64dp。
- **状态缩放**：开心/兴奋时长大一点（1.12 倍），睡觉时缩一点（0.85 倍），
  跳/转圈时有个缩放脉冲；都是 300ms 平滑过渡，不跳变；只改画出来的大小，
  不影响点按区域和拖动。
- **动作更多**：空闲时轮换看书、左右张望、挥手/打招呼、伸懒腰、打盹点头，
  不只待在 idle，每个动作 2–4 秒再换；单点除了展开面板，偶尔触发跳一下、
  转个圈、挥爪（快速连点彩蛋保留）；思考、打字、出错、成功各有不同动作
  （思考轻微起伏、出错抖动、成功长大）。
- **翻跟头翻一半自己晕倒**：空闲满 8 轮动作、或连点第 3 次双跳时，螃蟹翻个跟头，
  翻到一半自己晕了、躺平（旋转 90° 贴地），过会儿再爬起来，整个过程 4 秒；
  晕倒期间点按没反应，恢复后才正常；翻跟头时暂停溜达，恢复后再继续。
  全是现有 GIF + 旋转/缩放变换，不新加素材。
- **锁屏也有它**：悬浮窗在锁屏上也显示（FLAG_SHOW_WHEN_LOCKED，不解开锁屏、
  不读锁屏内容、不申请额外敏感权限）；锁屏上只显示螃蟹本身，不展开快捷按钮面板，
  点按会唤醒到应用内（系统会先要求解锁）。

螃蟹会跟着 Claude 的活动换动画：发提示 → 思考；终端有输出 → 打字；
输出停 3 秒 → 开心；60 秒没动静 → 睡觉；空闲一阵 → 轮换动作。
长按螃蟹切换迷你模式（迷你 GIF），快速连点有冒泡 / 不耐烦 / 双跳彩蛋。

| 按钮 | 填进终端的内容 |
|---|---|
| 登录 | `/login`（Claude Code 登录命令） |
| ★ API Key | 打开 API Key 设置框（不把 key 打进终端；绿色高亮，排在最前面） |
| 体检 | 手机体检一下（调 phone-doctor 技能） |
| 备份 | 备份微信聊天记录到 /sdcard |
| 查流量 | 查一下哪个应用在后台偷跑流量 |
| 清缓存 | 清理一下手机缓存 |
| 中断 | 发 Ctrl+C，中断当前命令 |

开启悬浮窗需要系统设置里允许“Moss Terminal 在其他应用的上层显示”。
App 里点开悬浮窗，会先讲清楚是干什么的，再一键去设置里开，回来自动开。
悬浮窗只用来显示按钮，不会偷看其他应用；自动填入的文字都只是终端输入，
最终执行的 root 命令仍要过 RootBridge 黑名单 + settings.json deny，不绕过任何防线。
- 保活服务：App 在后台运行时有前台服务 + 唤醒锁，Claude Code 不会因为切后台被杀掉。
- 重启后 root 消失（临时 root 的特性）：开机后 App 会发一条通知提醒你“root 已失效，请重新获取 root”。

## root 玩法（放开想的部分）

有 root 之后，Claude Code 在终端里就是 root 身份，可以直接做这些事。
以下 5 个 root 技能已经内置在 App 里（`~/.claude/skills/`），Claude Code 会自己调用：

| 技能 | 能做的事 |
|---|---|
| `phone-doctor` | 手机体检：电池健康、温度、内存、存储、CPU、发热进程、唤醒锁 |
| `phone-ops` | 应用管理：冻结/解冻/停用系统应用、清缓存、改系统设置、调屏幕亮度、DND |
| `phone-backup` | 备份：打包应用数据、导出短信/通话记录、整机备份到 /sdcard |
| `phone-net` | 网络：抓包（tcpdump）、防火墙（iptables，按 UID 限流/断网）、改 DNS、测速 |
| `phone-audit` | 安全审计：查可疑应用、查 root 残留、查异常唤醒、查权限滥用 |

你也可以直接在终端里对 Claude Code 说人话，比如：

- “把那个天天弹广告的系统应用冻结掉”
- “备份微信聊天记录到 /sdcard”
- “查一下哪个应用在后台偷跑流量”
- “把屏幕亮度调到最低，晚上省电”
- “抓一下这个 App 的网络包，看看它往哪发数据”

Claude Code 会自己去调 root 命令。不用你记命令。

## system 分区不动的保证（硬红线）

**本 App 绝对不写 system 分区。** 三层防线：

1. **应用层守卫（RootBridge）**：所有经 App 执行的 root 命令先过黑名单。
   黑名单包括 `/system` `/vendor` `/product` `/odm` `/oem` `/system_ext` `/apex`
   `/etc` `/init` `/boot` `/recovery` `/metadata` `/dev/block` `/data/system` `/data/misc`，
   以及任何 `remount,rw` 到 system/vendor/product 的操作、任何 `dd` 写块设备。
   命中直接拒绝执行，并写入审计日志。
   路径会先规范化（`//`、`.`、`..` 都会被折叠），绕过写法一样拦。
2. **Claude Code 层守卫（PreToolUse hook + settings.json deny）**：Claude Code 自己的权限系统
   也配了同样的 deny 规则，Bash/Write/Edit 工具碰到 system 路径直接拒绝。
3. **自检（SetupManager.selfCheck）**：启动时检查 `/system` 是否仍为只读挂载，结果写日志。

所有 root 命令都会记审计日志：`files/logs/audit.log`（0600，只有本 App 能读）。
你可以随时在终端里 `cat files/logs/audit.log` 看 App 都执行过什么 root 命令。

**注意**：这个保证只覆盖“经本 App 执行的命令”。如果你自己在终端里手动敲
`mount -o remount,rw /system`，那是你自己的操作，App 拦不住你——
但 Claude Code 自己不会这么做，App 的守卫也不会放行这类命令。

## 文件位置（手机上）

| 位置 | 内容 |
|---|---|
| `files/bin/claude` | Claude Code 二进制 |
| `files/lib/ld-musl-aarch64.so.1` | musl 加载器 |
| `files/run-claude.sh` | 启动脚本 |
| `files/home/.claude/settings.json` | Claude Code 配置（含 deny 规则、API key 如有） |
| `~/.claude/skills/` | 5 个 root 技能 |
| `files/logs/audit.log` | root 命令审计日志 |
| `files/workspace/` | Claude Code 的工作目录（默认 cwd） |

卸载 App 会把这些全部删掉，不留残余。

## 隐私

- App 不收集、不上传任何数据。没有统计、没有遥测、没有广告。
- Claude Code 本身会向 DeepSeek 服务器发请求（v1.6 默认后端，这是它工作的必要条件），
  具体发什么、能不能关，见 Claude Code 拆解报告
  （`~/workspace/claude-code-reverse/` 和 `~/workspace/CLAUDE_CODE_FORENSICS/`；
  注意拆解针对的是默认连 Anthropic 的情形，v1.6 改走 DeepSeek 后目的地变了）。
- 你的 DeepSeek API key 只存在你手机的应用私有目录里，不会发到任何地方。

## 已知限制

- **没有 git**：没找到可用的 aarch64 静态 git 二进制，Claude Code 在没有 git 的环境下照常工作，
  只是不能 `git clone` / `git commit`。可以在手机上另装 Termux 补一个 git，或者等后续版本内置。
- **普通模式可能被 W^X 拦住**：Android 10+ 不允许从应用私有目录执行二进制文件。
  root 模式不受影响；无 root 时如果启动失败，请先获取 root。
- **首次解压需要 ~650 MB 可用空间**（321 MB 二进制 + 解压临时文件）。
- **签名是调试密钥**：可以正常安装使用；如果要发布或换设备长期用，建议用自己的密钥重签。
- **只支持 arm64**：Dimensity 8250 是 arm64，没问题；x86 手机用不了。

## 版本历史

- **v1.7**：两件事。一是**代码里彻底清除 api.anthropic.com**：审计确认 v1.6 有 4 条
  运行时路径还能产出它（旧脚本残留是主因：SETUP_VERSION 没变，升级跳过重装，
  重写失败还被静默吞掉；另有 getSavedBaseUrl 原样返回、buildEnv 同款漏洞、
  settings.json env 块从不清理）。修法：每次启动强制校验脚本内容
  （`ensureLauncherScript`：脏脚本一律重写，写完读回校验，写不干净就不启动会话）；
  `resolveBaseUrl` 单一 choke point，中转地址/会话 env/网络诊断/保存入口全部走它，
  空、非法、指向 api.anthropic.com 的一律回落 DeepSeek；settings.json env 块里的
  Anthropic 地址一并清掉；中转框手填该地址也不保存。测试：新增
  AnthropicPurgeTest 103/103（含模拟 v1.5 旧脚本残留再启动的用例），
  DeepSeekTest 50/50、SuArgvTest 10/10、RootBridge 39/39 回归通过。
  二是**螃蟹活起来了**：空闲溜达（CrabWander，每 100ms 一步，碰边反射，偶尔坐下；
  面板展开/睡觉/翻跟头时不动）、弹簧跟手（滞后 + 每步 48px 封顶，不瞬移，
  松手停落点、2 秒后恢复溜达）、双指捏合调大小（64–180dp，SharedPreferences 持久化，
  长按迷你保留=缩到最小）、锁屏显示（FLAG_SHOW_WHEN_LOCKED，不解开锁屏，
  锁屏上不展开面板，点按唤醒到应用内）、空闲动作轮换（看书/张望/挥手/伸懒腰/
  打盹/发呆，10 秒宽限期后轮换，每个 2–4 秒）、单点反应（每 3 次单点：跳→转圈→挥爪）、
  工作状态区分（思考起伏/出错抖动/成功长大）、翻跟头翻一半晕倒（4 秒：翻 1.2s→
  晕 1.8s→爬起来 1s；空闲满 8 轮或连点第 3 次双跳触发；晕时点按没反应；
  翻时暂停溜达）、状态缩放（开心 1.12/睡觉 0.85，300ms smoothstep 平滑过渡，
  只改画出来的大小）。24 张 GIF 没变，全是复用组合 + 旋转/缩放变换。
  测试：新增 ClawdPetActsTest 59/59、CrabWanderTest 639/639、CrabScaleTest 37/37、
  FloatingOverlayTest 5/5；ClawdPetTest 更新到 94/94（状态数 24→30，25 秒规则
  并入轮换表）。system 分区三层防御未动。
- **v1.6**：默认后端改走 DeepSeek，不再连 Anthropic。启动脚本和会话环境变量里按
  DeepSeek 官方文档写死 8 个环境变量（ANTHROPIC_BASE_URL=https://api.deepseek.com/anthropic、
  ANTHROPIC_MODEL=deepseek-flash[1m]、ANTHROPIC_DEFAULT_OPUS_MODEL=deepseek-flash[1m]、
  ANTHROPIC_DEFAULT_SONNET_MODEL=deepseek-flash[1m]、ANTHROPIC_DEFAULT_HAIKU_MODEL=deepseek-flash、
  CLAUDE_CODE_SUBAGENT_MODEL=deepseek-flash、CLAUDE_CODE_EFFORT_LEVEL=max、
  CLAUDE_CODE_AUTO_COMPACT_WINDOW=786432）。API Key 改走 ANTHROPIC_AUTH_TOKEN
  （DeepSeek 文档用的名字；以前写错成 ANTHROPIC_API_KEY，已改，旧的会被迁移掉），
  同时写进 settings.json env 块（0600）和会话环境变量（只走内存），双保险。
  中转入口保留，默认指向 DeepSeek；升级时一次性清掉 v1.4 时代保存的中转地址，
  旧设置不再覆盖 DeepSeek。网络诊断改测生效后端（默认 DeepSeek）。
  另外修了 applyApiKey 的重复 env 块 bug（清掉 key 后再存会插第二个 env 键）。
  测试：DeepSeekTest 50/50；SuArgvTest 10/10、RootBridge 39/39、ClawdPet 88/88 回归通过。
- **v1.3**：修 su 调用格式。根因是传给 Termux 会话的 args 缺了 argv[0]：
  Termux 的 JNI 把 args 数组原样当成 argv，su 实际收到 argv[0]="-c"、
  argv[1]="exec /system/bin/sh ..."——su 把它当成子命令报错
  （`error: unrecognized subcommand ...`，`Usage: -c <COMMAND>`，退出码 2）。
  v1.3 把程序本身补进 args[0]（root：`{su, "-c", "exec ..."}`；
  普通：`{"/system/bin/sh", <脚本>}`），顺带修了普通模式同样的问题
  （原来 sh 收不到脚本参数，只会打开空白交互 shell）。
  另外：会话刚启动（10 秒内）就非零退出时，弹明白的错误框 + 一键重试，
  不再只剩终端里一行报错。
- **v1.2**：修启动闪退。根因是保活服务声明了 `foregroundServiceType="dataSync"`，
  但 manifest 漏了 `FOREGROUND_SERVICE_DATA_SYNC` 权限，Android 14+ 上 `startForeground()`
  直接抛 SecurityException 杀进程（解压成功后闪退、每次都重复）。v1.2 补上权限，
  启动环节全部包进 try-catch 加启动日志，启动前多一道校验，root 没生效给明确提示。
  新增悬浮窗（一键自动填入）和启动日志查看。su 路径修正（KernelSU 真正的 su 在
  `/data/adb/ksu/bin/su`，不在 `/data/adb/ksud`）。
- **v1.1**：修释放闪退。资源改不压缩存放，解压放后台带进度条，失败给错误框和一键重试。
- **v1.0**：首版。解压环节真机闪退（压缩资源实时解压），启动环节也闪退（缺 FGS 权限）。

## 源码与许可证

源码在 `app/`（Java + 资源），构建脚本 `build.sh`（不用 Gradle，javac + d8 + aapt2 手工构建）。
终端部分用了 Termux 的 `terminal-emulator` 和 `terminal-view`（GPL-3.0），
源码随本目录一起提供；Termux 仓库地址：https://github.com/termux/termux-app
（terminal-emulator 和 terminal-view 是该仓库内的模块）。

Claude Code 二进制是 Anthropic 的闭源产品（从官方 npm 包提取，未修改代码，只改了动态链接器路径），
使用需遵守 Anthropic 的使用条款。
