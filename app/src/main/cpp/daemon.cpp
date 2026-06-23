#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include <string>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <pthread.h>
#include <fcntl.h>

using namespace std;

int g_socket_fd = -1;

void daemonize() {
    pid_t pid;

    // 创建子进程
    pid = fork();
    if (pid < 0) {
        exit(EXIT_FAILURE);
    }
    if (pid > 0) {
        exit(EXIT_SUCCESS); // 父进程退出
    }

    // 创建新会话
    if (setsid() < 0) {
        exit(EXIT_FAILURE);
    }

    pid = fork();
    if (pid < 0) {
        exit(EXIT_FAILURE);
    }
    if (pid > 0) {
        exit(EXIT_SUCCESS);
    }
    // 改变当前目录
    if ((chdir("/")) < 0) {
        exit(EXIT_FAILURE);
    }

    // 重设文件权限掩码
    umask(0);

    // 关闭文件描述符
    int fd = open("/dev/null", O_RDWR);
    if (fd != -1) {
        dup2(fd, STDIN_FILENO);
        dup2(fd, STDOUT_FILENO);
        dup2(fd, STDERR_FILENO);
        if (fd > STDERR_FILENO) close(fd);
    }
}

void *messenger_thread_func(void *args) {
    if (g_socket_fd == -1)return nullptr;
    char buffer[256];
    while (true) {
        int n = read(g_socket_fd, buffer, sizeof(buffer) - 1);
        if (n <= 0)break;
        buffer[n] = '\0';
        string reply;
        if (strcmp(buffer, "status") == 0) {
            reply = "Working: " + to_string(getpid());
        } else {
            reply = "Unknown command!";
        }
        write(g_socket_fd, reply.c_str(), reply.size());
    }
    close(g_socket_fd);
    return nullptr;
}

int main(int argc, char *argv[]) {
    if (argc < 2) return 1;
    daemonize();
    g_socket_fd = (int) strtol(argv[1], nullptr, 10);
    pthread_t msg_thread;
    if (pthread_create(&msg_thread, NULL, messenger_thread_func, NULL) != 0)exit(-1);
    while (true) {
        sleep(2);
    }

    return 0;
}