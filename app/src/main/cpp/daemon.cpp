#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include <string>
#include <cstring>
#include <cstddef>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <fcntl.h>
#include <errno.h>
#include <thread>

using namespace std;

void daemonize() {
    pid_t pid;

    // 创建子进程
    pid = fork();
    if (pid < 0) {
        _exit(EXIT_FAILURE);
    }
    if (pid > 0) {
        _exit(EXIT_SUCCESS); // 父进程退出
    }

    // 创建新会话
    if (setsid() < 0) {
        _exit(EXIT_FAILURE);
    }

    pid = fork();
    if (pid < 0) {
        _exit(EXIT_FAILURE);
    }
    if (pid > 0) {
        _exit(EXIT_SUCCESS);
    }
    // 改变当前目录
    if ((chdir("/")) < 0) {
        _exit(EXIT_FAILURE);
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

int main(int argc, char *argv[]) {
    if (argc < 2) return 1;
    daemonize();
    signal(SIGPIPE, SIG_IGN);
    string abstract_name = string("\0", 1) + argv[1];
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) return 2;
    struct sockaddr_un address = {};
    address.sun_family = AF_UNIX;
    size_t len = abstract_name.size();
    if (len > sizeof(address.sun_path)) {
        close(fd);
        return 3;
    }
    memcpy(address.sun_path, abstract_name.data(), len);
    socklen_t address_len = offsetof(struct sockaddr_un, sun_path) + len;
    if (::bind(fd, (struct sockaddr *) &address, address_len) < 0) {
        close(fd);
        if (errno == EADDRINUSE) return 4;
        return 5;
    }
    if (listen(fd, 1) < 0) {
        close(fd);
        return 6;
    }
    while (true) {
        int client_fd = accept(fd, nullptr, nullptr);
        if (client_fd < 0) continue;
        char buffer[256];
        while (true) {
            ssize_t n = read(client_fd, buffer, sizeof(buffer) - 1);
            if (n <= 0) break;          // 客户端断开或出错
            buffer[n] = '\0';
            string reply;
            if (strcmp(buffer, "status") == 0) {
                reply = "Working: " + to_string(getpid());
            } else if (strcmp(buffer, "stop") == 0) {
                reply = "Stopping...";
                write(client_fd, reply.c_str(), reply.size());
                // stop 命令仅断开当前连接，不退出守护进程
                break;
            } else {
                reply = "Unknown command!";
            }
            write(client_fd, reply.c_str(), reply.size());
        }
        close(client_fd);
    }
    close(fd);
    return 0;
}