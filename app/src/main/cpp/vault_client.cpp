// vault_client.cpp —— vault 进程启动器与帧管道（编入 libmemsys.so）
//
// 职责边界：只做 spawn 与字节搬运，不解析协议（帧编解码在 Kotlin
// VaultClient，协议执行在 vault 进程）——本文件不含任何密码学知识。
//
// 启动（double-fork + execve）：
// - socketpair 建立于 fork 前：vault 侧 dup2 到 fd 0/1——无文件系统路径、
//   无 listen，第三方进程物理上无法插入；主进程死亡 = EOF = vault 自动
//   清密钥退出（与主进程同生死，无任何"关闭"开关）
// - double-fork：vault 挂 init 名下，主进程不是其父（app 崩溃时 vault
//   由 EOF 联动退出而非随父进程组被杀）；child1 setsid 后退出，grandchild
//   无控制终端
// - 子侧 close_range 清空继承 fd（防 app 的 DataStore/socket 句柄泄漏给
//   vault）；execve 环境传空表（vault 不依赖环境，environ 不含 app 数据）
// - vault 侧自身的 dumpable=0 + TracerPid 自检见 vault.cpp main——本侧
//   在 fork 前不做 dumpable 操作（app 进程的 dumpable 由 guard.cpp 管理，
//   子进程继承，fork→exec 窗口同 uid attach 已被继承的 dumpable=0 阻断；
//   debug 构建 app 可 attach，由 vault 侧 TracerPid 自检兜底）
//
// JNI 入口：VaultClient（defense 包）——本文件的 JNI 符号是该类契约
// 的一部分。库加载与 guard 共库（libmemsys.so，System.loadLibrary 幂等）。

#include <jni.h>

#include <poll.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <unistd.h>

#include <cerrno>
#include <cstdint>
#include <cstring>
#include <pthread.h>

static pthread_mutex_t g_vault_mutex = PTHREAD_MUTEX_INITIALIZER;
static int g_vault_fd = -1;

