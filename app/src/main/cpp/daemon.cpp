#include "auxiliary.h"
#include <libssh2.h>
#include <sys/wait.h>
#include <dirent.h>

string capture_gesture;
string capture_command;
string record_gesture;
string record_command;
string share_gesture;
string share_command;
string ssh_options;
atomic_bool auto_encrypt = false;
string scrcpy_path;
vector<unsigned char> scrcpy_data;
// 通信密钥（main 派生后填充，之后只读；filter 线程加密输出使用）
vector<unsigned char> g_key;
atomic_bool filter_update = false;
mutex config_mutex;

// ===================== 录屏 toggle 状态 =====================
// 匹配触发一次开始录制，再次触发发 SIGINT 停止（参考录屏磁贴服务）
mutex record_mutex;
atomic_bool record_running = false;
pid_t record_pid = -1;

// ===================== 屏幕共享运行状态 =====================
// 匹配触发一次开启，再次触发关闭，循环往复
static const char *RELAY_MARKER = "fake.screenshot.core.Relay";
atomic_bool share_running = false;
atomic_bool share_stop_requested = false;
atomic<pid_t> share_server_pid{-1};
static thread share_supervisor;

void daemonize() {
    pid_t pid = fork();
    if (pid < 0) _exit(EXIT_FAILURE);
    if (pid > 0) _exit(EXIT_SUCCESS);
    if (setsid() < 0) _exit(EXIT_FAILURE);
    pid = fork();
    if (pid < 0) _exit(EXIT_FAILURE);
    if (pid > 0) _exit(EXIT_SUCCESS);
    if (chdir("/") < 0) _exit(EXIT_FAILURE);
    umask(0);
    int fd = open("/dev/null", O_RDWR);
    if (fd != -1) {
        dup2(fd, STDIN_FILENO);
        dup2(fd, STDOUT_FILENO);
        dup2(fd, STDERR_FILENO);
        if (fd > STDERR_FILENO) close(fd);
    }
}

// ===================== 通用工具 =====================

// 随机临时文件名：/data/local/tmp/ 下长度随机（20..35）的字母数字串，
// 不含任何工具特征字样（隐藏性要求）
string random_tmp_name() {
    static mt19937 gen{random_device{}()};
    uniform_int_distribution<int> dist(20, 35);
    return "/data/local/tmp/" + getRandomString(dist(gen));
}

// 流式 AES-256-GCM 文件加密：输出 nonce(12) + 密文 + tag(16)，
// 与 Kotlin 端 EncryptManager 软件加密格式互通（软件解密输入通信密码即可解开）
bool encrypt_file(const vector<unsigned char> &key, const string &plain_path,
                  const string &cipher_path) {
    if (key.empty()) return false;
    FILE *in = fopen(plain_path.c_str(), "rb");
    if (!in) return false;
    FILE *out = fopen(cipher_path.c_str(), "wb");
    if (!out) {
        fclose(in);
        return false;
    }
    bool ok = false;
    unsigned char nonce[NONCE_LEN];
    EVP_CIPHER_CTX *ctx = nullptr;
    do {
        if (RAND_bytes(nonce, NONCE_LEN) != 1) break;
        ctx = EVP_CIPHER_CTX_new();
        if (!ctx) break;
        if (EVP_EncryptInit_ex(ctx, EVP_aes_256_gcm(), nullptr, key.data(), nonce) != 1) break;
        if (EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_IVLEN, NONCE_LEN, nullptr) != 1) break;
        if (fwrite(nonce, 1, NONCE_LEN, out) != NONCE_LEN) break;
        unsigned char buf[65536];
        unsigned char enc[sizeof(buf) + TAG_LEN];
        size_t n;
        int len;
        bool io_failed = false;
        while ((n = fread(buf, 1, sizeof(buf), in)) > 0) {
            if (EVP_EncryptUpdate(ctx, enc, &len, buf, static_cast<int>(n)) != 1) {
                io_failed = true;
                break;
            }
            if (fwrite(enc, 1, static_cast<size_t>(len), out) != static_cast<size_t>(len)) {
                io_failed = true;
                break;
            }
        }
        if (io_failed || ferror(in)) break;
        if (EVP_EncryptFinal_ex(ctx, enc, &len) != 1) break;
        if (fwrite(enc, 1, static_cast<size_t>(len), out) != static_cast<size_t>(len)) break;
        unsigned char tag[TAG_LEN];
        if (EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_GET_TAG, TAG_LEN, tag) != 1) break;
        if (fwrite(tag, 1, TAG_LEN, out) != TAG_LEN) break;
        ok = true;
    } while (false);
    if (ctx) EVP_CIPHER_CTX_free(ctx);
    fclose(in);
    if (fclose(out) != 0) ok = false;
    if (!ok) remove(cipher_path.c_str());
    return ok;
}

// spawn shell 命令，stdout/stderr 重定向 /dev/null（防止管道写满阻塞子进程）
pid_t spawn_shell_command(const string &command) {
    vector<string> args = {"/system/bin/sh", "-c", command};
    vector<char *> argv;
    argv.reserve(args.size() + 1);
    for (auto &a: args) argv.push_back(const_cast<char *>(a.c_str()));
    argv.push_back(nullptr);
    posix_spawn_file_actions_t actions;
    posix_spawn_file_actions_init(&actions);
    posix_spawn_file_actions_addopen(&actions, STDOUT_FILENO, "/dev/null", O_WRONLY, 0);
    posix_spawn_file_actions_addopen(&actions, STDERR_FILENO, "/dev/null", O_WRONLY, 0);
    posix_spawnattr_t attr;
    posix_spawnattr_init(&attr);
    pid_t pid = -1;
    int ret = posix_spawn(&pid, argv[0], &actions, &attr, argv.data(), environ);
    posix_spawnattr_destroy(&attr);
    posix_spawn_file_actions_destroy(&actions);
    if (ret != 0) return -1;
    return pid;
}

