#include "auxiliary.h"
#include <libssh2.h>
#include <sys/wait.h>
#include <sys/prctl.h>
#include <dirent.h>

string capture_gesture;
string capture_command;
string record_gesture;
string record_command;
string share_gesture;
string share_command;
string ssh_options;
atomic_bool auto_encrypt = false;
// 自定义输出文件 mtime（epoch 秒；-1 = 禁用）。
// custom_mtime_raw 为原始输入串（"yyyy-M-d H:m"，config_mutex 保护，detail 输出用），
// 用户填什么就写什么，不做任何调整
atomic<long long> custom_mtime{-1};
string custom_mtime_raw;
string scrcpy_path;
vector<unsigned char> scrcpy_data;
// 通信密钥（main 从 stdin 读取后填充，之后只读；filter 线程加密输出使用）
vector<unsigned char> g_key;
atomic_bool filter_update = false;
mutex config_mutex;

// ===================== 超时看门狗状态 =====================
// deadline 为墙钟绝对时刻（秒，0=未启用），由 app 侧经 config/renew 下发；
// daemon 自持 uptime/wall 双锚点（密文存随机 tmp 文件）防冻结与回拨：
// - /proc/uptime 基于内核 boottime（含 suspend），root 改不动 jiffies——
//   冻结墙钟而 uptime 走表 → 漂移超限 → 引爆
// - 重启（uptime 回退）后墙钟必须至少前进 MIN_REBOOT_WALL_ELAPSED_SEC，
//   否则 = 冻结+重启绕过计时 → 引爆
atomic<long long> idle_deadline_sec{0};
string app_data_dir;            // config 下发，root 模式过期擦除范围（config_mutex）
atomic<int> app_uid{-1};
string g_self_path;             // 自身可执行文件路径（tmp 随机名或 apk 内路径）
string g_anchor_path;           // 看门狗锚点文件路径（密钥派生随机名）
mutex anchor_mutex;
// 录屏明文 tmp 路径（detonate/SIGTERM 时删除，防明文残留）。
// 双表示：std::string 供线程内加锁访问；定长缓冲 + sig_atomic 长度供
// 信号 handler 无锁读取（先清长度后拷贝，读取侧只见完整或空两种状态）
string g_record_tmp;            // record_mutex 保护
static char g_rec_tmp_buf[256];
static volatile sig_atomic_t g_rec_tmp_len = 0;
// 共享 server 落地文件路径（detonate 时删除）
string g_share_server_file;     // record_mutex 复用保护（低频写，无竞争热点）

// 录屏 tmp 路径维护（record_mutex 下调用）
static void set_record_tmp(const string &p) {
    g_rec_tmp_len = 0;
    if (p.size() < sizeof(g_rec_tmp_buf)) {
        memcpy(g_rec_tmp_buf, p.c_str(), p.size());
        g_rec_tmp_len = static_cast<sig_atomic_t>(p.size());
    }
    g_record_tmp = p;
}

static void clear_record_tmp() {
    g_rec_tmp_len = 0;
    g_record_tmp.clear();
}

/**
 * SIGTERM 兜底清理（kill-by-discovery 路径：app 侧信道不可用时按端口杀进程）。
 * 信号安全说明：锚点/自拷贝路径在 main 初始化后只读（其他线程不写），
 * 读取 .c_str()/.empty() 安全；g_key 同为初始化后只读（memset 与读并发
 * 最坏产生一次坏密文，进程随即退出，方向安全）；录屏 tmp 走 sig_atomic
 * 长度前缀缓冲
 */
static void term_handler(int) {
    if (g_rec_tmp_len > 0) {
        char buf[256];
        memcpy(buf, g_rec_tmp_buf, static_cast<size_t>(g_rec_tmp_len));
        buf[g_rec_tmp_len] = 0;
        remove(buf);
    }
    if (!g_anchor_path.empty()) remove(g_anchor_path.c_str());
    if (g_self_path.rfind("/data/local/tmp/", 0) == 0) {
        remove(g_self_path.c_str());
    }
    if (!g_key.empty()) {
        memset(g_key.data(), 0, g_key.size());
        g_key.clear();
    }
    _exit(0);
}