// close_range(3, ~0)：Linux 5.9+（裸 syscall——Bionic 的 close_range
// 包装在部分 API 级别头文件不可见，内核号在 uapi 头恒可用）；老内核
// ENOSYS 回落循环 close（fd 上限 1024 兜底——继承自 app 的句柄超出该
// 范围属异常环境，残留只影响 vault 侧资源占用，不影响安全——那些 fd
// 未 dup2，vault 用不到）
static void close_inherited_fds() {
    if (syscall(__NR_close_range, 3u, ~0u, 0u) != 0) {
        for (int i = 3; i < 1024; i++) close(i);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_fake_screenshot_defense_VaultClient_nativeStart(
        JNIEnv* env, jobject /*thiz*/, jstring binPath, jstring filesDir) {
    pthread_mutex_lock(&g_vault_mutex);
    if (g_vault_fd >= 0) {
        pthread_mutex_unlock(&g_vault_mutex);
        return JNI_TRUE;  // 幂等：已启动
    }
    const char* bin = env->GetStringUTFChars(binPath, nullptr);
    const char* dir = env->GetStringUTFChars(filesDir, nullptr);
    jboolean result = JNI_FALSE;
    int sv[2] = {-1, -1};
    if (bin != nullptr && dir != nullptr && socketpair(AF_UNIX, SOCK_STREAM, 0, sv) == 0) {
        pid_t pid1 = fork();
        if (pid1 == 0) {
            // child1：新会话（vault 与 app 进程组脱钩）后立即再 fork
            setsid();
            close(sv[0]);
            pid_t pid2 = fork();
            if (pid2 == 0) {
                // grandchild → vault
                if (dup2(sv[1], 0) != 0 || dup2(sv[1], 1) != 1) _exit(126);
                if (sv[1] > 2) close(sv[1]);
                close_inherited_fds();
                char* const argv[] = {const_cast<char*>(bin), const_cast<char*>(dir), nullptr};
                char* const envp[] = {nullptr};
                execve(bin, argv, envp);
                _exit(127);
            }
            _exit(pid2 < 0 ? 125 : 0);
        }
        if (pid1 > 0) {
            close(sv[1]);
            int status = 0;
            waitpid(pid1, &status, 0);  // 回收 child1（grandchild 由 init 收养）
            if (WIFEXITED(status) && WEXITSTATUS(status) == 0) {
                // 30s 读超时：UNLOCK 含 Argon2id（64MiB 内存硬，慢设备
                // 秒级），超时按本次失败处理（不判死——EOF 才是死亡信号）
                struct timeval tv {30, 0};
                setsockopt(sv[0], SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
                setsockopt(sv[0], SOL_SOCKET, SO_SNDTIMEO, &tv, sizeof(tv));
                g_vault_fd = sv[0];
                result = JNI_TRUE;
            } else {
                close(sv[0]);
            }
        } else {
            close(sv[0]);
            close(sv[1]);
        }
    }
    if (bin != nullptr) env->ReleaseStringUTFChars(binPath, bin);
    if (dir != nullptr) env->ReleaseStringUTFChars(filesDir, dir);
    pthread_mutex_unlock(&g_vault_mutex);
    return result;
}

static bool read_full_fd(int fd, uint8_t* buf, size_t n) {
    size_t done = 0;
    while (done < n) {
        ssize_t r = read(fd, buf + done, n - done);
        if (r <= 0) return false;
        done += (size_t) r;
    }
    return true;
}

static bool write_full_fd(int fd, const uint8_t* buf, size_t n) {
    size_t done = 0;
    while (done < n) {
        ssize_t w = write(fd, buf + done, n - done);
        if (w <= 0) return false;
        done += (size_t) w;
    }
    return true;
}

// 单请求-响应往返。返回 jbyteArray 或 null（超时/EOF/协议错）。
// 调用方约定：null 后用 nativeAlive 区分"vault 仍在（瞬态失败，可重试
// 同一连接）"与"vault 已死（下次调用前重启动）"。
extern "C" JNIEXPORT jbyteArray JNICALL
Java_fake_screenshot_defense_VaultClient_nativeRequest(
        JNIEnv* env, jobject /*thiz*/, jbyteArray req) {
    pthread_mutex_lock(&g_vault_mutex);
    jbyteArray result = nullptr;
    do {
        if (g_vault_fd < 0) break;
        jsize n = env->GetArrayLength(req);
        if (n < 1 || n > 131072) break;
        jbyte* in = env->GetByteArrayElements(req, nullptr);
        if (in == nullptr) break;
        uint8_t hdr[4] = {(uint8_t) ((uint32_t) n >> 24), (uint8_t) ((uint32_t) n >> 16),
                          (uint8_t) ((uint32_t) n >> 8), (uint8_t) n};
        bool ok = write_full_fd(g_vault_fd, hdr, 4) &&
                  write_full_fd(g_vault_fd, (const uint8_t*) in, (size_t) n);
        env->ReleaseByteArrayElements(req, in, JNI_ABORT);
        if (!ok) break;
        uint8_t rhdr[4];
        if (!read_full_fd(g_vault_fd, rhdr, 4)) break;
        uint32_t rlen = ((uint32_t) rhdr[0] << 24) | ((uint32_t) rhdr[1] << 16) |
                        ((uint32_t) rhdr[2] << 8) | (uint32_t) rhdr[3];
        if (rlen == 0 || rlen > 131072) break;  // 协议失步
        result = env->NewByteArray((jsize) rlen);
        if (result == nullptr) { result = nullptr; break; }
        // 直接读入 JVM 数组（避免中转缓冲）
        jbyte* out = env->GetByteArrayElements(result, nullptr);
        if (out == nullptr) { result = nullptr; break; }
        if (!read_full_fd(g_vault_fd, (uint8_t*) out, rlen)) {
            env->ReleaseByteArrayElements(result, out, JNI_ABORT);
            result = nullptr;
            break;
        }
        env->ReleaseByteArrayElements(result, out, 0);
    } while (false);
    pthread_mutex_unlock(&g_vault_mutex);
    return result;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_fake_screenshot_defense_VaultClient_nativeAlive(JNIEnv* /*env*/, jobject /*thiz*/) {
    pthread_mutex_lock(&g_vault_mutex);
    bool alive = g_vault_fd >= 0;
    pthread_mutex_unlock(&g_vault_mutex);
    return alive ? JNI_TRUE : JNI_FALSE;
}

// 关闭连接（vault 侧 EOF 自动退出）。销毁序列/测试用。
extern "C" JNIEXPORT void JNICALL
Java_fake_screenshot_defense_VaultClient_nativeClose(JNIEnv* /*env*/, jobject /*thiz*/) {
    pthread_mutex_lock(&g_vault_mutex);
    if (g_vault_fd >= 0) {
        close(g_vault_fd);
        g_vault_fd = -1;
    }
    pthread_mutex_unlock(&g_vault_mutex);
}

// ===================== 雷管联动（guard.cpp detonate 调用）=====================

// 向 vault 发 DESTROY 帧并等待确认（300ms 轮询窗）。
// 返回 true = vault 已确认处理：sync_key.bin 已改写为销毁态——DK 包裹
// 已消失而门禁验证项保留（销毁序列语义：门禁行为前后一致）。
// 任何失败（vault 不在/超时/协议错）返回 false——调用方回落到整文件
// 粉碎（验证项随之消失是可接受降级：vault 不在 = 无内存 DK，密文级
// 销毁效果等同；仅"引爆后门禁仍在"这一 UX 语义损失）。
// 供 detonate() 在引爆前尽力调用：多一次跨进程销毁（vault 内存 DK
// 即刻清零，早于 EOF 联动退出），且保住验证项。
bool vault_detonate_best_effort() {
    pthread_mutex_lock(&g_vault_mutex);
    bool ok = false;
    do {
        if (g_vault_fd < 0) break;
        // OP_DESTROY = 0x13（与 vault.cpp 对齐；此处不引协议头，单一字节常量）
        const uint8_t req[1] = {0x13};
        uint8_t hdr[4] = {0, 0, 0, sizeof(req)};
        if (!write_full_fd(g_vault_fd, hdr, 4) ||
            !write_full_fd(g_vault_fd, req, sizeof(req))) {
            break;
        }
        // 300ms 窗等响应（vault 处理 DESTROY 无密码学开销，即时返回）
        struct pollfd pfd = {g_vault_fd, POLLIN, 0};
        if (poll(&pfd, 1, 300) != 1) break;
        uint8_t rhdr[4];
        if (!read_full_fd(g_vault_fd, rhdr, 4)) break;
        uint32_t rlen = ((uint32_t) rhdr[0] << 24) | ((uint32_t) rhdr[1] << 16) |
                        ((uint32_t) rhdr[2] << 8) | (uint32_t) rhdr[3];
        if (rlen == 0 || rlen > 4096) break;
        uint8_t resp[4096];
        if (!read_full_fd(g_vault_fd, resp, rlen)) break;
        ok = resp[0] == 0;
    } while (false);
    pthread_mutex_unlock(&g_vault_mutex);
    return ok;
}