// 等待子进程退出（处理 EINTR）
void wait_process(pid_t pid) {
    int status;
    while (waitpid(pid, &status, 0) < 0 && errno == EINTR) {}
}

// ===================== 残留 server 进程扫描/清理 =====================

// 扫描 /proc 找出 cmdline 含 Relay 入口类的进程（app 进程重启后标志丢失时以此为准）
vector<pid_t> find_relay_pids() {
    vector<pid_t> pids;
    DIR *d = opendir("/proc");
    if (!d) return pids;
    struct dirent *e;
    char path[64], buf[4096];
    while ((e = readdir(d)) != nullptr) {
        if (e->d_type != DT_DIR) continue;
        int pid = static_cast<int>(strtol(e->d_name, nullptr, 10));
        if (pid <= 0 || pid == getpid()) continue;
        snprintf(path, sizeof(path), "/proc/%d/cmdline", pid);
        int fd = open(path, O_RDONLY);
        if (fd < 0) continue;
        ssize_t n = read(fd, buf, sizeof(buf) - 1);
        close(fd);
        if (n <= 0) continue;
        buf[n] = '\0';
        for (ssize_t i = 0; i < n; ++i) if (buf[i] == '\0') buf[i] = ' ';
        if (strstr(buf, RELAY_MARKER) != nullptr) pids.push_back(static_cast<pid_t>(pid));
    }
    closedir(d);
    return pids;
}

// 先 SIGINT 让 server 走 CleanUp 正常收尾，1s 后仍存活则 SIGKILL 兜底
void kill_relay_processes() {
    auto pids = find_relay_pids();
    if (pids.empty()) return;
    for (pid_t pid: pids) kill(pid, SIGINT);
    for (int i = 0; i < 10; ++i) {
        if (find_relay_pids().empty()) return;
        usleep(100000);
    }
    for (pid_t pid: find_relay_pids()) kill(pid, SIGKILL);
}

// ===================== SSH 隧道（libssh2 远程端口转发） =====================
// 等价 ssh -R remote_port:127.0.0.1:local_port，供接收端通过 SSH 服务器接入。
// session 以非阻塞模式轮询 accept；每个转发连接由两个线程双向搬运，
// 所有 libssh2 调用统一持锁（session 非线程安全）。

struct SshTunnel {
    int sock = -1;
    LIBSSH2_SESSION *session = nullptr;
    LIBSSH2_LISTENER *listener = nullptr;
    mutex mtx;
    atomic_bool stopping{false};
    atomic<int> active_bridges{0};
    thread acceptor;
    int local_port = 0;

    struct BridgeCtx {
        LIBSSH2_CHANNEL *ch;
        int local_fd;
        SshTunnel *tunnel;
        atomic<int> alive{2};
    };

    static int connect_with_timeout(const string &host, int port, int timeout_ms) {
        int fd = socket(AF_INET, SOCK_STREAM, 0);
        if (fd < 0) return -1;
        int flags = fcntl(fd, F_GETFL, 0);
        fcntl(fd, F_SETFL, flags | O_NONBLOCK);
        struct sockaddr_in addr{};
        addr.sin_family = AF_INET;
        addr.sin_port = htons(static_cast<uint16_t>(port));
        if (inet_pton(AF_INET, host.c_str(), &addr.sin_addr) != 1) {
            struct hostent *he = gethostbyname(host.c_str());
            if (!he || he->h_addrtype != AF_INET || !he->h_addr_list[0]) {
                close(fd);
                return -1;
            }
            memcpy(&addr.sin_addr, he->h_addr_list[0], he->h_length);
        }
        int r = connect(fd, (struct sockaddr *) &addr, sizeof(addr));
        if (r != 0 && errno != EINPROGRESS) {
            close(fd);
            return -1;
        }
        if (r != 0) {
            struct pollfd pfd{fd, POLLOUT, 0};
            if (poll(&pfd, 1, timeout_ms) <= 0) {
                close(fd);
                return -1;
            }
            int err = 0;
            socklen_t len = sizeof(err);
            getsockopt(fd, SOL_SOCKET, SO_ERROR, &err, &len);
            if (err != 0) {
                close(fd);
                return -1;
            }
        }
        fcntl(fd, F_SETFL, flags);
        return fd;
    }

    static void write_all_fd(int fd, const char *buf, size_t len) {
        size_t off = 0;
        while (off < len) {
            ssize_t w = write(fd, buf + off, len - off);
            if (w < 0) {
                if (errno == EINTR) continue;
                return;
            }
            off += static_cast<size_t>(w);
        }
    }

    bool start(const string &host, int port, const string &user, const string &password,
               int remote_port, int local_server_port) {
        local_port = local_server_port;
        if (libssh2_init(0) != 0) return false;
        do {
            sock = connect_with_timeout(host, port, 8000);
            if (sock < 0) break;
            session = libssh2_session_init();
            if (!session) break;
            libssh2_session_set_blocking(session, 1);
            if (libssh2_session_handshake(session, sock) != 0) break;
            if (libssh2_userauth_password(session, user.c_str(), password.c_str()) != 0) break;
            listener = libssh2_channel_forward_listen_ex(session, nullptr, remote_port, nullptr,
                                                         16);
            if (!listener) break;
            libssh2_session_set_blocking(session, 0);
            stopping.store(false);
            acceptor = thread(&SshTunnel::accept_loop, this);
            return true;
        } while (false);
        cleanup();
        return false;
    }

    void accept_loop() {
        while (!stopping.load()) {
            LIBSSH2_CHANNEL *ch = nullptr;
            {
                lock_guard<mutex> lock(mtx);
                ch = libssh2_channel_forward_accept(listener);
            }
            if (ch) {
                spawn_bridge(ch);
            } else {
                usleep(200000);
            }
        }
    }

