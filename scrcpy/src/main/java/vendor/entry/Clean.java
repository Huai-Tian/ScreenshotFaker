package vendor.entry;

import fake.screenshot.scrcpy.CleanUp;

/**
 * 中性入口类：CleanUp 子进程的 app_process 命令行（ps 全局可见）只暴露
 * 本类名——原实现以 fake.screenshot.scrcpy.CleanUp 直接作入口，共享存续
 * 与收尾期间进程特征扫描可凭真实包名定位 app。
 * 与 vendor.entry.Main（relay 入口）同一命名约定，内部实现类不进 cmdline。
 */
public final class Clean {

    private Clean() {
        // not instantiable
    }

    public static void main(String... args) {
        CleanUp.main(args);
    }
}
