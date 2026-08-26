package fake.screenshot.scrcpy;

import fake.screenshot.scrcpy.audio.AudioCapture;
import fake.screenshot.scrcpy.audio.AudioCodec;
import fake.screenshot.scrcpy.audio.AudioDirectCapture;
import fake.screenshot.scrcpy.audio.AudioEncoder;
import fake.screenshot.scrcpy.audio.AudioPlaybackCapture;
import fake.screenshot.scrcpy.audio.AudioRawRecorder;
import fake.screenshot.scrcpy.audio.AudioSource;
import fake.screenshot.scrcpy.control.ControlChannel;
import fake.screenshot.scrcpy.control.Controller;
import fake.screenshot.scrcpy.device.DesktopConnection;
import fake.screenshot.scrcpy.device.Device;
import fake.screenshot.scrcpy.device.Streamer;
import fake.screenshot.scrcpy.model.ConfigurationException;
import fake.screenshot.scrcpy.model.NewDisplay;
import fake.screenshot.scrcpy.opengl.OpenGLRunner;
import fake.screenshot.scrcpy.util.Ln;
import fake.screenshot.scrcpy.util.LogUtils;
import fake.screenshot.scrcpy.video.CameraCapture;
import fake.screenshot.scrcpy.video.NewDisplayCapture;
import fake.screenshot.scrcpy.video.ScreenCapture;
import fake.screenshot.scrcpy.video.SurfaceCapture;
import fake.screenshot.scrcpy.video.SurfaceEncoder;
import fake.screenshot.scrcpy.video.VideoSource;

import android.annotation.SuppressLint;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Build;
import android.os.Looper;
import android.system.Os;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;

public final class Server {

    public static final String SERVER_PATH;
    private static final int AUTH_TIMEOUT_MS = 3000;

    static {
        String[] classPaths = System.getProperty("java.class.path").split(File.pathSeparator);
        // By convention, scrcpy is always executed with the absolute path of scrcpy-server.jar as the first item in the classpath
        SERVER_PATH = classPaths[0];
    }

    private static class Completion {
        private final int initial;
        private int running;
        private boolean fatalError;

        Completion(int running) {
            this.initial = running;
            this.running = running;
        }

        synchronized void addCompleted(boolean fatalError) {
            --running;
            if (fatalError) {
                this.fatalError = true;
            }
            if (running < initial) {
                // 任一 processor 结束即会话结束：video/audio/control 共享同一客户端
                // 连接，任一断开（含正常断开产生的 broken pipe）都意味着客户端已离开，
                // 立即唤醒会话线程收尾，无需等待其余 processor 自行超时
                notifyAll();
            }
        }

        synchronized boolean isFinished() {
            return running < initial;
        }
    }

    private Server() {
        // not instantiable
    }

