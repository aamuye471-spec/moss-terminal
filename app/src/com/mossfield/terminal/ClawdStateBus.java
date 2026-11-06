package com.mossfield.terminal;

/**
 * ClawdStateBus：MainActivity（终端）→ FloatingWindowService（螃蟹）之间的
 * 轻量状态通道，同一进程内静态共享，不用 IPC。
 *
 * MainActivity 在终端有输出 / 会话结束时调这里记一笔；
 * FloatingWindowService 每 ~500ms 轮询 {@link #getPet()} 的 tick，
 * 把算好的状态画到螃蟹上。
 *
 * 注意：这里只能看到终端的输入输出，看不到 Claude Code 的 hook 事件，
 * 所以能驱动的只是“有输出→打字、输出停→开心、60 秒没动静→睡觉”
 * 这一档（见 ClawdPet 注释）。完整的 hook 事件 API 都在 ClawdPet 上预留了，
 * 以后接上 hook 再调它们。
 */
public class ClawdStateBus {

    private static final ClawdPet PET = new ClawdPet();

    public static ClawdPet getPet() {
        return PET;
    }

    /** 终端有输出：Claude 正在干活。 */
    public static void noteTerminalOutput() {
        PET.onTerminalOutput(System.currentTimeMillis());
    }

    /** 会话结束：回 idle。 */
    public static void noteSessionFinished() {
        PET.onSessionEnd(System.currentTimeMillis());
    }

    /** 会话（重）启动：回 idle。 */
    public static void noteSessionStarted() {
        PET.onSessionStart(System.currentTimeMillis());
    }
}
