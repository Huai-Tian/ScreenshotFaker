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
#include <chrono>
#include <vector>
#include <sstream>
#include <openssl/evp.h>
#include <openssl/kdf.h>
#include <openssl/rand.h>

using namespace std;

// ===================== 日志 =====================
void log(const string &text) {
    ofstream ofs("/data/local/tmp/log.txt", ios::app);
    ofs << text << " errno=" << errno << " (" << strerror(errno) << ")" << endl;
    ofs.flush(); // 强制刷新
    ofs.close();
}

// ===================== 守护进程化 =====================
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

// ===================== 加密常量 =====================
const string SALT = "ScreenshotFakerSalt";
const int PBKDF2_ITERATIONS = 200000;
const int KEY_LEN = 32;          // 256 bits
const int TAG_LEN = 16;          // 128 bits
const int NONCE_LEN = 12;
const long long TIME_SKEW_SECONDS = 10;

// ===================== 时间戳工具 =====================
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

// ===================== 密钥派生 =====================
vector<unsigned char> derive_key(const string &password) {
    vector<unsigned char> key(KEY_LEN);
    PKCS5_PBKDF2_HMAC(password.c_str(), password.size(),
                      (const unsigned char *) SALT.c_str(), SALT.size(),
                      PBKDF2_ITERATIONS, EVP_sha256(), KEY_LEN, key.data());
    return key;
}

// ===================== 加密 / 解密 =====================
vector<unsigned char> encrypt_data(const vector<unsigned char> &key, const string &plaintext) {
    unsigned char nonce[NONCE_LEN];
    RAND_bytes(nonce, NONCE_LEN);

    EVP_CIPHER_CTX *ctx = EVP_CIPHER_CTX_new();
    EVP_EncryptInit_ex(ctx, EVP_aes_256_gcm(), nullptr, key.data(), nonce);
    EVP_CIPHER_CTX_ctrl(ctx, EVP_CTRL_GCM_SET_IVLEN, NONCE_LEN, nullptr);

    vector<unsigned char> ciphertext(plaintext.size() + TAG_LEN);
    int len;
    EVP_EncryptUpdate(ctx, ciphertext.data(), &len,
                      (const unsigned char *) plaintext.data(), plaintext.size());
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
                        (void *)(ciphertext + ciphertext_len - TAG_LEN));

    vector<unsigned char> plaintext(ciphertext_len);
    int len;
    int ret = EVP_DecryptUpdate(ctx, plaintext.data(), &len,
                                ciphertext, ciphertext_len - TAG_LEN);
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

// ===================== 安全发送（加密） =====================
bool send_encrypted(int fd, const vector<unsigned char> &key, const string &plaintext) {
    vector<unsigned char> encrypted = encrypt_data(key, plaintext);

    // 打印加密后的数据长度和十六进制
    string hex_str;
    for (unsigned char c : encrypted) {
        char buf[3];
        snprintf(buf, sizeof(buf), "%02x", c);
        hex_str += buf;
    }
    uint32_t len = htonl(encrypted.size());
    if (write(fd, &len, 4) != 4) return false;
    if (write(fd, encrypted.data(), encrypted.size()) != (ssize_t) encrypted.size()) return false;
    return true;
}

// ===================== 安全接收（解密） =====================
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

    len = ntohl(*(uint32_t*)len_buf);
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

    // 打印接收到的数据十六进制（前64字节）
    string hex_str;
    for (size_t i = 0; i < len && i < 64; i++) {
        char buf[3];
        snprintf(buf, sizeof(buf), "%02x", encrypted[i]);
        hex_str += buf;
    }
    return decrypt_data(key, encrypted);
}

// ===================== 主函数 =====================
int main(int argc, char *argv[]) {
    if (argc < 3) {
        return 1;
    }

    // 1. 解析端口
    char *endptr;
    errno = 0;
    long port = strtol(argv[1], &endptr, 10);
    if (errno != 0 || endptr == argv[1] || *endptr != '\0' || port < 1024 || port > 65535) {
        return 1;
    }

    // 2. 获取密码（暂时保留，将在守护进程化后派生密钥）
    string password = argv[2];

    // 3. 守护进程化（此时父进程退出，子进程继续）
    daemonize();
    signal(SIGPIPE, SIG_IGN);

    // 4. 在子进程中派生密钥
    vector<unsigned char> key = derive_key(password);

// 打印密钥十六进制（仅调试）
    string key_hex;
    for (unsigned char c : key) {
        char buf[3];
        snprintf(buf, sizeof(buf), "%02x", c);
        key_hex += buf;
    }

    __builtin_memset(argv[2], 0, strlen(argv[2]));

    // 6. 创建 TCP socket
    int fd = socket(AF_INET, SOCK_STREAM, 0);
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

    // 7. 主循环
    while (true) {
        int client_fd = accept(fd, nullptr, nullptr);
        if (client_fd < 0) continue;

        // 接收加密命令
        string plaintext = recv_encrypted(client_fd, key);
        if (plaintext.empty()) {
            // 解密失败或读取错误，发送加密的错误消息，避免连接重置
            send_encrypted(client_fd, key, "Decryption failed");
            close(client_fd);
            continue;
        }

        // 解析 "command|timestamp"
        size_t sep = plaintext.find('|');
        if (sep == string::npos) {
            string reply = "Invalid format";
            send_encrypted(client_fd, key, reply);
            close(client_fd);
            continue;
        }
        string command = plaintext.substr(0, sep);
        string ts_str = plaintext.substr(sep + 1);
        long long timestamp;
        try {
            timestamp = stoll(ts_str);
        } catch (...) {
            string reply = "Invalid timestamp";
            send_encrypted(client_fd, key, reply);
            close(client_fd);
            continue;
        }

        // 验证时间戳
        if (!is_timestamp_valid(timestamp)) {
            string reply = "Rejected (replay)";
            send_encrypted(client_fd, key, reply);
            close(client_fd);
            continue;
        }

        // 处理命令
        string reply_plain;
        if (command == "status") {
            reply_plain = "Working|" + to_string(get_current_timestamp_seconds());
        } else if (command == "stop") {
            reply_plain = "Stopping|" + to_string(get_current_timestamp_seconds());
            send_encrypted(client_fd, key, reply_plain);
            close(client_fd);
            close(fd);
            exit(0);
        } else {
            reply_plain = "Unknown|" + to_string(get_current_timestamp_seconds());
        }

        send_encrypted(client_fd, key, reply_plain);
        close(client_fd);
    }

    close(fd);
    return 0;
}