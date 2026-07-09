#include "auxiliary.h"

string capture_gesture;
string capture_command;
string record_gesture;
string record_command;
string share_gesture;
string share_command;
atomic_bool filter_update = false;
mutex config_mutex;

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
            string command;
            if (first == "screencap" || first == "screenrecord") {
                auto file_name = getCurrentDateString() + "_" + getRandomString(4) + i.back();
                i.pop_back();
                filesystem::create_directories(i.back());
                i.back() = i.back() + "/" + file_name;
                string result;
                for (const auto &j: i) {
                    command.append(j);
                    command += ' ';
                }
                command.pop_back();
            } else {
                return -1;
            }
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
            return ret;
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
                    (void) record_filter.execute_command();
                }
            }
            if (share_filter.valid && tag == share_filter.tag) {
                if ((priority == share_filter.priority || share_filter.priority == ' ') &&
                    regex_match(msg, share_filter.msg_regex)) {
                    (void) share_filter.execute_command();
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
    vector<unsigned char> key = derive_key(password);
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
            string cap_gs, rec_gs, sha_gs;
            string cap_cmd, rec_cmd, sha_cmd;
            {
                lock_guard<mutex> lock(config_mutex);
                cap_gs = capture_gesture;
                rec_gs = record_gesture;
                sha_gs = share_gesture;
                cap_cmd = capture_command;
                rec_cmd = record_command;
                sha_cmd = share_command;
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
            reply_plain.append("\x1C" + to_string(get_current_timestamp_seconds()));
        } else if (command == "stop") {
            reply_plain = "Stopping\x1C" + to_string(get_current_timestamp_seconds());
            send_encrypted(client_fd, key, reply_plain);
            close(client_fd);
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
                size_t pos1D = data.find('\x1D');
                if (pos1D != string::npos) {
                    auto processGesture = [](const string &gesture) -> string {
                        if (gesture.empty())return "";
                        auto patterns = split(gesture, '\x1F');
                        auto result = patterns[0] + "\x1F" + patterns[1] + "\x1F";
                        if (!isRegexValid(patterns[2]))return "";
                        result += patterns[2];
                        return result;
                    };
                    string filterPart = data.substr(0, pos1D);
                    string argumentPart = data.substr(pos1D + 1);
                    vector<string> filters = split(filterPart, '\x1E');
                    vector<string> arguments = split(argumentPart, '\x1E');
                    string cap_gs = processGesture(filters[0]);
                    string rec_gs = processGesture(filters[1]);
                    string sha_gs = processGesture(filters[2]);
                    lock_guard<mutex> lock(config_mutex);
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