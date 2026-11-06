# Clawd 桌宠（螃蟹）出处与许可证说明

## 来源

- 项目：clawd-on-badge（Clawd 桌宠，Electron 桌面宠物）
- 仓库：https://github.com/shdy0602-glitch/clawd-on-badge
- 取用提交：70af12e46a2894f943cb3afa8d024ce53d0423b1
- 取用内容：仅 `assets/gif/clawd-*.gif` 共 24 个 GIF（约 1.9 MB），
  以及 `docs/guides/state-mapping.md` 的事件→状态→GIF 映射逻辑。
  没有搬 Electron、Node 依赖或任何上游代码。

## 许可证——注意有两个不同的许可证

1. **上游源码：AGPL-3.0**（全文见同目录 `LICENSE-AGPL-3.0.txt`）。
   状态映射等逻辑属于源码范畴，移植时遵循 AGPL-3.0。
2. **图片素材：不在 AGPL 覆盖范围内**（全文见同目录 `LICENSE-ARTWORK.txt`，
   原文：`assets/LICENSE`）。
   上游明确写明：artwork 文件“All rights are reserved”，
   Clawd 角色属于 Anthropic（https://www.anthropic.com），
   Clawd 像素画是粉丝自制（fan-created），**不可商用**；
   未经版权方书面许可，不得复制、修改、分发或使用，
   仅限“作为 clawd-on-badge 应用分发时的个人使用”。

## 本 App 的使用方式

- Moss Terminal 只在用户自己的手机上、为用户个人使用而显示这只螃蟹，
  不商用、不单独分发这些 GIF、不改图。
- GIF 以原样放进 APK 的 `assets/clawd/`，不做任何修改。
- 本文件与两个许可证全文随 APK 一并交付（`assets/clawd/` 内可查）。

## 合规提醒

- 因为素材是“保留所有权利、仅限个人使用”，**不要把含这些 GIF 的 APK
  公开发布、上传到应用市场或拿去商用**。个人自用、自己手机上装，是
  上游许可写明的范围；超出这个范围需要先取得版权方书面许可。
- 如果上游或 Anthropic 要求下架这些素材，删掉 `assets/clawd/*.gif`
  并把悬浮窗恢复成纯色按钮即可，状态机（`ClawdPet`）本身不受影响。

## 24 个 GIF 清单（字节数与上游一致）

| GIF | 字节 | 用途 |
|---|---|---|
| clawd-idle.gif | 40707 | 无活动（默认） |
| clawd-idle-reading.gif | 73538 | 空闲一阵后随机看书 |
| clawd-thinking.gif | 57599 | 用户提交提示 |
| clawd-typing.gif | 101039 | 工具运行（1 会话） |
| clawd-headphones-groove.gif | 66648 | 工具运行（2 会话）/ 子代理（1 个） |
| clawd-building.gif | 89980 | 工具运行（3+ 会话） |
| clawd-juggling.gif | 57586 | 子代理（2+ 个）三球杂耍 |
| clawd-error.gif | 55847 | 工具失败 |
| clawd-debugger.gif | 79482 | 连续第 2 次工具失败（加分项） |
| clawd-happy.gif | 47944 | 结束 / 压缩后 |
| clawd-notification.gif | 126061 | 权限请求 |
| clawd-sweeping.gif | 53842 | 压缩前 |
| clawd-carrying.gif | 68149 | 建 worktree |
| clawd-sleeping.gif | 40508 | 60 秒无操作 |
| clawd-conducting.gif | 52500 | 上游已退役的指挥动作（保留，未接入主映射） |
| clawd-bubble.gif | 63186 | 连点彩蛋：冒泡 |
| clawd-react-annoyed.gif | 42917 | 连点彩蛋：不耐烦 |
| clawd-react-double-jump.gif | 45425 | 连点彩蛋：双跳 |
| clawd-mini-idle.gif | 158473 | 迷你模式默认 |
| clawd-mini-peek.gif | 146251 | 迷你模式点一下：探头 |
| clawd-mini-happy.gif | 131098 | 迷你模式任务完成 |
| clawd-mini-alert.gif | 160486 | 迷你模式权限提醒 |
| clawd-mini-crabwalk.gif | 76664 | 迷你模式拖动：横着走 |
| clawd-mini-enter.gif | 111742 | 进入迷你模式 |
