#pragma once

#include <cstdio>
#include <cstdlib>
#include <unistd.h>
#include <string>
#include <cstring>
#include <map>
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <fcntl.h>
#include <cerrno>
#include <fstream>
#include <csignal>
#include <chrono>
#include <vector>
#include <sstream>
#include <regex>
#include <thread>
#include <random>
#include <poll.h>
#include <spawn.h>
#include <filesystem>
#include <atomic>
#include <mutex>
#include <ctime>
#include <iomanip>
#include <openssl/evp.h>
#include <openssl/kdf.h>
#include <openssl/rand.h>

using namespace std;
// ===================== 加密常量 =====================
const int KEY_LEN = 32;          // 256 bits（通信密钥，由 App 侧经 stdin 递交）
const int TAG_LEN = 16;          // 128 bits
const int NONCE_LEN = 12;
const long long TIME_SKEW_SECONDS = 10;

// ===================== 全局数据 =====================
extern string capture_gesture;
extern string capture_command;
extern string record_gesture;
extern string record_command;
extern string share_gesture;
extern string share_command;
extern string ssh_options;
extern atomic_bool auto_encrypt;
extern string scrcpy_path;
extern vector<unsigned char> g_key;

// ===================== 辅助函数 =====================
inline vector<string> split(const string &s, char sep) {
    vector<string> parts;
    size_t start = 0;
    size_t end = 0;
    while ((end = s.find(sep, start)) != string::npos) {
        parts.emplace_back(s, start, end - start);
        start = end + 1;
    }
    parts.emplace_back(s, start);

    return parts;
}

inline string replace_all(string str, const string &from, const string &to) {
    if (from.empty()) return str;
    size_t pos = 0;
    while ((pos = str.find(from, pos)) != string::npos) {
        str.replace(pos, from.length(), to);
        pos += to.length();
    }
    return str;
}

inline bool isRegexValid(const string &pattern) {
    if (pattern.empty()) return true;
    try {
        regex re(pattern);
        return true;
    } catch (const regex_error &) {
        return false;
    }
}

inline string getCurrentDateString() {
    auto now = chrono::system_clock::now();
    time_t t = chrono::system_clock::to_time_t(now);
    tm tm = *localtime(&t);
    ostringstream oss;
    oss << put_time(&tm, "%Y%m%d");
    return oss.str();
}

inline string getRandomString(int length) {
    // 密码学安全随机：这些名字包括录屏明文 tmp 名（明文截图落盘期间
    // 的唯一屏障）与 daemon 自拷贝路径——mt19937 的状态可由数百个
    // 连续输出恢复（/proc/cmdline + inotify 监视 tmp 目录创建即可收集），
    // 恢复后可预测后续明文名偷读、或预建符号链接诱导 root 写入。
    // 与 app 侧 Auxiliary.getRandomString 的 SecureRandom 基线对齐。
    // RAND_bytes 失败（熵源异常）时回退 mt19937：命名降级优于进程
    // 不可用（random_device 播种一次性，预测窗口仍远大于零的情况
    // 仅存在于熵源故障的极端环境）
    static const string chars =
            "AaBbCcDdEeFfGgHhIiJjKkLlMmNnOoPpQqRrSsTtUuVvWwXxYyZz0123456789";
    string result;
    result.reserve(length);
    unsigned char buf[64];
    int done = 0;
    while (done < length) {
        int want = min((int) sizeof(buf), length - done);
        if (RAND_bytes(buf, want) != 1) {
            // 熵源故障回退（见注释）：mt19937 一次性实例
            static random_device rd;
            static mt19937 gen(rd());
            static uniform_int_distribution<size_t> dist(0, chars.size() - 1);
            for (int i = 0; i < want; ++i) {
                result.push_back(chars[dist(gen)]);
            }
            done += want;
            continue;
        }
        for (int i = 0; i < want; ++i) {
            // 拒绝采样：256 % 62 != 0，直接取模有 4/256 的模偏差——
            // 名字场景无安全后果，但拒绝采样成本可忽略，取无偏实现
            unsigned char v = buf[i];
            while (v >= 248) {
                if (RAND_bytes(&v, 1) != 1) {
                    v = buf[i]; // 熵源二次失败：接受偏差（见注释）
                    break;
                }
            }
            result.push_back(chars[v % chars.size()]);
        }
        done += want;
    }
    return result;
}

// ===================== 时间戳工具 =====================
long long get_current_timestamp_seconds();

bool is_timestamp_valid(long long ts);

// ===================== 加密 / 解密 =====================
vector<unsigned char> encrypt_data(const vector<unsigned char> &key, const string &plaintext);

string decrypt_data(const vector<unsigned char> &key, const vector<unsigned char> &data);

// ===================== 发送（加密） =====================
bool send_encrypted(int fd, const vector<unsigned char> &key, const string &plaintext);

// ===================== 接收（解密） =====================
string recv_encrypted(int fd, const vector<unsigned char> &key);