#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include <string>
#include <cstring>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <fcntl.h>
#include <cerrno>
#include <fstream>
#include <csignal>

using namespace std;
void log(const string &text) {
    ofstream ofs("/data/local/tmp/log.txt", ios::app);
    ofs << text << " errno=" << errno << " (" << strerror(errno) << ")" << endl;
    ofs.close();
}

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

    // 1. 将 argv[1] 解析为端口号
    int port = stoi(argv[1]);
    if (port < 1024 || port > 65535) {
        log("Invalid port number");
        return 1;
    }

    // 2. 创建 TCP 套接字
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) {
        log("socket() failed");
        return 2;
    }

    // 3. 允许端口复用（防止 bind 失败）
    int opt = 1;
    if (setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt)) < 0) {
        log("setsockopt(SO_REUSEADDR) failed (non-critical)");
    }

    // 4. 绑定到 127.0.0.1:port
    struct sockaddr_in addr{};
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons(port);
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);  // 127.0.0.1

    if (::bind(fd, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        close(fd);
        log("bind() failed: " + to_string(errno));
        return 3;
    }

    // 5. 开始监听
    if (listen(fd, 1) < 0) {
        close(fd);
        log("listen() failed");
        return 4;
    }

    // 6. 主循环
    while (true) {
        int client_fd = accept(fd, nullptr, nullptr);
        if (client_fd < 0) {
            continue;
        }

        char buffer[256];
        while (true) {
            ssize_t n = read(client_fd, buffer, sizeof(buffer) - 1);
            if (n <= 0) break;
            buffer[n] = '\0';

            // 去除换行符（Kotlin 用 println 发送，末尾带 \n）
            char *p = strchr(buffer, '\n');
            if (p) *p = '\0';
            p = strchr(buffer, '\r');
            if (p) *p = '\0';

            string reply;
            if (strcmp(buffer, "status") == 0) {
                reply = "Working: " + to_string(getpid()) + "\n";
            } else if (strcmp(buffer, "stop") == 0) {
                reply = "Stopping...\n";
                write(client_fd, reply.c_str(), reply.size());
                close(client_fd);
                close(fd);
                exit(0);
            } else {
                reply = "Unknown command!\n";
            }
            write(client_fd, reply.c_str(), reply.size());
        }
        close(client_fd);
    }

    close(fd);
    return 0;
}