    private static void scrcpy(Options options) throws IOException, ConfigurationException {
        // 构建标记：logcat 中据此确认设备上运行的 server 是否为最新版本。
        // 若日志中出现的不是本字符串（或完全没有），说明 APK 打包了过期的
        // server 二进制——检查 app/build.gradle.kts 的 injectScrcpyAsLib
        // 构建接线（必须直接依赖 :scrcpy:packageRelease，不可用嵌套 gradlew）
        Ln.i("Server build: relay-diag-v4");
        if (Build.VERSION.SDK_INT < AndroidVersions.API_31_ANDROID_12 && options.getVideoSource() == VideoSource.CAMERA) {
            Ln.e("Camera mirroring is not supported before Android 12");
            throw new ConfigurationException("Camera mirroring is not supported");
        }

        if (Build.VERSION.SDK_INT < AndroidVersions.API_29_ANDROID_10) {
            if (options.getNewDisplay() != null) {
                Ln.e("New virtual display is not supported before Android 10");
                throw new ConfigurationException("New virtual display is not supported");
            }
            if (options.getDisplayImePolicy() != -1) {
                Ln.e("Display IME policy is not supported before Android 10");
                throw new ConfigurationException("Display IME policy is not supported");
            }
        }

        CleanUp cleanUp = null;

        if (options.getCleanup()) {
            cleanUp = CleanUp.start(options);
        }

        int scid = options.getScid();
        int tcpPort = options.getTcpPort();

        Workarounds.apply();

        if (tcpPort != -1) {
            final boolean tcpLocalOnly = options.getTcpLocalOnly();
            final String authPassword = options.getAuthPassword();
            Thread proxyThread = new Thread(() -> {
                String socketName = DesktopConnection.getSocketName(scid);
                try (ServerSocket serverSocket = createServerSocket(tcpPort, tcpLocalOnly)) {
                    Ln.i("TCP proxy listening on port " + tcpPort + ", forwarding to " + socketName);
                    // 认证与 abstract socket 连接必须在 accept 线程内按序完成：
                    // server 端按 video → audio → control 顺序 accept abstract 连接，
                    // 若在各转发线程内并发连接，连接到达顺序可能与客户端连接顺序不一致，
                    // 导致通道错位配对（控制消息被写进 server 永不读取的通道，控制完全失效）。
                    // 在 accept 线程内串行处理则天然保序，且无需任何锁，不存在死锁可能
                    // （此前按序放行的"顺序门"方案在认证失败分支不推进计数器，
                    // 会让后续所有连接永久阻塞，表现为完全连不上）
                    // 连接序号（供转发日志区分通道：第 1/2/3 条连接对应
                    // video/audio/control，会话循环下每个会话重新从 1 计数会
                    // 与上一会话的残余连接混淆，因此用全局递增序号）
                    int connSeq = 0;
                    while (!Thread.currentThread().isInterrupted()) {
                        Socket clientSocket = serverSocket.accept();
                        LocalSocket localSocket = null;
                        boolean ok = false;
                        try {
                            clientSocket.setTcpNoDelay(true);
                            // 客户端异常掉线（无 FIN/RST）时依靠 keepalive 探测，避免连接长期泄漏
                            clientSocket.setKeepAlive(true);
                            ok = authenticate(clientSocket, authPassword);
                            if (ok) {
                                localSocket = new LocalSocket();
                                localSocket.connect(new LocalSocketAddress(socketName));
                            }
                        } catch (IOException e) {
                            ok = false;
                        }
                        if (!ok) {
                            closeQuietly(clientSocket);
                            closeQuietly(localSocket);
                            continue;
                        }
                        final Socket relayClient = clientSocket;
                        final LocalSocket relayLocal = localSocket;
                        final int seq = ++connSeq;
                        Thread connThread = new Thread(() -> relayConnection(relayClient, relayLocal, seq));
                        connThread.setName("tcp_proxy_conn");
                        connThread.setDaemon(true);
                        connThread.start();
                    }
                } catch (IOException e) {
                    if (!Thread.currentThread().isInterrupted()) {
                        Ln.e("TCP proxy error", e);
                    }
                }
            });
            proxyThread.setName("proxy_thread");
            proxyThread.setPriority(Thread.MAX_PRIORITY);
            proxyThread.setDaemon(true);
            proxyThread.start();
        }

        // 会话循环在独立线程运行，主线程 Looper 持续处理系统消息（display monitor 等）。
        // 一个会话结束（接收端断开/锁屏/切后台）后，server 不再退出，
        // 而是等待下一个接收端连接，进程常驻直到被外部终止（磁贴停止共享）
        // 或发生致命错误。这消除了"接收端重进时 server 正在重启"的连接拒绝窗口。
        final CleanUp sessionCleanUp = cleanUp;
        final Options sessionOptions = options;
        Thread sessionThread = new Thread(() -> sessionLoop(sessionOptions, sessionCleanUp), "session");
        sessionThread.start();

        Looper.loop(); // 常驻；仅在致命错误（sessionLoop 调用 quitSafely）时返回
    }

