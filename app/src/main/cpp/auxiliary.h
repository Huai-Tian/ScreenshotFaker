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
#include <openssl/evp.h>
#include <openssl/kdf.h>
#include <openssl/rand.h>

using namespace std;
// ===================== 加密常量 =====================
const string SALT = "ScreenshotFakerSalt";
const int PBKDF2_ITERATIONS = 200000;
const int KEY_LEN = 32;          // 256 bits
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

[[maybe_unused]] inline void log(const string &text) {
    ofstream ofs("/data/local/tmp/log.txt", ios::app);
    ofs << text << " errno=" << errno << " (" << strerror(errno) << ")" << endl;
    ofs.flush();
    ofs.close();
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
    static const string chars =
            "AaBbCcDdEeFfGgHhIiJjKkLlMmNnOoPpQqRrSsTtUuVvWwXxYyZz0123456789";
    static random_device rd;
    static mt19937 gen(rd());
    static uniform_int_distribution<size_t> dist(0, chars.size() - 1);

    string result;
    result.reserve(length);
    for (int i = 0; i < length; ++i) {
        result.push_back(chars[dist(gen)]);
    }
    return result;
}

// ===================== 时间戳工具 =====================
long long get_current_timestamp_seconds();

bool is_timestamp_valid(long long ts);

// ===================== 密钥派生 =====================
vector<unsigned char> derive_key(const string &password);

// ===================== 加密 / 解密 =====================
vector<unsigned char> encrypt_data(const vector<unsigned char> &key, const string &plaintext);

string decrypt_data(const vector<unsigned char> &key, const vector<unsigned char> &data);

// ===================== 发送（加密） =====================
bool send_encrypted(int fd, const vector<unsigned char> &key, const string &plaintext);

// ===================== 接收（解密） =====================
string recv_encrypted(int fd, const vector<unsigned char> &key);