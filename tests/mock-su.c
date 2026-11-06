// mock-su.c — 模拟 clap 解析的 su（KernelSU 那种 Rust su）。
//
// 只验证 argv 解析：argv[0] 是程序名（解析器跳过），argv[1] 必须是 -c，
// 否则按 clap 的格式报错并退出 2；argv[1] 是 -c 就打印要执行的命令并退出 0。
// 不会真的执行任何命令，也不会碰 root。
//
// 用法：gcc -O2 -o mock-su mock-su.c
// 调用方用 exec 控制 argv[0]（见 su_argv_exec_test.py）：
//   旧 argv（v1.2）：argv = ["-c", "exec /system/bin/sh <脚本>"]  -> 退出 2
//   新 argv（v1.3）：argv = [su, "-c", "exec /system/bin/sh <脚本>"] -> 退出 0
#include <stdio.h>
#include <string.h>

int main(int argc, char **argv) {
    if (argc < 2 || strcmp(argv[1], "-c") != 0) {
        fprintf(stderr, "error: unrecognized subcommand '%s'\n\n",
                argc >= 2 ? argv[1] : "");
        fprintf(stderr, "Usage: -c <COMMAND>\n\n");
        fprintf(stderr, "For more information, try '--help'.\n");
        return 2;
    }
    printf("OK: su would run as root: %s\n", argc >= 3 ? argv[2] : "");
    return 0;
}