// 看门狗参数
static const int WATCHDOG_INTERVAL_SEC = 30;
// wall 与 uptime 漂移容差（秒）：正常 NTP 校正远小于此值
static const long long ANCHOR_FREEZE_TOLERANCE_SEC = 120;
// 真实重启至少耗费的墙钟时间（秒）：跨重启墙钟增量低于此值 = 冻结
static const long long MIN_REBOOT_WALL_ELAPSED_SEC = 20;

// ===================== 录屏 toggle 状态 =====================
// 匹配触发一次开始录制，再次触发发 SIGINT 停止（参考录屏磁贴服务）
mutex record_mutex;
atomic_bool record_running = false;
pid_t record_pid = -1;

// ===================== 屏幕共享运行状态 =====================
// 匹配触发一次开启，再次触发关闭，循环往复
static const char *RELAY_MARKER = "vendor.entry.Main";
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

// 隐藏随机临时文件名（'.' 前缀，ls 默认不可见）：自拷贝落地与锚点文件用
string random_hidden_tmp_name() {
    static mt19937 gen{random_device{}()};
    uniform_int_distribution<int> dist(20, 35);
    return "/data/local/tmp/." + getRandomString(dist(gen));
}

// 阻塞写全量（EINTR 容忍）
static void write_all_buffer(int fd, const char *buf, size_t len) {
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

// 读 /proc/uptime（内核 boottime，含 suspend；root 无法冻结 jiffies）。
// 失败返回 -1
static double read_proc_uptime() {
    FILE *f = fopen("/proc/uptime", "r");
    if (!f) return -1.0;
    double up = -1.0;
    if (fscanf(f, "%lf", &up) != 1) up = -1.0;
    fclose(f);
    return up;
}

// 锚点文件路径：密钥哈希前 8 字节 hex——仅持密钥者可推导文件名，
// tmp 目录扫描者无法定位（配合 '.' 前缀隐藏）
static string anchor_path_for_key(const vector<unsigned char> &key) {
    unsigned char md[EVP_MAX_MD_SIZE];
    unsigned int mdlen = 0;
    if (EVP_Digest(key.data(), key.size(), md, &mdlen, EVP_sha256(), nullptr) != 1 || mdlen < 8) {
        return "/data/local/tmp/.anchor";
    }
    char hex[17];
    for (int i = 0; i < 8; ++i) snprintf(hex + i * 2, 3, "%02x", md[i]);
    hex[16] = '\0';
    return string("/data/local/tmp/.") + hex;
}

// 锚点文件内容（加密前）："deadline,lastwall,lastuptime"
// exists=false：文件不存在（首启/被清理）；valid=false：存在但解密/解析失败 = 篡改
struct AnchorState {
    bool exists = false;
    bool valid = false;
    long long deadline = 0;
    long long lastwall = 0;
    double lastuptime = -1.0;
};

static AnchorState anchor_load() {
    AnchorState st;
    FILE *f = fopen(g_anchor_path.c_str(), "rb");
    if (!f) return st;                       // 不存在
    st.exists = true;
    vector<unsigned char> data;
    char buf[4096];
    size_t n;
    while ((n = fread(buf, 1, sizeof(buf), f)) > 0) {
        data.insert(data.end(), buf, buf + n);
    }
    fclose(f);
    string plain = decrypt_data(g_key, data);
    if (plain.empty()) return st;            // 存在但解不开 = 篡改
    auto parts = split(plain, ',');
    if (parts.size() != 3) return st;
    st.deadline = strtoll(parts[0].c_str(), nullptr, 10);
    st.lastwall = strtoll(parts[1].c_str(), nullptr, 10);
    st.lastuptime = strtod(parts[2].c_str(), nullptr);
    if (st.lastwall <= 0 || st.lastuptime < 0) return st;
    st.valid = true;
    return st;
}

// 原子写锚点（tmp + rename）：并发写最坏"后写者胜"，绝不产生半截文件
// （半截文件下次加载解密失败会被判篡改引爆——原子性防误炸）
static void anchor_save(long long deadline, long long wall, double uptime) {
    string plain = to_string(deadline) + "," + to_string(wall) + "," +
                   to_string(uptime);
    vector<unsigned char> enc = encrypt_data(g_key, plain);
    if (enc.empty()) return;
    string tmp = g_anchor_path + ".t";
    FILE *f = fopen(tmp.c_str(), "wb");
    if (!f) return;
    fwrite(enc.data(), 1, enc.size(), f);
    fclose(f);
    chmod(tmp.c_str(), 0600);
    rename(tmp.c_str(), g_anchor_path.c_str());
}

// 自拷贝：把 /proc/self/exe 复制到随机 tmp 路径
static bool copy_self_to(const string &dst) {
    ifstream in("/proc/self/exe", ios::binary);
    if (!in) return false;
    ofstream out(dst, ios::binary | ios::trunc);
    if (!out) return false;
    out << in.rdbuf();
    out.flush();
    bool ok = out.good();
    out.close();
    if (ok) chmod(dst.c_str(), 0700);
    return ok;
}

// "yyyy-M-d H:m" 原始串 → epoch 秒（按设备本地时区，与 Kotlin 侧
// ZoneId.systemDefault() 语义一致；bionic 无 TZ 时读 persist.sys.timezone）。
// strptime 逐字符严格匹配且校验尾部无残留；空/格式非法 → -1（禁用）。
// 防崩兜底而非校验：真正的格式把关在 UI
long long parse_defined_mtime(const string &text) {
    if (text.empty()) return -1;
    struct tm t{};
    t.tm_isdst = -1;                            // DST 交由系统判定
    const char *rest = strptime(text.c_str(), "%Y-%m-%d %H:%M", &t);
    if (rest == nullptr || *rest != '\0') return -1;
    time_t secs = mktime(&t);
    return secs < 0 ? -1 : static_cast<long long>(secs);
}

// 自定义输出文件 mtime：原样写入用户指定的 epoch 秒。
// atime 设为同一假值——"截图后从未查看"是常态（创建即最后访问），
// 留真实 atime 会泄露实际生成时刻；ctime 由内核垄断无法伪造，接受残留。
// 必须在最终落盘（含加密回写）之后调用；
// 失败静默——只是时间戳，不影响产物
void set_custom_mtime(const string &path) {
    long long base = custom_mtime.load();
    if (base < 0) return;
    timespec ts[2]{};
    ts[0].tv_sec = static_cast<time_t>(base);    // atime = 假 mtime
    ts[1].tv_sec = static_cast<time_t>(base);    // mtime = 用户指定值
    utimensat(AT_FDCWD, path.c_str(), ts, 0);
}

// 流式 AES-256-GCM 文件加密：输出 nonce(12) + 密文 + tag(16)，
// 与 Kotlin 端 EncryptManager 软件加密互通（软件解密输入通信密码即可解开）
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

// 进程是否存活（kill 0 探测）。SIGCHLD 为 SIG_IGN 时子进程被自动回收，
// waitpid 恒返回 ECHILD——所有等待一律改为存在性轮询。
// PID 复用会造成极小概率的"多等一会"，方向安全（不会提前收尾）
static bool process_alive(pid_t pid) {
    if (pid <= 0) return false;
    if (kill(pid, 0) == 0) return true;
    return errno == EPERM;   // 存在但非我们的进程（被复用）也视为存活
}

// 等待进程退出（轮询版：SIGCHLD=SIG_IGN 下 waitpid 不可用）
void wait_process(pid_t pid) {
    while (process_alive(pid)) {
        usleep(100000);
    }
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

/**
 * 清扫 app 侧共享（磁贴/页面启动的 relay 与其 sh 守护循环）。
 * 顺序关键：先杀守护 sh——否则 server 被杀后 1s 内被循环重新拉起；
 * 再杀 relay server；最后清理停止标记与脚本文件。
 * sh 匹配模式锚定行尾（\.sh$）：sh -c 包装进程自身命令行以引号收尾，
 * 不会被匹配（pkill 自杀的经典足枪规避）。
 * 使用场景：detonate（看门狗引爆——用户停止使用后 relay 仍可能在推流）
 * 与 purge 命令（胁迫销毁——app 侧 stopScreenShare 无特权时的兜底）。
 */
static void purge_app_side_share() {
    pid_t p1 = spawn_shell_command("pkill -f '\\.w_[A-Za-z0-9_-]+\\.sh$'");
    if (p1 > 0) {
        for (int i = 0; i < 30 && process_alive(p1); ++i) usleep(100000);
    }
    kill_relay_processes();
    pid_t p2 = spawn_shell_command("rm -f /data/local/tmp/.s_* /data/local/tmp/.w_*.sh");
    if (p2 > 0) {
        for (int i = 0; i < 50 && process_alive(p2); ++i) usleep(100000);
    }
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
    // 记录落地路径（detonate 时删除；supervisor 退出时清除）
    {
        lock_guard<mutex> lock(record_mutex);
        g_share_server_file = server_file;
    }
    SshTunnel tunnel;
    if (ssh_enabled) {
        if (!tunnel.start(ssh_host, ssh_port, ssh_user, ssh_pass, ssh_remote_port, local_port)) {
            remove(server_file.c_str());
            {
                lock_guard<mutex> lock(record_mutex);
                g_share_server_file.clear();
            }
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
        // SIGCHLD=SIG_IGN 下 waitpid 不可用：存在性轮询探测退出
        while (process_alive(pid)) {
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
    {
        lock_guard<mutex> lock(record_mutex);
        g_share_server_file.clear();
    }
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

// ===================== 超时看门狗 =====================

/**
 * 引爆：静默销毁，绝不输出任何可观测信号。
 * 触发源：deadline 到期 / 墙钟冻结 / 墙钟回拨 / 锚点被篡改。
 * 按 daemon 自身 uid 分级：
 * - root：擦除 app 数据目录（含 hw_key.bin/tink_prefs → DK 永久不可恢复；
 *   Keystore 条目虽存但已无密文可解）+ tmp 产物 + relay；
 * - shell：app 私有目录不可达，仅能清理 tmp 产物与 relay——
 *   app 数据由 app 侧 checkIdleExpired 在下次启动补刀。
 * 末尾 memset 清密钥后 _exit（跳过析构，规避与命令线程的锁交互）。
 */
[[noreturn]] static void detonate() {
    // 1. 停自身管理的共享：置停止标志 + 杀 server 进程
    share_stop_requested.store(true);
    pid_t spid = share_server_pid.load();
    if (spid > 0) kill(spid, SIGKILL);
    // 清扫 app 侧共享（磁贴/页面启动的 relay 与守护 sh）：
    // 用户停止使用后 app 侧共享仍可能独立运行（sh 循环不依赖 app 进程），
    // 引爆时必须一并停止推流——且必须先杀 sh 再杀 server（防自动重启）
    purge_app_side_share();

    // 2. 停录屏并清明文 tmp（SIGINT 让 screenrecord 收尾，但明文必须删而非加密）
    {
        lock_guard<mutex> lock(record_mutex);
        if (record_running.load() && record_pid > 0) kill(record_pid, SIGKILL);
        if (!g_record_tmp.empty()) {
            remove(g_record_tmp.c_str());
            g_record_tmp.clear();
        }
        if (!g_share_server_file.empty()) {
            remove(g_share_server_file.c_str());
            g_share_server_file.clear();
        }
    }

    // 3. root：擦除 app 数据目录（单引号包裹防路径注入；来源为加密信道）。
    // dataDir 多数设备返回 /data/user/0/<pkg>（bind 挂载，与 /data/data/<pkg>
    // 同一 inode），两种前缀都必须放行——只认 /data/data/ 会让 root 模式
    // 引爆时静默跳过数据擦除（复核发现的实际 bug）
    string data_dir;
    {
        lock_guard<mutex> lock(config_mutex);
        data_dir = app_data_dir;
    }
    bool dir_ok = data_dir.rfind("/data/data/", 0) == 0 ||
                  data_dir.rfind("/data/user/", 0) == 0;
    if (getuid() == 0 && !data_dir.empty() && dir_ok) {
        pid_t pid = spawn_shell_command("rm -rf '" + data_dir + "'");
        if (pid > 0) {
            // 等待完成（上限 30s，rm -rf 数据目录通常秒级）
            for (int i = 0; i < 300 && process_alive(pid); ++i) usleep(100000);
        }
    }

    // 4. 清理共享守护脚本与标记文件（shell 可达范围）
    pid_t cleaner = spawn_shell_command(
            "rm -f /data/local/tmp/.s_* /data/local/tmp/.w_*.sh");
    if (cleaner > 0) {
        for (int i = 0; i < 50 && process_alive(cleaner); ++i) usleep(100000);
    }

    // 5. 删锚点与自拷贝（自拷贝 unlink 于运行中安全：inode 存活至进程退出）
    if (!g_anchor_path.empty()) remove(g_anchor_path.c_str());
    if (g_self_path.rfind("/data/local/tmp/", 0) == 0) {
        remove(g_self_path.c_str());
    }

    // 6. 密钥清零后立即退出
    if (!g_key.empty()) {
        memset(g_key.data(), 0, g_key.size());
        g_key.clear();
    }
    _exit(0);
}

/**
 * 看门狗主循环：每 WATCHDOG_INTERVAL_SEC 检查一次。
 * 双锚点（wall + /proc/uptime）交叉校验：
 * - 同一开机（uptime 单调）：uptime 走表而墙钟不走（漂移超容差）= 冻结 → 引爆；
 *   墙钟倒退超容差 = 回拨 → 引爆
 * - 重启（uptime 回退）：墙钟必须至少前进 MIN_REBOOT_WALL_ELAPSED_SEC，
 *   否则 = 冻结 + 重启绕过 → 引爆；墙钟倒退 = 回拨 → 引爆
 * - deadline 到期 → 引爆
 * 锚点密文存随机 tmp 文件（路径由密钥派生，无密钥者不可定位/不可伪造）。
 */
static void watchdog_main() {
    while (true) {
        this_thread::sleep_for(chrono::seconds(WATCHDOG_INTERVAL_SEC));
        double up = read_proc_uptime();
        if (up < 0) continue;                 // /proc 读取失败：跳过本轮（无法判定）
        long long wall = static_cast<long long>(time(nullptr));
        if (wall <= 0) continue;

        lock_guard<mutex> lock(anchor_mutex);
        AnchorState st = anchor_load();
        if (!st.exists) {
            // 首启/锚点被清理：以当前 config 的 deadline 初始化基线
            anchor_save(idle_deadline_sec.load(), wall, up);
            continue;
        }
        if (!st.valid) {
            // 锚点存在但解不开 = 无密钥者改写过 = 篡改 → 引爆
            detonate();
        }

        if (up >= st.lastuptime) {
            // 同一开机：双锚点交叉校验
            double up_elapsed = up - st.lastuptime;
            long long wall_elapsed = wall - st.lastwall;
            if (wall_elapsed < -ANCHOR_FREEZE_TOLERANCE_SEC) {
                detonate();                    // 墙钟大幅倒退 = 回拨
            }
            if (up_elapsed - static_cast<double>(wall_elapsed) >
                static_cast<double>(ANCHOR_FREEZE_TOLERANCE_SEC)) {
                detonate();                    // uptime 走表而墙钟不走 = 冻结
            }
        } else {
            // uptime 回退 = 重启过：墙钟必须至少前进一次真实重启的时长
            if (wall < st.lastwall) {
                detonate();                    // 跨重启墙钟倒退 = 回拨
            }
            if (wall - st.lastwall < MIN_REBOOT_WALL_ELAPSED_SEC) {
                detonate();                    // 跨重启墙钟近乎未走 = 冻结绕过
            }
        }

        // 生效死线 = max(锚点持久值, 内存值)：renew 先更新内存再写文件，
        // 内存恒 ≥ 文件；watchdog 周期回写若拿旧值会覆盖掉新续期（提前引爆）
        long long effective_deadline = st.deadline > idle_deadline_sec.load()
                                       ? st.deadline : idle_deadline_sec.load();

        // deadline 到期
        if (effective_deadline > 0 && wall >= effective_deadline) {
            detonate();
        }

        // 通过检查：仅当墙钟严格前进时推进基线——冻结/回拨期间基线不动，
        // 漂移跨轮累积直至容差引爆。旧实现每轮回写基线：单轮漂移至多
        // ~WATCHDOG_INTERVAL_SEC(30s)，永远达不到 120s 容差，冻结检测
        // 形同虚设（扣押设备+冻结墙钟 = 看门狗完全失效）
        if (wall > st.lastwall) {
            anchor_save(effective_deadline, wall, up);
        }
        // wall == lastwall（冻结）或 wall < lastwall（回拨）：保留旧基线，
        // 下一轮 up_elapsed 继续增长 / wall_elapsed 继续为负，累积判爆
    }
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
                // 记录明文 tmp 路径（detonate/SIGTERM 时删除防残留；收尾完成后清除）
                if (encrypt) {
                    lock_guard<mutex> lock(record_mutex);
                    set_record_tmp(i.back());
                }
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
                // 后加密回写并清状态，不阻塞 filter 线程继续匹配。
                // 自定义 mtime 启用时纯明文 screencap 也需要收尾（否则直接落盘的文件
                // 没有机会改时间戳），故条件追加 mtime 开关
                bool want_mtime = custom_mtime.load() >= 0;
                if (encrypt || is_record || want_mtime) {
                    const string &output = i.back();
                    thread([pid, encrypt, is_record, output, target, want_mtime]() {
                        wait_process(pid);
                        if (encrypt) {
                            encrypt_file(g_key, output, target);
                            // 加密失败也删明文：宁可丢失不留明文（与 Kotlin 端一致）
                            remove(output.c_str());
                        }
                        // 最终产物已落盘（明文=target 直写 / 密文=encrypt_file 回写），
                        // 此后无写入，mtime 定格；失败静默（函数内部吞错）
                        if (want_mtime) set_custom_mtime(target);
                        if (is_record) {
                            lock_guard<mutex> lock(record_mutex);
                            record_pid = -1;
                            record_running.store(false);
                            clear_record_tmp();
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
    if (argc < 2) {
        return 1;
    }
    char *endptr;
    errno = 0;
    long port = strtol(argv[1], &endptr, 10);
    if (errno != 0 || endptr == argv[1] || *endptr != '\0' || port < 1024 || port > 65535) {
        return 1;
    }
    // 密钥经 stdin 递交（32 字节裸密钥，由 App 侧 Keystore 包裹的随机 DK 解包而来）：
    // - 不经 argv，避免 cmdline 泄露（exec 到读取之间无暴露窗口）；
    // - 必须在 daemonize() 之前读取——daemonize 会将 stdin 重定向到 /dev/null。
    vector<unsigned char> dk(KEY_LEN);
    size_t got = 0;
    while (got < dk.size()) {
        ssize_t n = read(STDIN_FILENO, dk.data() + got, dk.size() - got);
        if (n <= 0) {
            return 1;
        }
        got += static_cast<size_t>(n);
    }

    // ---- 去指纹：自拷贝到随机 tmp 路径再 exec ----
    // argv[0] 原为 /data/app/.../lib/arm64/libnetsvc.so（含包名，ps 一眼定位）。
    // 自拷贝后进程 cmdline 只剩随机路径；密钥经管道转交给拷贝体，不经 argv。
    // 已在 tmp 路径下运行（拷贝体的再入）则跳过，防止无限复制。
    g_self_path = argv[0];
    if (g_self_path.rfind("/data/local/tmp/", 0) != 0) {
        string tmp_copy = random_hidden_tmp_name();
        if (copy_self_to(tmp_copy)) {
            int pfd[2];
            if (pipe(pfd) == 0) {
                pid_t child = fork();
                if (child == 0) {
                    // 拷贝体：stdin 接管管道，重新走 main（argv[0] 已是 tmp
                    // 路径，跳过自拷贝分支，防无限复制）
                    dup2(pfd[0], STDIN_FILENO);
                    close(pfd[1]);
                    execl(tmp_copy.c_str(), tmp_copy.c_str(), argv[1], (char *) nullptr);
                    _exit(127);
                }
                if (child > 0) {
                    // 原进程：转交密钥后立即退出——调用方（execWithStdin）的
                    // waitFor 快速返回，拷贝体自行完成 daemonize 与端口监听
                    close(pfd[0]);
                    write_all_buffer(pfd[1],
                                     reinterpret_cast<const char *>(dk.data()), dk.size());
                    close(pfd[1]);
                    _exit(0);
                }
                // fork 失败：关管道、清拷贝、就地继续运行
                close(pfd[0]);
                close(pfd[1]);
            }
            // 管道失败：清拷贝，就地运行（隐蔽性降级，功能不受影响）
            remove(tmp_copy.c_str());
        }
        // 拷贝失败：就地运行
    }

    daemonize();
    // comm 随机化（ps 默认显示列）：15 字符上限（含 NUL 共 16）
    {
        char name[16];
        string rn = getRandomString(15);
        memcpy(name, rn.c_str(), 15);
        name[15] = '\0';
        prctl(PR_SET_NAME, name, 0, 0, 0);
    }
    signal(SIGPIPE, SIG_IGN);
    // SIGTERM = kill-by-discovery 兜底路径（app 侧信道不可用时按端口杀）：
    // handler 做最小清理（录屏明文 tmp / 锚点 / 自拷贝 / 密钥）后退出
    signal(SIGTERM, term_handler);
    // SIGCHLD 忽略 = 子进程自动回收（spawn 的 sh 不留僵尸）；
    // 代价：waitpid 不可用，所有等待走 process_alive 轮询（见上）
    signal(SIGCHLD, SIG_IGN);
    g_key = std::move(dk);
    const vector<unsigned char> &key = g_key;
    __builtin_memset(argv[1], 0, strlen(argv[1]));
    // 看门狗锚点路径：密钥派生（仅持密钥者可定位）
    g_anchor_path = anchor_path_for_key(key);
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
    // 看门狗与 filter 线程并列：超时/冻结/回拨/锚点篡改任一命中即静默销毁。
    // detach 模式（stop 命令正常收尾；detonate 走 _exit 不经线程汇合）
    thread(watchdog_main).detach();
    while (true) {
        int client_fd = accept(fd, nullptr, nullptr);
        if (client_fd < 0) continue;
        else fcntl(client_fd, F_SETFD, FD_CLOEXEC);
        // 信道 DoS 硬化：本地任意进程可 connect 固定端口后不发数据——
        // 单线程 accept 循环会阻塞在 recv_encrypted 的首个 read 上，
        // 后续所有命令（stop/purge/renew）排队不可达。读超时后按坏
        // 连接关闭；5s 远大于回环正常往返（毫秒级），合法命令不受影响
        struct timeval rcv_to{5, 0};
        setsockopt(client_fd, SOL_SOCKET, SO_RCVTIMEO, &rcv_to, sizeof(rcv_to));
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
            string cap_cmd, rec_cmd, sha_cmd, ssh, mtime_raw;
            bool encrypt, scrcpy_ready;
            long long mtime;
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
                mtime = custom_mtime.load();
                mtime_raw = custom_mtime_raw;
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
            // 看门狗死线（0 = 未启用；过期判定以锚点内持久化值为准）
            {
                lock_guard<mutex> lock(anchor_mutex);
                AnchorState ast = anchor_load();
                long long dl = ast.valid ? ast.deadline : idle_deadline_sec.load();
                reply_plain.append(
                        string("idle_deadline= ") +
                        (dl > 0 ? to_string(dl) : string("Disabled")) + "\n");
            }
            // 自定义时间戳：启用输出用户输入的原始串，禁用输出 Disabled
            reply_plain.append(
                    "timestamp= " + (mtime >= 0 ? mtime_raw : string("Disabled")) + "\n");
            reply_plain.append("\x1C" + to_string(get_current_timestamp_seconds()));
        } else if (command == "stop" || command == "purge") {
            // purge = stop + 清扫 app 侧共享（磁贴/页面启动的 relay 与守护 sh）：
            // 胁迫销毁序列调用——app 侧 stopScreenShare 依赖 shell 特权，
            // Shizuku 断连时清不掉，由持特权的 daemon 兜底。
            // 普通 stop 不清扫（用户可能正运行 app 侧共享，不应被牵连）
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
            // purge：清扫 app 侧共享（在自身 supervisor 收尾后进行）
            if (command == "purge") {
                purge_app_side_share();
            }
            // 用户主动 stop：清锚点（下次启动按 config 重新初始化）与自拷贝，
            // 密钥清零后退出
            if (!g_anchor_path.empty()) remove(g_anchor_path.c_str());
            if (g_self_path.rfind("/data/local/tmp/", 0) == 0) {
                remove(g_self_path.c_str());
            }
            if (!g_key.empty()) {
                memset(g_key.data(), 0, g_key.size());
                g_key.clear();
            }
            exit(0);
        } else if (command == "detach") {
            reply_plain = "Detaching\x1C" + to_string(get_current_timestamp_seconds());
            send_encrypted(client_fd, key, reply_plain);
            close(client_fd);
            close(fd);
            // detach 关闭通信端口后 renew 永远无法送达，旧死线照常到期会在
            // 用户正常使用期间引爆（数据误毁）。因此 detach 同时解除死线：
            // 内存清零 + 锚点持久化为未启用。下次 startDaemon 经 syncConfig
            // 重新武装（detach 需持密钥经加密信道，是用户主动行为）
            idle_deadline_sec.store(0);
            {
                lock_guard<mutex> lock(anchor_mutex);
                double up = read_proc_uptime();
                long long wall = static_cast<long long>(time(nullptr));
                if (up >= 0 && wall > 0) anchor_save(0, wall, up);
            }
            // 看门狗继续运行（冻结/回拨/锚点篡改检测不因 detach 失效）
            while (true) sleep(5);
        } else if (command.rfind("renew:", 0) == 0) {
            // 超时续期：app 侧 touchIdle 捎带下发新的绝对死线（墙钟秒）。
            // 更新内存死线并重置锚点基线（续期 = 近期有效联络，漂移从此刻重算）
            long long dl = strtoll(command.c_str() + 6, nullptr, 10);
            if (dl > 0) {
                idle_deadline_sec.store(dl);
                lock_guard<mutex> lock(anchor_mutex);
                double up = read_proc_uptime();
                long long wall = static_cast<long long>(time(nullptr));
                if (up >= 0 && wall > 0) anchor_save(dl, wall, up);
            }
            reply_plain = "fine\x1C" + to_string(get_current_timestamp_seconds());
        } else if (command.rfind("config", 0) == 0) {
            string data = command.substr(6);
            bool success = false;
            if (!data.empty()) {
                auto partsD = split(data, '\x1D');
                if (partsD.size() == 4) {
                    auto processGesture = [](const string &gesture) -> string {
                        if (gesture.empty())return "";
                        auto patterns = split(gesture, '\x1F');
                        // 边界检查：少于 3 段（优先级/tag/正则）直接拒绝，防越界 UB
                        if (patterns.size() < 3) return "";
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
                    // others 边界安全访问（字段序：relayPath/autoEncrypt/mtime/
                    // idleLimit/idleDeadline/appDataDir/appUid，旧字段缺省兼容）
                    auto getOther = [&others](size_t idx) -> string {
                        return others.size() > idx ? others[idx] : string();
                    };
                    string cap_gs = processGesture(filters[0]);
                    string rec_gs = processGesture(filters[1]);
                    string sha_gs = processGesture(filters[2]);
                    // 看门狗信任链字段（越界/非法 → 0 = 未启用，不引爆）
                    long long cfg_idle_limit =
                            strtoll(getOther(3).c_str(), nullptr, 10);
                    long long cfg_idle_deadline =
                            strtoll(getOther(4).c_str(), nullptr, 10);
                    string cfg_app_data_dir = getOther(5);
                    int cfg_app_uid =
                            static_cast<int>(strtol(getOther(6).c_str(), nullptr, 10));
                    lock_guard<mutex> lock(config_mutex);
                    try {
                        if (scrcpy_path != getOther(0) && filesystem::exists(getOther(0))) {
                            auto size = static_cast<streamsize>(filesystem::file_size(getOther(0)));
                            ifstream file(getOther(0), ios::binary);
                            if (file) {
                                scrcpy_data.clear();
                                scrcpy_data.resize(static_cast<size_t>(size));
                                file.read(reinterpret_cast<char *>(scrcpy_data.data()), size);
                                if (file.gcount() != size) {
                                    scrcpy_data.clear();
                                } else {
                                    scrcpy_path = getOther(0);
                                }
                            }
                        }
                    } catch (...) {
                        scrcpy_data.clear();
                    }
                    auto_encrypt = getOther(1) == "true";
                    // 第 3 段：自定义 mtime 原始串（"yyyy-M-d H:m"），daemon 自行换算。
                    // 换算失败（空/格式非法）→ 禁用，这是防崩兜底而非校验
                    custom_mtime_raw = getOther(2);
                    custom_mtime.store(parse_defined_mtime(custom_mtime_raw));
                    ssh_options = std::move(partsD[2]);
                    capture_gesture = std::move(cap_gs);
                    record_gesture = std::move(rec_gs);
                    share_gesture = std::move(sha_gs);
                    capture_command = std::move(arguments[0]);
                    record_command = std::move(arguments[1]);
                    share_command = std::move(arguments[2]);
                    // 超时看门狗：死线与擦除范围（deadline>0 时重置锚点基线）
                    app_data_dir = std::move(cfg_app_data_dir);
                    app_uid.store(cfg_app_uid);
                    idle_deadline_sec.store(cfg_idle_deadline > 0 ? cfg_idle_deadline : 0);
                    (void) cfg_idle_limit;
                    filter_update.store(true);
                    success = true;
                }
                if (success && idle_deadline_sec.load() > 0) {
                    // config 携带新死线：重置锚点基线（config = 有效联络）
                    lock_guard<mutex> lock(anchor_mutex);
                    double up = read_proc_uptime();
                    long long wall = static_cast<long long>(time(nullptr));
                    if (up >= 0 && wall > 0) {
                        anchor_save(idle_deadline_sec.load(), wall, up);
                    }
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