#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <android/log.h>

#define TAG "RootShell"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)

static int root_pid = -1;
static int root_stdin = -1;
static int root_stdout = -1;

static void close_root_fds(void) {
    if (root_stdin >= 0) close(root_stdin);
    if (root_stdout >= 0) close(root_stdout);
    root_stdin = -1;
    root_stdout = -1;
}

/*
 * 只有当前进程真的拿到 root 才建立 shell，否则返回 -2。
 * 否则 is_root_shell_available() 会谎报可用，Java 层拿到的是普通权限 shell。
 */
int establish_root_shell(void) {
    if (root_pid > 0) return 0;

    if (getuid() != 0 && geteuid() != 0) {
        LOGI("current uid=%d euid=%d is not root", (int) getuid(), (int) geteuid());
        return -2;
    }

    int pipe_stdin[2], pipe_stdout[2];
    if (pipe(pipe_stdin) < 0 || pipe(pipe_stdout) < 0) {
        LOGI("pipe failed: %s", strerror(errno));
        return -1;
    }

    /* shell 退出后再写管道会产生 SIGPIPE，默认动作会直接杀掉应用进程 */
    signal(SIGPIPE, SIG_IGN);

    pid_t pid = fork();
    if (pid == 0) {
        /* 子进程：stdin/stdout/stderr 全部接到管道，stderr 合并进 stdout */
        dup2(pipe_stdin[0], STDIN_FILENO);
        dup2(pipe_stdout[1], STDOUT_FILENO);
        dup2(pipe_stdout[1], STDERR_FILENO);
        close(pipe_stdin[0]);
        close(pipe_stdin[1]);
        close(pipe_stdout[0]);
        close(pipe_stdout[1]);

        setgid(0);
        setuid(0);

        char *argv[] = {"/system/bin/sh", NULL};
        execve(argv[0], argv, environ);
        _exit(1);
    }

    if (pid < 0) {
        LOGI("fork failed: %s", strerror(errno));
        close(pipe_stdin[0]);
        close(pipe_stdin[1]);
        close(pipe_stdout[0]);
        close(pipe_stdout[1]);
        return -1;
    }

    close(pipe_stdin[0]);
    close(pipe_stdout[1]);
    root_pid = pid;
    root_stdin = pipe_stdin[1];
    root_stdout = pipe_stdout[0];

    /* 非阻塞读：Java 侧轮询，读取线程随时能退出，不会卡死 */
    int flags = fcntl(root_stdout, F_GETFL, 0);
    if (flags >= 0) fcntl(root_stdout, F_SETFL, flags | O_NONBLOCK);

    LOGI("root shell established, pid=%d", root_pid);
    return 0;
}

int write_root_shell(const char *cmd) {
    if (root_stdin < 0) return -1;
    size_t len = strlen(cmd);
    size_t written = 0;
    while (written < len) {
        ssize_t n = write(root_stdin, cmd + written, len - written);
        if (n < 0) {
            if (errno == EINTR) continue;
            return -1;
        }
        written += (size_t) n;
    }
    return (int) written;
}

/*
 * 返回 NULL 表示「暂无输出」或「shell 已退出」。
 * 读到 EOF 时把 shell 标记为不可用，让 Java 侧能感知并回到普通 shell。
 */
char* read_root_shell(void) {
    if (root_stdout < 0) return NULL;

    static char buffer[4096];
    ssize_t n = read(root_stdout, buffer, sizeof(buffer) - 1);
    if (n > 0) {
        buffer[n] = '\0';
        return buffer;
    }
    if (n == 0) {
        LOGI("root shell closed, pid=%d", root_pid);
        close_root_fds();
        root_pid = -1;
    }
    return NULL;
}

void close_root_shell(void) {
    if (root_pid > 0) {
        kill(root_pid, SIGTERM);
        waitpid(root_pid, NULL, 0);
        root_pid = -1;
    }
    close_root_fds();
}

int is_root_shell_available(void) {
    return (root_pid > 0) ? 1 : 0;
}
