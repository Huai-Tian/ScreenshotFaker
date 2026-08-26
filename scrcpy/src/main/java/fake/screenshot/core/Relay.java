package fake.screenshot.core;

import fake.screenshot.scrcpy.Server;

/**
 * 中性入口类：app_process 的命令行（ps 可见）只暴露本类名，
 * 内部实现包名不出现在进程 cmdline 中。
 */
public final class Relay {

    private Relay() {
        // not instantiable
    }

    public static void main(String... args) {
        Server.main(args);
    }
}