    void spawn_bridge(LIBSSH2_CHANNEL *ch) {
        int local_fd = connect_with_timeout("127.0.0.1", local_port, 3000);
        if (local_fd < 0) {
            lock_guard<mutex> lock(mtx);
            libssh2_channel_free(ch);
            return;
        }
        // 读超时让 l2c 线程能周期性检查停止标志
        struct timeval tv{0, 200000};
        setsockopt(local_fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
        auto *ctx = new BridgeCtx{ch, local_fd, this};
        active_bridges.fetch_add(1);
        thread(&SshTunnel::bridge_c2l, ctx).detach();
        thread(&SshTunnel::bridge_l2c, ctx).detach();
    }

    // 远端 → 本地 server
    static void bridge_c2l(BridgeCtx *ctx) {
        SshTunnel *t = ctx->tunnel;
        char buf[16384];
        while (!t->stopping.load()) {
            ssize_t n;
            {
                lock_guard<mutex> lock(t->mtx);
                n = libssh2_channel_read(ctx->ch, buf, sizeof(buf));
            }
            if (n > 0) {
                write_all_fd(ctx->local_fd, buf, static_cast<size_t>(n));
            } else if (n == 0) {
                shutdown(ctx->local_fd, SHUT_WR);
                break;
            } else if (n == LIBSSH2_ERROR_EAGAIN) {
                usleep(50000);
            } else {
                break;
            }
        }
        finish_bridge(ctx);
    }

    // 本地 server → 远端
    static void bridge_l2c(BridgeCtx *ctx) {
        SshTunnel *t = ctx->tunnel;
        char buf[16384];
        while (!t->stopping.load()) {
            ssize_t n = recv(ctx->local_fd, buf, sizeof(buf), 0);
            if (n > 0) {
                size_t off = 0;
                bool error = false;
                while (off < static_cast<size_t>(n)) {
                    if (t->stopping.load()) {
                        error = true;
                        break;
                    }
                    ssize_t w;
                    {
                        lock_guard<mutex> lock(t->mtx);
                        w = libssh2_channel_write(ctx->ch, buf + off, static_cast<size_t>(n) - off);
                    }
                    if (w > 0) {
                        off += static_cast<size_t>(w);
                    } else if (w == LIBSSH2_ERROR_EAGAIN) {
                        usleep(20000);
                    } else {
                        error = true;
                        break;
                    }
                }
                if (error) break;
            } else if (n == 0) {
                break;
            } else {
                if (errno == EINTR) continue;
                if (errno == EAGAIN || errno == EWOULDBLOCK) continue;
                break;
            }
        }
        {
            lock_guard<mutex> lock(t->mtx);
            libssh2_channel_send_eof(ctx->ch);
        }
        finish_bridge(ctx);
    }

    static void finish_bridge(BridgeCtx *ctx) {
        if (ctx->alive.fetch_sub(1) == 1) {
            SshTunnel *t = ctx->tunnel;
            {
                lock_guard<mutex> lock(t->mtx);
                libssh2_channel_free(ctx->ch);
            }
            close(ctx->local_fd);
            delete ctx;
            t->active_bridges.fetch_sub(1);
        }
    }

    void stop() {
        if (sock < 0 && !session) return;
        stopping.store(true);
        if (acceptor.joinable()) acceptor.join();
        // bridge 线程在停止标志 + 200ms 超时机制下快速退出，最多等 3s
        for (int i = 0; i < 30 && active_bridges.load() > 0; ++i) usleep(100000);
        cleanup();
    }

    void cleanup() {
        if (listener) {
            libssh2_channel_forward_cancel(listener);
            listener = nullptr;
        }
        if (session) {
            libssh2_session_disconnect(session, "");
            libssh2_session_free(session);
            session = nullptr;
        }
        if (sock >= 0) {
            close(sock);
            sock = -1;
        }
        libssh2_exit();
    }
};

// ===================== 屏幕共享子系统的实现 =====================

// 快照当前共享配置（config 线程与 supervisor 之间的数据竞争防护）
bool build_share_snapshot(string &cmd, int &local_port, bool &ssh_enabled, string &ssh_host,
                          int &ssh_port, string &ssh_user, string &ssh_pass, int &ssh_remote_port,
                          vector<unsigned char> &server_data) {
    string share_cmd_copy, ssh_copy;
    {
        lock_guard<mutex> lock(config_mutex);
        if (share_command.empty() || scrcpy_data.empty()) return false;
        share_cmd_copy = share_command;
        ssh_copy = ssh_options;
        server_data = scrcpy_data;
    }
    auto sp = split(ssh_copy, '\x1F');
    ssh_enabled = !sp.empty() && sp[0] == "true";
    if (ssh_enabled) {
        ssh_host = sp.size() > 1 ? sp[1] : "";
        ssh_port = sp.size() > 2 ? static_cast<int>(strtol(sp[2].c_str(), nullptr, 10)) : 22;
        ssh_user = sp.size() > 3 ? sp[3] : "";
        ssh_pass = sp.size() > 4 ? sp[4] : "";
        ssh_remote_port = sp.size() > 5 ? static_cast<int>(strtol(sp[5].c_str(), nullptr, 10)) : 0;
    }
    // 从 base 命令提取 tcp_port（server 的本地监听端口）
    size_t p = share_cmd_copy.find("tcp_port=");
    if (p == string::npos) return false;
    local_port = static_cast<int>(strtol(share_cmd_copy.c_str() + p + 9, nullptr, 10));
    if (local_port < 1024 || local_port > 65535) return false;
    // 远程端口未配置（0 或越界）时回退本地端口，与 app 端逻辑一致
    if (ssh_remote_port < 1024 || ssh_remote_port > 65535) ssh_remote_port = local_port;
    // server 落地文件用随机名替换 Kotlin 端的占位符（隐藏性：文件名无特征）
    string random_name = random_tmp_name();
    cmd = replace_all(share_cmd_copy, "FullRandomName",
                      random_name.substr(random_name.rfind('/') + 1));
    // 拼接成完整 shell 命令：base + 空格分隔的 key=value 参数
    auto parts = split(cmd, '\x1F');
    string full_cmd;
    for (const auto &part: parts) {
        if (part.empty()) continue;
        if (!full_cmd.empty()) full_cmd += ' ';
        full_cmd += part;
    }
    // SSH 模式下 server 只监听回环，防止局域网直连绕过隧道
    if (ssh_enabled && full_cmd.find("tcp_local_only=") == string::npos) {
        full_cmd += " tcp_local_only=true";
    }
    cmd = full_cmd;
    return !cmd.empty();
}

// supervisor 线程：落地 server → 建隧道 → 拉起并守护 server 进程。
// server 异常退出 1s 后自动重启；连续 3 次快速退出（<30s）说明无法正常启动，放弃。
void share_supervisor_main() {
    string cmd, ssh_host, ssh_user, ssh_pass;
    int local_port = 0, ssh_port = 22, ssh_remote_port = 0;
    bool ssh_enabled = false;
    vector<unsigned char> server_data;
    if (!build_share_snapshot(cmd, local_port, ssh_enabled, ssh_host, ssh_port, ssh_user,
                              ssh_pass, ssh_remote_port, server_data)) {
        share_running.store(false);
        return;
    }
    string server_file = random_tmp_name();
    {
        ofstream f(server_file, ios::binary | ios::trunc);
        if (!f) {
            share_running.store(false);
            return;
        }
        f.write(reinterpret_cast<const char *>(server_data.data()),
                static_cast<streamsize>(server_data.size()));
        if (!f.good()) {
            remove(server_file.c_str());
            share_running.store(false);
            return;
        }
    }
    chmod(server_file.c_str(), 0600);
    SshTunnel tunnel;
    if (ssh_enabled) {
        if (!tunnel.start(ssh_host, ssh_port, ssh_user, ssh_pass, ssh_remote_port, local_port)) {
            remove(server_file.c_str());
            share_running.store(false);
            return;
        }
    }
    int fast_exit = 0;
    while (!share_stop_requested.load()) {
        pid_t pid = spawn_shell_command(cmd);
        if (pid <= 0) {
            this_thread::sleep_for(chrono::seconds(1));
            continue;
        }
        share_server_pid.store(pid);
        auto start_time = chrono::steady_clock::now();
        bool sigint_sent = false;
        auto sigint_time = chrono::steady_clock::now();
        while (true) {
            int status;
            pid_t r = waitpid(pid, &status, WNOHANG);
            if (r == pid) break;
            if (r < 0) break;
            if (share_stop_requested.load()) {
                if (!sigint_sent) {
                    kill(pid, SIGINT);
                    sigint_sent = true;
                    sigint_time = chrono::steady_clock::now();
                } else if (chrono::steady_clock::now() - sigint_time > chrono::seconds(1)) {
                    kill(pid, SIGKILL);
                }
            }
            usleep(200000);
        }
        share_server_pid.store(-1);
        if (share_stop_requested.load()) break;
        auto elapsed = chrono::duration_cast<chrono::seconds>(
                chrono::steady_clock::now() - start_time).count();
        if (elapsed >= 30) fast_exit = 0;
        else ++fast_exit;
        if (fast_exit >= 3) break;
        this_thread::sleep_for(chrono::seconds(1));
    }
    tunnel.stop();
    remove(server_file.c_str());
    share_running.store(false);
}

// 共享 toggle：未运行 → 清理残留并启动；运行中 → 停止（supervisor 异步完成收尾）
void toggle_share() {
    if (share_running.load()) {
        share_stop_requested.store(true);
        pid_t pid = share_server_pid.load();
        if (pid > 0) kill(pid, SIGINT);
        share_running.store(false);
        return;
    }
    // daemon 未管理共享：先清理可能残留的 server（daemon 重启场景）
    kill_relay_processes();
    if (share_supervisor.joinable()) share_supervisor.join();
    share_stop_requested.store(false);
    share_running.store(true);
    share_supervisor = thread(share_supervisor_main);
}

void filter_thread_main() {
    class Filter {
    public:
        void initialize() {
            valid = !(origin_gesture.empty() || origin_command.empty());
            if (!valid)return;
            auto slices = split(origin_gesture, '\x1F');
            priority = slices[0].empty() ? ' ' : slices[0][0];
            tag = slices[1];
            msg_regex = regex(slices[2]);
        }

        [[nodiscard]]int execute_command() const {
            if (!valid)return -1;
            auto i = split(origin_command, '\x1F');
            auto first = i.front();
            if (first == "screencap" || first == "screenrecord") {
                bool is_record = first == "screenrecord";
                bool encrypt = auto_encrypt.load();
                auto file_name = getCurrentDateString() + "_" + getRandomString(4) + i.back();
                i.pop_back();
                filesystem::create_directories(i.back());
                string target = i.back() + "/" + file_name;
                // 加密模式：明文先落 /data/local/tmp/ 随机名（与 Kotlin 端一致），完成后加密回写
                i.back() = encrypt ? random_tmp_name() : target;
                string command;
                for (const auto &j: i) {
                    command.append(j);
                    command += ' ';
                }
                command.pop_back();
                vector<string> args = {"/system/bin/sh", "-c", command};
                vector<char *> argv;
                argv.reserve(args.size() + 1);
                for (auto &a: args) argv.push_back(const_cast<char *>(a.c_str()));
                argv.push_back(nullptr);
                posix_spawnattr_t attr;
                posix_spawnattr_init(&attr);
                pid_t pid;
                int ret = posix_spawn(&pid, argv[0], nullptr, &attr, argv.data(), environ);
                posix_spawnattr_destroy(&attr);
                if (ret != 0) return ret;
                if (is_record) {
                    lock_guard<mutex> lock(record_mutex);
                    record_pid = pid;
                    record_running.store(true);
                }
                // 收尾线程：等命令结束（screencap 秒级，screenrecord 跑满 time-limit）
                // 后加密回写并清状态，不阻塞 filter 线程继续匹配
                if (encrypt || is_record) {
                    const string &output = i.back();
                    thread([pid, encrypt, is_record, output, target]() {
                        wait_process(pid);
                        if (encrypt) {
                            encrypt_file(g_key, output, target);
                            // 加密失败也删明文：宁可丢失不留明文（与 Kotlin 端一致）
                            remove(output.c_str());
                        }
                        if (is_record) {
                            lock_guard<mutex> lock(record_mutex);
                            record_pid = -1;
                            record_running.store(false);
                        }
                    }).detach();
                }
                return 0;
            } else {
                return -1;
            }
        }

        bool valid = false;
        char priority = ' ';
        string tag;
        regex msg_regex;
        string origin_gesture;
        string origin_command;
    };
    class PipeManager {
    public:
        ~PipeManager() { stop(); }

        bool start(const string &cmd) {
            stop();
            vector<string> tokens = split(cmd, ' ');
            vector<char *> args;
            args.reserve(tokens.size() + 1);
            for (auto &t: tokens) args.push_back(const_cast<char *>(t.c_str()));
            args.push_back(nullptr);

            if (pipe(pipe_fd) == -1) return false;
            pid = fork();
            if (pid == -1) {
                close(pipe_fd[0]);
                close(pipe_fd[1]);
                return false;
            }
            if (pid == 0) {
                dup2(pipe_fd[1], STDOUT_FILENO);
                close(pipe_fd[0]);
                close(pipe_fd[1]);
                execvp(args[0], args.data());
                exit(1);
            }
            close(pipe_fd[1]);
            pipe_fd[1] = -1;
            // 将读端转换为 FILE*
            f = fdopen(pipe_fd[0], "r");
            return f != nullptr;
        }

        void stop() {
            if (pid > 0) {
                kill(pid, SIGTERM);
                // 等待最多 1 秒，简单轮询
                for (int i = 0; i < 10; ++i) {
                    if (kill(pid, 0) != 0) break;  // 进程已不存在
                    usleep(100000);
                }
                if (kill(pid, 0) == 0) kill(pid, SIGKILL);
                pid = -1;
            }
            if (f) {
                fclose(f);
                f = nullptr;
                pipe_fd[0] = -1;   // 标记已关闭
            }
            // 以防万一，关闭 fd
            if (pipe_fd[0] != -1) {
                close(pipe_fd[0]);
                pipe_fd[0] = -1;
            }
            if (pipe_fd[1] != -1) {
                close(pipe_fd[1]);
                pipe_fd[1] = -1;
            }
        }

        [[nodiscard]] FILE *get_file() const { return f; }

    private:
        pid_t pid = -1;
        int pipe_fd[2] = {-1, -1};
        FILE *f = nullptr;
    };
    static PipeManager proc;
    Filter capture_filter, record_filter, share_filter;
    auto parse_log_line = [](const string &line) -> tuple<char, string, string> {
        char priority = 'V';
        string tag, msg;
        size_t slash = line.find('/');
        if (slash != string::npos && slash > 0) {
            priority = line[0];
            size_t colon = line.find(':', slash);
            if (colon != string::npos) {
                tag = line.substr(slash + 1, colon - slash - 1);
                msg = line.substr(colon + 1);
                if (!msg.empty() && msg[0] == ' ') msg.erase(0, 1);
            }
        }
        return {priority, tag, msg};
    };
    auto get_logcat_command = [&capture_filter, &record_filter, &share_filter]() -> string {
        string cmd = "logcat -v tag";
        map<string, int> tag_min_priority;
        auto priority_char_to_int = [](const char &c) -> int {
            switch (c) {
                case 'V':
                    return 2;
                case 'D':
                    return 3;
                case 'I':
                    return 4;
                case 'W':
                    return 5;
                case 'E':
                    return 6;
                case 'F':
                    return 7;
                default:
                    return 2; // 默认为 VERBOSE
            }
        };
        auto int_to_priority_char = [](const int &pri) -> char {
            switch (pri) {
                case 2:
                    return 'V';
                case 3:
                    return 'D';
                case 4:
                    return 'I';
                case 5:
                    return 'W';
                case 6:
                    return 'E';
                case 7:
                    return 'F';
                default:
                    return 'V';
            }
        };
        auto parse_gesture = [&](const string &gesture) -> pair<string, int> {
            if (gesture.empty()) return {"", 2};  // 默认 VERBOSE
            auto parts = split(gesture, '\x1F');
            if (parts.size() < 3) return {"", 2};
            string tag = parts[1];
            int lv = 2;
            if (!parts[0].empty()) {
                lv = priority_char_to_int(parts[0][0]);
            }
            return {tag, lv};
        };
        auto process_gesture = [&](const string &gesture) {
            if (gesture.empty()) return;
            auto [tag, lv] = parse_gesture(gesture);
            if (tag.empty()) return;
            auto it = tag_min_priority.find(tag);
            if (it == tag_min_priority.end()) {
                tag_min_priority[tag] = lv;
            } else {
                if (lv < it->second) {
                    it->second = lv;
                }
            }
        };
        process_gesture(capture_filter.origin_gesture);
        process_gesture(record_filter.origin_gesture);
        process_gesture(share_filter.origin_gesture);
        if (tag_min_priority.empty()) {
            return cmd;
        }
        for (const auto &pair: tag_min_priority) {
            cmd += " " + pair.first + ":" + int_to_priority_char(pair.second);
        }
        cmd += " *:S";
        return cmd;
    };
    while (true) {
        if (filter_update.load()) {
            proc.stop();
            {
                lock_guard<mutex> lock(config_mutex);
                capture_filter.origin_gesture = capture_gesture;
                capture_filter.origin_command = capture_command;
                record_filter.origin_gesture = record_gesture;
                record_filter.origin_command = record_command;
                share_filter.origin_gesture = share_gesture;
                share_filter.origin_command = share_command;
                filter_update.store(false);
            }
            capture_filter.initialize();
            record_filter.initialize();
            share_filter.initialize();
        }
        if (!(capture_filter.valid || record_filter.valid || share_filter.valid)) {
            this_thread::sleep_for(chrono::milliseconds(200));
            continue;
        }
        if (!proc.get_file()) {
            if (!proc.start(get_logcat_command())) {
                this_thread::sleep_for(chrono::milliseconds(200));
                continue;
            }
        }
        FILE *pipe = proc.get_file();
        int fd = fileno(pipe);
        struct pollfd pfd{fd, POLLIN, 0};
        char buffer[4096] = {0};
        bool pipe_active = true;
        while (pipe_active) {
            if (filter_update.load()) {
                pipe_active = false;
                break;
            }
            int poll_ret = poll(&pfd, 1, 200);
            if (poll_ret < 0) {
                pipe_active = false;
                break;
            } else if (poll_ret == 0) {
                continue;
            }
            if (fgets(buffer, sizeof(buffer), pipe) == nullptr) {
                pipe_active = false;
                break;
            }
            string line(buffer);
            if (!line.empty() && line.back() == '\n') line.pop_back();
            //logcat: I/VSyncReactor: startPeriodTransitionInternal newPeriod:11111111
            auto [priority, tag, msg] = parse_log_line(line);
            if (capture_filter.valid && tag == capture_filter.tag) {
                if ((priority == capture_filter.priority || capture_filter.priority == ' ') &&
                    regex_match(msg, capture_filter.msg_regex)) {
                    (void) capture_filter.execute_command();
                }
            }
            if (record_filter.valid && tag == record_filter.tag) {
                if ((priority == record_filter.priority || record_filter.priority == ' ') &&
                    regex_match(msg, record_filter.msg_regex)) {
                    // 录屏 toggle：录制中 → SIGINT 停止（收尾线程完成加密与状态清理）
                    bool stopping_record = false;
                    {
                        lock_guard<mutex> lock(record_mutex);
                        if (record_running.load()) {
                            if (record_pid > 0) kill(record_pid, SIGINT);
                            stopping_record = true;
                        }
                    }
                    if (!stopping_record) (void) record_filter.execute_command();
                }
            }
            if (share_filter.valid && tag == share_filter.tag) {
                if ((priority == share_filter.priority || share_filter.priority == ' ') &&
                    regex_match(msg, share_filter.msg_regex)) {
                    // 共享 toggle：未运行开启，运行中关闭，循环往复
                    toggle_share();
                }
            }
        }
        if (!pipe_active && !filter_update.load()) {
            proc.stop();
        }
    }
}

int main(int argc, char *argv[]) {
    if (argc < 3) {
        return 1;
    }
    char *endptr;
    errno = 0;
    long port = strtol(argv[1], &endptr, 10);
    if (errno != 0 || endptr == argv[1] || *endptr != '\0' || port < 1024 || port > 65535) {
        return 1;
    }
    string password = argv[2];
    daemonize();
    signal(SIGPIPE, SIG_IGN);
    signal(SIGCHLD, SIG_IGN);
    g_key = derive_key(password);
    const vector<unsigned char> &key = g_key;
    __builtin_memset(argv[2], 0, strlen(argv[2]));
    __builtin_memset(argv[1], 0, strlen(argv[1]));
    int fd = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (fd < 0) {
        return 2;
    }

    int opt = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    struct sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(port);
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);

