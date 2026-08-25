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
        private int running;
        private boolean fatalError;

        Completion(int running) {
            this.running = running;
        }

        synchronized void addCompleted(boolean fatalError) {
            --running;
            if (fatalError) {
                this.fatalError = true;
            }
            if (running == 0 || this.fatalError) {
                Looper.getMainLooper().quitSafely();
            }
        }
    }

    private Server() {
        // not instantiable
    }

    private static void scrcpy(Options options) throws IOException, ConfigurationException {
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
        boolean tunnelForward = options.isTunnelForward();
        boolean control = options.getControl();
        boolean video = options.getVideo();
        boolean audio = options.getAudio();
        boolean sendDummyByte = options.getSendDummyByte();

        Workarounds.apply();

        if (tcpPort != -1) {
            final boolean tcpLocalOnly = options.getTcpLocalOnly();
            final String authPassword = options.getAuthPassword();
            Thread proxyThread = new Thread(() -> {
                String socketName = DesktopConnection.getSocketName(scid);
                try (ServerSocket serverSocket = tcpLocalOnly
                        ? new ServerSocket(tcpPort, 50, java.net.InetAddress.getLoopbackAddress())
                        : new ServerSocket(tcpPort)) {
                    Ln.i("TCP proxy listening on port " + tcpPort + ", forwarding to " + socketName);
                    while (!Thread.currentThread().isInterrupted()) {
                        Socket clientSocket = serverSocket.accept();
                        Thread connThread = new Thread(() -> proxyConnection(clientSocket, socketName, authPassword));
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

        List<AsyncProcessor> asyncProcessors = new ArrayList<>();

        DesktopConnection connection = DesktopConnection.open(scid, tunnelForward, video, audio, control, sendDummyByte);
        try {
            if (options.getSendDeviceMeta()) {
                connection.sendDeviceMeta(Device.getDeviceName());
            }

            Controller controller = null;

            if (control) {
                ControlChannel controlChannel = connection.getControlChannel();
                controller = new Controller(controlChannel, cleanUp, options);
                asyncProcessors.add(controller);
            }

            if (audio) {
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

            if (video) {
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
                asyncProcessor.start((fatalError) -> {
                    completion.addCompleted(fatalError);
                });
            }

            Looper.loop(); // interrupted by the Completion implementation
        } finally {
            if (cleanUp != null) {
                cleanUp.interrupt();
            }
            for (AsyncProcessor asyncProcessor : asyncProcessors) {
                asyncProcessor.stop();
            }

            connection.shutdown();

            try {
                if (cleanUp != null) {
                    cleanUp.join();
                }
                for (AsyncProcessor asyncProcessor : asyncProcessors) {
                    asyncProcessor.join();
                }

                OpenGLRunner.shutdown();
            } catch (InterruptedException e) {
                // ignore
            }

            connection.close();
        }
    }

    private static void proxyConnection(Socket clientSocket, String socketName) {
        LocalSocket localSocket = new LocalSocket();

        try {
            clientSocket.setTcpNoDelay(true);
            // 客户端异常掉线（无 FIN/RST）时依靠 keepalive 探测，避免连接长期泄漏
            clientSocket.setKeepAlive(true);
        } catch (IOException ignored) {
        }

        try {
            localSocket.connect(new LocalSocketAddress(socketName));
        } catch (IOException e) {
            Ln.w("Failed to connect to abstract socket " + socketName + ": " + e.getMessage());
            closeQuietly(clientSocket);
            closeQuietly(localSocket);
            return;
        }

        try {
            InputStream clientIn = clientSocket.getInputStream();
            OutputStream clientOut = clientSocket.getOutputStream();
            InputStream localIn = localSocket.getInputStream();
            OutputStream localOut = localSocket.getOutputStream();

            Thread toLocal = new Thread(() -> relay(clientIn, localOut, clientSocket, localSocket));
            Thread toClient = new Thread(() -> relay(localIn, clientOut, clientSocket, localSocket));
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

    private static void relay(InputStream in, OutputStream out, Socket clientSocket, LocalSocket localSocket) {
        byte[] buffer = new byte[64 * 1024];
        try {
            int len;
            while ((len = in.read(buffer)) != -1) {
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

    private static void proxyConnection(Socket clientSocket, String socketName, String authPassword) {
        LocalSocket localSocket = new LocalSocket();

        try {
            clientSocket.setTcpNoDelay(true);
            // 客户端异常掉线（无 FIN/RST）时依靠 keepalive 探测，避免连接长期泄漏
            clientSocket.setKeepAlive(true);
        } catch (IOException ignored) {
        }

        // 共享密码握手：认证通过前不触碰 abstract socket。
        // 对探测者的表现是"接受后短暂等待即断开"，不会暴露转发行为。
        if (authPassword != null && !authPassword.isEmpty()) {
            try {
                clientSocket.setSoTimeout(AUTH_TIMEOUT_MS);
                String received = readPasswordLine(clientSocket);
                clientSocket.setSoTimeout(0);
                if (received == null || !passwordMatches(received, authPassword)) {
                    closeQuietly(clientSocket);
                    closeQuietly(localSocket);
                    return;
                }
            } catch (IOException e) {
                closeQuietly(clientSocket);
                closeQuietly(localSocket);
                return;
            }
        }

        try {
            localSocket.connect(new LocalSocketAddress(socketName));
        } catch (IOException e) {
            // 连接失败立即关闭，不保留半开连接，减少端口指纹
            closeQuietly(clientSocket);
            closeQuietly(localSocket);
            return;
        }

        try {
            InputStream clientIn = clientSocket.getInputStream();
            OutputStream clientOut = clientSocket.getOutputStream();
            InputStream localIn = localSocket.getInputStream();
            OutputStream localOut = localSocket.getOutputStream();

            Thread toLocal = new Thread(() -> relay(clientIn, localOut, clientSocket, localSocket));
            Thread toClient = new Thread(() -> relay(localIn, clientOut, clientSocket, localSocket));
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
