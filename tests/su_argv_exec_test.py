#!/usr/bin/env python3
"""su_argv_exec_test.py — su 调用格式的 exec 级测试（v1.3 真机 bug）。

背景：Termux 的 JNI createSubprocess（termux.c v0.118.0）把 Java 的 args
数组原样当成 argv，execvp(cmd, argv)，不会把 cmd 再拼成 argv[0]。
所以传给 TerminalSession 的 args 必须就是完整的 argv（args[0] = 程序本身）。

本测试用 subprocess 的 executable= 参数精确控制 argv[0]（即 execvp 的
argv），跑一个模拟 clap 解析的 su（tests/mock-su.c 编译出的二进制），
验证两组 argv 的命运：

  旧 argv（v1.2）：["-c", "exec /system/bin/sh <脚本>"]
      -> su 收到 argv[0]="-c"、argv[1]="exec ..."，
         报错 error: unrecognized subcommand 'exec ...'，退出码 2，
         和用户真机上报的一模一样（必须复现）。
  新 argv（v1.3）：[su, "-c", "exec /system/bin/sh <脚本>"]
      -> su 正常解析 -c，退出 0（必须通过）。

用法：python3 tests/su_argv_exec_test.py
全部通过退出 0，任一失败退出 1。
"""
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
MOCK_C = os.path.join(HERE, "mock-su.c")
MOCK_BIN = os.path.join(HERE, "mock-su")

SU = "/data/adb/ksu/bin/su"
SCRIPT = "/data/user/0/com.mossfield.terminal/files/run-claude.sh"
CMD = "exec /system/bin/sh " + SCRIPT

passed = 0
failed = 0


def check(name, ok, detail=""):
    global passed, failed
    if ok:
        print("PASS: " + name)
        passed += 1
    else:
        print("FAIL: " + name + (" -- " + detail if detail else ""))
        failed += 1


def main():
    # 编译 mock su（没有 gcc 就报错退出，不静默跳过）。
    if not os.path.exists(MOCK_BIN) or (
        os.path.getmtime(MOCK_C) > os.path.getmtime(MOCK_BIN)
    ):
        r = subprocess.run(
            ["gcc", "-O2", "-o", MOCK_BIN, MOCK_C],
            capture_output=True, text=True)
        if r.returncode != 0:
            print("FAIL: 编译 mock-su 失败：\n" + r.stderr)
            sys.exit(1)
    print("mock su: " + MOCK_BIN)

    # --- 1. 旧 argv（v1.2）：argv = ["-c", CMD]，必须复现用户真机的报错 ---
    old = subprocess.run(
        ["-c", CMD], executable=MOCK_BIN,
        capture_output=True, text=True)
    print("--- 旧 argv（v1.2）---")
    print("argv = " + repr(["-c", CMD]))
    print("returncode = " + str(old.returncode))
    print("stderr:\n" + old.stderr.rstrip())
    check("旧 argv 退出码是 2", old.returncode == 2,
          "got %d" % old.returncode)
    check("旧 argv 报错是 unrecognized subcommand 'exec ...'",
          "unrecognized subcommand '" + CMD + "'" in old.stderr)
    check("旧 argv 报错含 Usage: -c <COMMAND>",
          "Usage: -c <COMMAND>" in old.stderr)

    # --- 2. 新 argv（v1.3）：argv = [su, "-c", CMD]，必须通过 ---
    new = subprocess.run(
        [SU, "-c", CMD], executable=MOCK_BIN,
        capture_output=True, text=True)
    print("--- 新 argv（v1.3）---")
    print("argv = " + repr([SU, "-c", CMD]))
    print("returncode = " + str(new.returncode))
    print("stdout:\n" + new.stdout.rstrip())
    check("新 argv 退出码是 0", new.returncode == 0,
          "got %d, stderr=%s" % (new.returncode, new.stderr))
    check("新 argv 里 su 收到 -c 并执行命令",
          new.stdout.startswith("OK: su would run as root: " + CMD))

    print("SU_ARGV_EXEC_TEST: passed=%d failed=%d" % (passed, failed))
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