    if (::bind(fd, (struct sockaddr *) &addr, sizeof(addr)) < 0) {
        close(fd);
        return 3;
    }

    if (listen(fd, 1) < 0) {
        close(fd);
        return 4;
    }
    thread(filter_thread_main).detach();
    while (true) {
        int client_fd = accept(fd, nullptr, nullptr);
        if (client_fd < 0) continue;
        else fcntl(client_fd, F_SETFD, FD_CLOEXEC);
        string plaintext = recv_encrypted(client_fd, key);
        if (plaintext.empty()) {
            close(client_fd);
            continue;
        }
        size_t sep = plaintext.find('\x1C');
        if (sep == string::npos) {
            close(client_fd);
            continue;
        }
        string command = plaintext.substr(0, sep);
        string ts_str = plaintext.substr(sep + 1);
        long long timestamp;
        try {
            timestamp = stoll(ts_str);
        } catch (...) {
            close(client_fd);
            continue;
        }
        if (!is_timestamp_valid(timestamp)) {
            close(client_fd);
            continue;
        }
        string reply_plain;
        if (command == "status") {
            reply_plain = "Working\x1C" + to_string(get_current_timestamp_seconds());
        } else if (command == "detail") {
            auto processGestureDisplay = [](const string &gesture) -> string {
                if (gesture.empty())return "Disabled";
                string result;
                auto i = split(gesture, '\x1F');
                result += "LV=[" + i[0] + "]:";
                result += "TAG=[" + i[1] + "]:";
                result += "MSG=[" + i[2] + "]";
                return result;
            };
            auto processCommandDisplay = [](const string &command) -> string {
                string result = "[";
                result += replace_all(command, "\x1F", "] [");
                result += ']';
                return result;
            };
            auto processSshDisplay = [](const string &option) -> string {
                auto options = split(option, '\x1F');
                auto get = [&options](size_t idx) -> string {
                    return options.size() > idx ? options[idx] : string("");
                };
                string result;
                result += "[Enabled= " + get(0) + "] ";
                result += "[Address= " + get(1) + "] ";
                result += "[Port= " + get(2) + "] ";
                result += "[Name= " + get(3) + "] ";
                result += "[Password= " + get(4) + "] ";
                result += "[RemotePort= " + get(5) + "]";
                return result;
            };
            auto processOtherOptions = [](const string &text, const bool &state) -> string {
                string result = state ? "True" : "False";
                return text + result + '\n';
            };
            string cap_gs, rec_gs, sha_gs;
            string cap_cmd, rec_cmd, sha_cmd, ssh;
            bool encrypt, scrcpy_ready;
            {
                lock_guard<mutex> lock(config_mutex);
                cap_gs = capture_gesture;
                rec_gs = record_gesture;
                sha_gs = share_gesture;
                cap_cmd = capture_command;
                rec_cmd = record_command;
                sha_cmd = share_command;
                ssh = ssh_options;
                encrypt = auto_encrypt;
                scrcpy_ready = !scrcpy_data.empty();
            }
            reply_plain = "ScreenshotFakerDaemon:\n";
            reply_plain.append(
                    "uid=" + to_string(getuid()) + ", pid=" + to_string(getpid()) + ", ppid=" +
                    to_string(getppid()) + "\n");
            reply_plain.append(
                    "capture_gesture: " + processGestureDisplay(cap_gs) + "\n");
            reply_plain.append(
                    "capture_commands:\n" + processCommandDisplay(cap_cmd) + "\n");
            reply_plain.append("record_gesture: " + processGestureDisplay(rec_gs) + "\n");
            reply_plain.append("record_commands:\n" + processCommandDisplay(rec_cmd) + "\n");
            reply_plain.append("share_gesture: " + processGestureDisplay(sha_gs) + "\n");
            reply_plain.append("share_commands:\n" + processCommandDisplay(sha_cmd) + "\n");
            reply_plain.append("ssh_options:\n" + processSshDisplay(ssh) + "\n");
            reply_plain.append("other_options:\n");
            reply_plain.append(processOtherOptions("auto_encrypt= ", encrypt));
            reply_plain.append(processOtherOptions("relay_state= ", scrcpy_ready));
            reply_plain.append(processOtherOptions("share_state= ", share_running.load()));
            reply_plain.append("\x1C" + to_string(get_current_timestamp_seconds()));
        } else if (command == "stop") {
            reply_plain = "Stopping\x1C" + to_string(get_current_timestamp_seconds());
            send_encrypted(client_fd, key, reply_plain);
            close(client_fd);
            // 等待进行中的录屏完成收尾（SIGINT + 等加密回写，避免明文残留在 tmp），上限 10s
            {
                pid_t pid;
                {
                    lock_guard<mutex> lock(record_mutex);
                    pid = record_running.load() ? record_pid : -1;
                }
                if (pid > 0) {
                    kill(pid, SIGINT);
                    for (int i = 0; i < 100 && record_running.load(); ++i) usleep(100000);
                }
            }
            // 停止屏幕共享：SIGKILL 快速终止 server，supervisor 完成隧道与文件清理
            if (share_running.load()) {
                share_stop_requested.store(true);
                pid_t pid = share_server_pid.load();
                if (pid > 0) kill(pid, SIGKILL);
            }
            if (share_supervisor.joinable()) share_supervisor.join();
            close(fd);
            exit(0);
        } else if (command == "detach") {
            reply_plain = "Detaching\x1C" + to_string(get_current_timestamp_seconds());
            send_encrypted(client_fd, key, reply_plain);
            close(client_fd);
            close(fd);
            while (true) sleep(5);
        } else if (command.rfind("config", 0) == 0) {
            string data = command.substr(6);
            bool success = false;
            if (!data.empty()) {
                auto partsD = split(data, '\x1D');
                if (partsD.size() == 4) {
                    auto processGesture = [](const string &gesture) -> string {
                        if (gesture.empty())return "";
                        auto patterns = split(gesture, '\x1F');
                        auto result = patterns[0] + "\x1F" + patterns[1] + "\x1F";
                        if (!isRegexValid(patterns[2]))return "";
                        result += patterns[2];
                        return result;
                    };
                    const string &filterPart = partsD[0];
                    const string &argumentPart = partsD[1];
                    const string &otherOptions = partsD[3];
                    vector<string> others = split(otherOptions, '\x1F');
                    vector<string> filters = split(filterPart, '\x1E');
                    vector<string> arguments = split(argumentPart, '\x1E');
                    string cap_gs = processGesture(filters[0]);
                    string rec_gs = processGesture(filters[1]);
                    string sha_gs = processGesture(filters[2]);
                    lock_guard<mutex> lock(config_mutex);
                    try {
                        if (scrcpy_path != others[0] && filesystem::exists(others[0])) {
                            auto size = static_cast<streamsize>(filesystem::file_size(others[0]));
                            ifstream file(others[0], ios::binary);
                            if (file) {
                                scrcpy_data.clear();
                                scrcpy_data.resize(static_cast<size_t>(size));
                                file.read(reinterpret_cast<char *>(scrcpy_data.data()), size);
                                if (file.gcount() != size) {
                                    scrcpy_data.clear();
                                } else {
                                    scrcpy_path = others[0];
                                }
                            }
                        }
                    } catch (...) {
                        scrcpy_data.clear();
                    }
                    auto_encrypt = others[1] == "true";
                    ssh_options = std::move(partsD[2]);
                    capture_gesture = std::move(cap_gs);
                    record_gesture = std::move(rec_gs);
                    share_gesture = std::move(sha_gs);
                    capture_command = std::move(arguments[0]);
                    record_command = std::move(arguments[1]);
                    share_command = std::move(arguments[2]);
                    filter_update.store(true);
                    success = true;
                }
            }
            reply_plain = (success ? "fine\x1C" : "failed\x1C") +
                          to_string(get_current_timestamp_seconds());
        } else {
            close(client_fd);
            continue;
        }
        send_encrypted(client_fd, key, reply_plain);
        close(client_fd);
    }
    close(fd);
    return 0;
}