    /**
     * 会话循环：逐个接受接收端连接并运行会话。
     * 会话结束（接收端断开/锁屏/切后台）或会话内部错误（编码器异常等）
     * 都不退出进程：前者是常态，后者通过重新建会话自愈。
     * 仅当连续多次快速失败（无法建立可用会话）才退出，交由外部守护重启。
     */
    private static void sessionLoop(Options options, CleanUp cleanUp) {
        int scid = options.getScid();
        boolean tunnelForward = options.isTunnelForward();
        boolean control = options.getControl();
        boolean video = options.getVideo();
        boolean audio = options.getAudio();
        boolean sendDummyByte = options.getSendDummyByte();

        int fastFailures = 0;
        while (true) {
            long sessionStart = System.currentTimeMillis();
            try {
                DesktopConnection connection = DesktopConnection.open(scid, tunnelForward, video, audio, control, sendDummyByte);
                runSession(options, cleanUp, connection);
                Ln.i("Session ended, waiting for next client");
            } catch (IOException | RuntimeException e) {
                // 接收端在协商中途断开、abstract socket 未及时释放等：
                // 视为一次失败的会话，继续等待下一个接收端
                Ln.i("Session failed, waiting for next client: " + e.getMessage());
            }

            // 保险阀：会话持续超 3s 视为正常（含阻塞等待客户端连接的时间），
            // 连续 5 次快速失败说明环境异常（socket 无法创建等），退出进程
            if (System.currentTimeMillis() - sessionStart > 3000) {
                fastFailures = 0;
            } else {
                fastFailures++;
                if (fastFailures >= 5) {
                    Ln.e("Too many consecutive session failures, exiting");
                    break;
                }
            }

            // 会话结束：稍等旧连接排干，避免新客户端立刻连上时
            // 配对到尚未完全关闭的 abstract socket
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }

        // 退出路径（正常停止由外部 kill，不经过这里）
        if (cleanUp != null) {
            cleanUp.interrupt();
            try {
                cleanUp.join();
            } catch (InterruptedException e) {
                // ignore
            }
        }
        Looper.getMainLooper().quitSafely();
    }

