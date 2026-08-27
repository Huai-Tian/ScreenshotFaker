// IRootDisplay：应用进程 <-> root（Shizuku UserService）进程的悬浮显示窗口控制接口。
//
// 使用规则：
// - 本接口所有方法均为 oneway（单向、不阻塞主线程）；同线程发起的调用按序送达。
// - 媒体内容以已打开的 ParcelFileDescriptor 传递：应用进程持有 content Uri 授权，
//   root 进程直接读取 fd，避免将授权 URI 交给其他 uid 打开而失败。
// - Shizuku 保留事务 destroy（ShizukuApiConstants.USER_SERVICE_TRANSACTION_destroy
//   = 16777115）超出 AIDL 编译器允许的事务码上限（0~16777114），因此不能在
//   本文件声明，改由 RootDisplayService.onTransact 直接拦截该事务码执行清理。
package fake.screenshot.services.privileged;

import fake.screenshot.services.privileged.IRootDisplayCallback;

oneway interface IRootDisplay {

    // 窗口生命周期与几何（坐标系：Gravity.TOP|START）
    void attach(int x, int y, int width, int height) = 1;
    void detach() = 2;
    void setGeometry(int x, int y, int width, int height) = 3;
    void setAlpha(float alpha) = 4;

    // 媒体显示
    void showImage(in ParcelFileDescriptor fd) = 5;
    void showVideo(in ParcelFileDescriptor fd) = 6;
    void clearMedia() = 7;

    // 图片缩放/平移（与本地模式手势逻辑一致）
    void scaleImage(float factor) = 8;
    void panImage(float dx, float dy) = 9;

    // 视频控制
    void togglePlayPause() = 10;
    void seekBy(int deltaMs) = 11;
    void setMuted(boolean muted) = 12;

    // 控制窗口（root 模式下与显示窗口同样由 root 进程托管）：
    // 透明可触摸，接收全部手势；几何与显示窗口同步由 root 端内部维护。
    // 之所以必须 root 托管：可触摸窗口必然遮挡下层应用触摸（FLAG_WINDOW_IS_OBSCURED），
    // 只有 uid=0 进程的 TRUSTED_OVERLAY 能让 InputDispatcher 跳过遮挡标记——
    // 应用进程的控制窗口无论怎么伪装都无法做到。
    void attachControl(int x, int y, int width, int height) = 13;
    void detachControl() = 14;

    // 反向回调：root 端手势判定"切换媒体"时通知应用进程
    // （媒体列表与 content Uri 授权都在应用进程，root 端拿不到 fd 以外的列表信息）
    void registerCallback(in IRootDisplayCallback callback) = 15;
}