long long get_current_timestamp_seconds() {
    return chrono::duration_cast<chrono::seconds>(
            chrono::system_clock::now().time_since_epoch()
    ).count();
}

bool is_timestamp_valid(long long ts) {
    long long now = get_current_timestamp_seconds();
    long long diff = now - ts;
    if (diff < 0) diff = -diff;
    return diff <= TIME_SKEW_SECONDS;
}

vector<unsigned char> derive_key(const string &password) {
    vector<unsigned char> key(KEY_LEN);
    PKCS5_PBKDF2_HMAC(password.c_str(), static_cast<int>(password.size()),
                      (const unsigned char *) SALT.c_str(), static_cast<int>(SALT.size()),
                      PBKDF2_ITERATIONS, EVP_sha256(), KEY_LEN, key.data());
    return key;
}

vector<unsigned char> encrypt_data(const vector<unsigned char> &key, const string &plaintext) {
    unsigned char nonce[NONCE_LEN];
    RAND_bytes(nonce, NONCE_LEN);

    EVP_CIPHER_CTX *ctx = EVP_CIPHER_CTX_new();
    EVP_EncryptInit_ex(ctx, EVP_aes_256_gcm(), nullptr, key.data(), nonce);
    EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_IVLEN, NONCE_LEN, nullptr);

    vector<unsigned char> ciphertext(plaintext.size() + TAG_LEN);
    int len;
    EVP_EncryptUpdate(ctx, ciphertext.data(), &len,
                      (const unsigned char *) plaintext.data(), static_cast<int>(plaintext.size()));
    int ciphertext_len = len;
    EVP_EncryptFinal_ex(ctx, ciphertext.data() + len, &len);
    ciphertext_len += len; // len 通常为 0，所以 ciphertext_len = plaintext.size()

    // 将 Tag 写入密文之后（位置 ciphertext_len）
    EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_GET_TAG, TAG_LEN,
                        ciphertext.data() + ciphertext_len);
    EVP_CIPHER_CTX_free(ctx);

    // 返回 nonce + 完整密文（含 Tag）
    vector<unsigned char> result(NONCE_LEN + ciphertext_len + TAG_LEN);
    memcpy(result.data(), nonce, NONCE_LEN);
    memcpy(result.data() + NONCE_LEN, ciphertext.data(), ciphertext_len + TAG_LEN);
    return result;
}

