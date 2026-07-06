#include "auxiliary.h"

string capture_gesture;
string capture_command;
string record_gesture;
string record_command;
string share_gesture;
string share_command;

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
    vector<unsigned char> key = derive_key(password);
    __builtin_memset(argv[2], 0, strlen(argv[2]));
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
    while (true) {
        int client_fd = accept(fd, nullptr, nullptr);
        if (client_fd < 0) continue;
        string plaintext = recv_encrypted(client_fd, key);
        if (plaintext.empty()) {
            send_encrypted(client_fd, key, "Decryption failed");
            close(client_fd);
            continue;
        }
        size_t sep = plaintext.find('\x1C');
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
        if (!is_timestamp_valid(timestamp)) {
            string reply = "Rejected (replay)";
            send_encrypted(client_fd, key, reply);
            close(client_fd);
            continue;
        }
        string reply_plain;
        if (command == "status") {
            reply_plain = "Working\x1C" + to_string(get_current_timestamp_seconds());
        } else if (command == "detail") {
            string capture_gs_display = []() -> string {
                string result;
                vector<string> i = split(capture_gesture, '\x1F');
                result += "Priority=[" + i[0] + "]:";
                result += "TAG=[" + i[1] + "]:";
                result += "MSG=[" + i[2] + "]";
                return result;
            }();
            string record_gs_display = []() -> string {
                string result;
                vector<string> i = split(record_gesture, '\x1F');
                result += "Priority=[" + i[0] + "]:";
                result += "TAG=[" + i[1] + "]:";
                result += "MSG=[" + i[2] + "]";
                return result;
            }();
            string share_gs_display = []() -> string {
                string result;
                vector<string> i = split(share_gesture, '\x1F');
                result += "Priority=[" + i[0] + "]:";
                result += "TAG=[" + i[1] + "]:";
                result += "MSG=[" + i[2] + "]";
                return result;
            }();
            string capture_cmd_display = []() -> string {
                string result = "[";
                result += replace_all(capture_command, "\x01F", "] [");
                result += ']';
                return result;
            }();
            string record_cmd_display = []() -> string {
                string result = "[";
                result += replace_all(record_command, "\x01F", "] [");
                result += ']';
                return result;
            }();
            string share_cmd_display = []() -> string {
                string result = "[";
                result += replace_all(share_command, "\x01F", "] [");
                result += ']';
                return result;
            }();
            reply_plain = "ScreenshotFakerDaemon:\n";
            reply_plain.append(
                    "uid=" + to_string(getuid()) + ", pid=" + to_string(getpid()) + ", ppid=" +
                    to_string(getppid()) + "\n");
            reply_plain.append("capture_gesture=" + capture_gs_display + "\n");
            reply_plain.append("capture_commands=" + capture_cmd_display + "\n");
            reply_plain.append("record_gesture=" + record_gs_display + "\n");
            reply_plain.append("record_commands=" + record_cmd_display + "\n");
            reply_plain.append("share_gesture=" + share_gs_display + "\n");
            reply_plain.append("share_commands=" + share_cmd_display + "\n");
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
                    string filterPart = data.substr(0, pos1D);
                    string argumentPart = data.substr(pos1D + 1);
                    vector<string> filters = split(filterPart, '\x1E');
                    vector<string> arguments = split(argumentPart, '\x1E');
                    capture_gesture = filters[0];
                    record_gesture = filters[1];
                    share_gesture = filters[2];
                    capture_command = arguments[0];
                    record_command = arguments[1];
                    share_command = arguments[2];
                    success = true;
                }
            }
            reply_plain = (success ? "fine\x1C" : "failed\x1C") +
                          to_string(get_current_timestamp_seconds());
        } else {
            reply_plain = "Unknown\x1C" + to_string(get_current_timestamp_seconds());
        }

        send_encrypted(client_fd, key, reply_plain);
        close(client_fd);
    }

    close(fd);
    return 0;
}