    /** 运行单个会话（含内部错误处理，不向外传播异常） */
    private static void runSession(Options options, CleanUp cleanUp, DesktopConnection connection) {
        List<AsyncProcessor> asyncProcessors = new ArrayList<>();
        // 诊断日志：确认各通道是否协商成功（控制无效问题时据此定位）
        Ln.i("Session start: video=" + options.getVideo() + " audio=" + options.getAudio() + " control=" + options.getControl());
        try {
            if (options.getSendDeviceMeta()) {
                connection.sendDeviceMeta(Device.getDeviceName());
            }

            Controller controller = null;

            if (options.getControl()) {
                ControlChannel controlChannel = connection.getControlChannel();
                controller = new Controller(controlChannel, cleanUp, options);
                asyncProcessors.add(controller);
            }

            if (options.getAudio()) {
                AudioCodec audioCodec = options.getAudioCodec();
                AudioSource audioSource = options.getAudioSource();
                AudioCapture audioCapture;
                if (audioSource.isDirect()) {
                    audioCapture = new AudioDirectCapture(audioSource);
                } else {
                    audioCapture = new AudioPlaybackCapture(options.getAudioDup());
                }

                Streamer audioStreamer = new Streamer(connection.getAudioFd(), audioCodec, options.getSendStreamMeta(), options.getSendFrameMeta());
                AsyncProcessor audioRecorder;
                if (audioCodec == AudioCodec.RAW) {
                    audioRecorder = new AudioRawRecorder(audioCapture, audioStreamer);
                } else {
                    audioRecorder = new AudioEncoder(audioCapture, audioStreamer, options);
                }
                asyncProcessors.add(audioRecorder);
            }

            if (options.getVideo()) {
                Streamer videoStreamer = new Streamer(connection.getVideoFd(), options.getVideoCodec(), options.getSendStreamMeta(),
                        options.getSendFrameMeta());
                SurfaceCapture surfaceCapture;
                if (options.getVideoSource() == VideoSource.DISPLAY) {
                    NewDisplay newDisplay = options.getNewDisplay();
                    if (newDisplay != null) {
                        surfaceCapture = new NewDisplayCapture(controller, options);
                    } else {
                        assert options.getDisplayId() != Device.DISPLAY_ID_NONE;
                        surfaceCapture = new ScreenCapture(controller, options);
                    }
                } else {
                    surfaceCapture = new CameraCapture(options);
                }
                SurfaceEncoder surfaceEncoder = new SurfaceEncoder(surfaceCapture, videoStreamer, options);
                asyncProcessors.add(surfaceEncoder);

                if (controller != null) {
                    controller.setSurfaceCapture(surfaceCapture);
                }
            }

            Completion completion = new Completion(asyncProcessors.size());
            for (AsyncProcessor asyncProcessor : asyncProcessors) {
                asyncProcessor.start(completion::addCompleted);
            }

            // 等待会话结束：任一 processor 完成（接收端断开，含正常断开的 broken pipe）
            synchronized (completion) {
                while (!completion.isFinished()) {
                    try {
                        completion.wait(10_000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // 处理器内部错误（编码器崩溃等）已由各处理器自行记录；
            // 这里吞掉异常：会话循环会建立新会话自愈
            Ln.e("Session error", e);
        } finally {
            for (AsyncProcessor asyncProcessor : asyncProcessors) {
                asyncProcessor.stop();
            }

            try {
                connection.shutdown();
            } catch (IOException ignored) {
            }

            try {
                for (AsyncProcessor asyncProcessor : asyncProcessors) {
                    asyncProcessor.join();
                }

                OpenGLRunner.shutdown();
            } catch (InterruptedException e) {
                // ignore
            }

            try {
                connection.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * 创建 TCP proxy 监听 socket。显式启用 SO_REUSEADDR：
     * 接收端断开后 server 会被守护循环重新拉起，旧会话的连接可能仍处于
     * TIME_WAIT 状态，无 SO_REUSEADDR 时重新 bind 会失败（Address already in use），
     * 导致重启失败、接收端连接被拒绝。
     */
    private static ServerSocket createServerSocket(int tcpPort, boolean tcpLocalOnly) throws IOException {
        ServerSocket serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        if (tcpLocalOnly) {
            serverSocket.bind(new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), tcpPort), 50);
        } else {
            serverSocket.bind(new java.net.InetSocketAddress(tcpPort), 50);
        }
        return serverSocket;
    }

    private static void relay(InputStream in, OutputStream out, Socket clientSocket, LocalSocket localSocket, int seq, String direction) {
        byte[] buffer = new byte[64 * 1024];
        // 诊断日志：每个方向的首包（控制失效排查）。
        // "up" = client→server（控制消息方向），"down" = server→client（音视频方向）。
        // 若 up 首包出现而 Controller 无 "First control message received"，
        // 说明数据卡在 abstract socket 段；若 up 首包不出现，
        // 说明控制消息根本没从接收端发出（接收端 APK 旧或未触发发送）
        boolean first = true;
        try {
            int len;
            while ((len = in.read(buffer)) != -1) {
                if (first) {
                    first = false;
                    Ln.d("Proxy relay #" + seq + " " + direction + ": first " + len + " bytes");
                }
                out.write(buffer, 0, len);
                out.flush();
            }
        } catch (IOException ignored) {
        } finally {
            closeQuietly(clientSocket);
            closeQuietly(localSocket);
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private static void closeQuietly(LocalSocket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    /**
     * 共享密码握手：认证通过前不触碰 abstract socket。
     * 对探测者的表现是"接受后短暂等待即断开"，不会暴露转发行为。
     * 返回 false 表示认证失败或连接已断开。
     * 在 proxy accept 线程内串行调用，单个坏连接最多阻塞 [AUTH_TIMEOUT_MS]。
     */
    private static boolean authenticate(Socket clientSocket, String authPassword) {
        if (authPassword == null || authPassword.isEmpty()) {
            return true;
        }
        try {
            clientSocket.setSoTimeout(AUTH_TIMEOUT_MS);
            String received = readPasswordLine(clientSocket);
            clientSocket.setSoTimeout(0);
            return received != null && passwordMatches(received, authPassword);
        } catch (IOException e) {
            return false;
        }
    }

    /** 双向转发一条已配对完成的连接（认证与 abstract 连接已由 accept 线程完成） */
    private static void relayConnection(Socket clientSocket, LocalSocket localSocket, int seq) {
        try {
            InputStream clientIn = clientSocket.getInputStream();
            OutputStream clientOut = clientSocket.getOutputStream();
            InputStream localIn = localSocket.getInputStream();
            OutputStream localOut = localSocket.getOutputStream();

            Thread toLocal = new Thread(() -> relay(clientIn, localOut, clientSocket, localSocket, seq, "up"));
            Thread toClient = new Thread(() -> relay(localIn, clientOut, clientSocket, localSocket, seq, "down"));
            toLocal.setName("tcp_proxy_up");
            toClient.setName("tcp_proxy_down");
            toLocal.setDaemon(true);
            toClient.setDaemon(true);
            toLocal.start();
            toClient.start();

            toLocal.join();
            toClient.join();
        } catch (IOException | InterruptedException e) {
            if (!Thread.currentThread().isInterrupted()) {
                Ln.e("Proxy connection error: " + e.getMessage());
            }
        } finally {
            closeQuietly(clientSocket);
            closeQuietly(localSocket);
        }
    }

    /**
     * 逐字节读取密码行（以 \n 结尾）。
     * 不使用缓冲流：避免 read-ahead 把属于后续转发的数据吞进缓冲区。
     */
    private static String readPasswordLine(Socket socket) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        InputStream in = socket.getInputStream();
        while (bos.size() < 256) {
            int b = in.read();
            if (b == -1) {
                return null;
            }
            if (b == '\n') {
                return new String(bos.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
            }
            if (b != '\r') {
                bos.write(b);
            }
        }
        return null; // 超长，视为非法
    }

    /**
     * 常数时间比较，避免时序侧信道逐字节泄露密码
     */
    private static boolean passwordMatches(String received, String expected) {
        return java.security.MessageDigest.isEqual(
                received.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void prepareMainLooper() {
        // Like Looper.prepareMainLooper(), but with quitAllowed set to true
        Looper.prepare();
        synchronized (Looper.class) {
            try {
                @SuppressLint("DiscouragedPrivateApi")
                Field field = Looper.class.getDeclaredField("sMainLooper");
                field.setAccessible(true);
                field.set(null, Looper.myLooper());
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }
    }

    public static void main(String... args) {
        int status = 0;
        try {
            internalMain(args);
        } catch (Throwable t) {
            Ln.e(t.getMessage(), t);
            status = 1;
        } finally {
            // By default, the Java process exits when all non-daemon threads are terminated.
            // The Android SDK might start some non-daemon threads internally, preventing the scrcpy server to exit.
            // So force the process to exit explicitly.
            System.exit(status);
        }
    }

    private static void internalMain(String... args) throws Exception {
        Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            Ln.e("Exception on thread " + t, e);
            if (defaultHandler != null) {
                defaultHandler.uncaughtException(t, e);
            }
        });

        dropRootPrivileges();

        prepareMainLooper();

        Options options = Options.parse(args);

        Ln.disableSystemStreams();
        Ln.initLogLevel(options.getLogLevel());

        Ln.i("Device: [" + Build.MANUFACTURER + "] " + Build.BRAND + " " + Build.MODEL + " (Android " + Build.VERSION.RELEASE + ")");

        if (options.getList()) {
            if (options.getCleanup()) {
                CleanUp.unlinkSelf();
            }

            if (options.getListEncoders()) {
                Ln.i(LogUtils.buildVideoEncoderListMessage());
                Ln.i(LogUtils.buildAudioEncoderListMessage());
            }
            if (options.getListDisplays()) {
                Ln.i(LogUtils.buildDisplayListMessage());
            }
            if (options.getListCameras() || options.getListCameraSizes()) {
                Workarounds.apply();
                Ln.i(LogUtils.buildCameraListMessage(options.getListCameraSizes()));
            }
            if (options.getListApps()) {
                Workarounds.apply();
                Ln.i("Processing Android apps... (this may take some time)");
                Ln.i(LogUtils.buildAppListMessage());
            }
            // Just print the requested data, do not mirror
            return;
        }

        try {
            scrcpy(options);
        } catch (ConfigurationException e) {
            // Do not print stack trace, a user-friendly error-message has already been logged
        }
    }

    @SuppressWarnings("deprecation")
    private static void dropRootPrivileges() {
        try {
            if (Os.getuid() == 0) {
                // Copy-paste does not work with root user
                // <https://github.com/Genymobile/scrcpy/issues/6224>
                Os.setuid(2000);
            }
        } catch (Exception e) {
            Ln.w("Cannot set UID", e);
        }
    }
}