string decrypt_data(const vector<unsigned char> &key, const vector<unsigned char> &data) {
    if (data.size() < NONCE_LEN + TAG_LEN) {
        return "";
    }
    const unsigned char *nonce = data.data();
    const unsigned char *ciphertext = data.data() + NONCE_LEN;
    size_t ciphertext_len = data.size() - NONCE_LEN;

    EVP_CIPHER_CTX *ctx = EVP_CIPHER_CTX_new();
    EVP_DecryptInit_ex(ctx, EVP_aes_256_gcm(), nullptr, key.data(), nonce);
    EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_TAG, TAG_LEN,
                        (void *) (ciphertext + ciphertext_len - TAG_LEN));

    vector<unsigned char> plaintext(ciphertext_len);
    int len;
    int ret = EVP_DecryptUpdate(ctx, plaintext.data(), &len,
                                ciphertext, static_cast<int>(ciphertext_len) - TAG_LEN);
    if (ret != 1) {
        EVP_CIPHER_CTX_free(ctx);
        return "";
    }
    int plaintext_len = len;
    ret = EVP_DecryptFinal_ex(ctx, plaintext.data() + len, &len);
    if (ret != 1) {
        EVP_CIPHER_CTX_free(ctx);
        return "";
    }
    plaintext_len += len;
    EVP_CIPHER_CTX_free(ctx);
    string result((char *) plaintext.data(), plaintext_len);
    return result;
}

bool send_encrypted(int fd, const vector<unsigned char> &key, const string &plaintext) {
    vector<unsigned char> encrypted = encrypt_data(key, plaintext);
    uint32_t len = htonl(encrypted.size());
    if (write(fd, &len, 4) != 4) return false;
    if (write(fd, encrypted.data(), encrypted.size()) != (ssize_t) encrypted.size()) return false;
    return true;
}

string recv_encrypted(int fd, const vector<unsigned char> &key) {
    uint32_t len = 0;
    ssize_t r = 0;
    size_t bytes_read = 0;
    unsigned char len_buf[4];

    // 循环读取直到读满 4 字节
    while (bytes_read < 4) {
        r = read(fd, len_buf + bytes_read, 4 - bytes_read);
        if (r <= 0) {
            return "";
        }
        bytes_read += r;
    }

    len = ntohl(*(uint32_t *) len_buf);
    if (len == 0 || len > 65536) {
        return "";
    }

    vector<unsigned char> encrypted(len);
    bytes_read = 0;
    while (bytes_read < len) {
        r = read(fd, encrypted.data() + bytes_read, len - bytes_read);
        if (r <= 0) {
            return "";
        }
        bytes_read += r;
    }
    return decrypt_data(key, encrypted);
}