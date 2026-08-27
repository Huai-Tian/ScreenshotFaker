// IRootDisplayCallback：root（Shizuku UserService / su 直连）进程 -> 应用进程的反向通知。
//
// root 托管的手势都在 root 进程内完成（播放/seek/缩放/平移/窗口几何），
// "切换媒体"因媒体列表与 content Uri 授权在应用进程，root 端手势判定后
// 经本接口回调，由应用进程打开新媒体的 fd 再传给 IRootDisplay。
package fake.screenshot.services.privileged;

oneway interface IRootDisplayCallback {

    // 双击切换媒体：delta 为 -1（上一个）/ +1（下一个）
    void onSwitchMedia(int delta) = 1;

    // root 端窗口挂载失败（systemContext 反射失败 / WMS 拒绝 addView 等）：
    // 应用进程收到后立即回落本地窗口——root 路径的任何失败都绝不静默吞掉，
    // 否则悬浮窗"无任何显示"（失败在 root 进程内，应用侧无从感知）。
    // reason 仅用于应用进程 logcat 定位（logcat 其他应用不可读，不泄特征）
    void onWindowFailed(String reason) = 2;
}
