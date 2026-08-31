package vendor.entry;

import fake.screenshot.scrcpy.Server;

/**
 * 中性入口类：app_process 的命令行（ps 全局可见）只暴露本类名——
 * 包名与类名均为中性词，不含 app 身份（fake.screenshot）与功能提示
 * （relay/share/cast），规避共享运行期间的进程特征扫描。
 * 内部实现包名不出现在进程 cmdline 中。
 */
public final class Main {

    private Main() {
        // not instantiable
    }

    public static void main(String... args) {
        Server.main(args);
    }
}
