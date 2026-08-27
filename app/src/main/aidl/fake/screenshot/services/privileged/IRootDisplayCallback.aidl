// IRootDisplayCallback：root（Shizuku UserService）进程 -> 应用进程的反向通知。
//
// root 托管的手势都在 root 进程内完成（播放/seek/缩放/平移/窗口几何），
// 唯一例外是"切换媒体"：媒体列表与 content Uri 授权在应用进程，
// root 端手势判定后经本接口回调，由应用进程打开新媒体的 fd 再传给 IRootDisplay。
package fake.screenshot.services.privileged;

oneway interface IRootDisplayCallback {

    // 双击切换媒体：delta 为 -1（上一个）/ +1（下一个）
    void onSwitchMedia(int delta) = 1;
}
