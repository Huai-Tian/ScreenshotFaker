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
            Thread proxyThread = new Thread(() -> {
                String socketName = DesktopConnection.getSocketName(scid);
                try (ServerSocket serverSocket = new ServerSocket(tcpPort)) {
                    Ln.i("TCP proxy listening on port " + tcpPort + ", forwarding to " + socketName);
                    while (!Thread.currentThread().isInterrupted()) {
                        Socket clientSocket = serverSocket.accept();
                        LocalSocket localSocket = null; // 声明在外层，以便 catch 块访问
                        try {
                            clientSocket.setTcpNoDelay(true);

                            try {
                                localSocket = new LocalSocket();
                                localSocket.connect(new LocalSocketAddress(socketName));
                            } catch (IOException e) {
                                Ln.w("Failed to connect to abstract socket " + socketName + ": " + e.getMessage());
                                clientSocket.close();
                                continue;
                            }

                            final InputStream clientIn = clientSocket.getInputStream();
                            final OutputStream clientOut = clientSocket.getOutputStream();
                            final InputStream localIn = localSocket.getInputStream();
                            final OutputStream localOut = localSocket.getOutputStream();

                            final byte[] buffer1 = new byte[256 * 1024];
                            final byte[] buffer2 = new byte[256 * 1024];

                            Thread t1 = new Thread(() -> {
                                try {
                                    int len;
                                    while ((len = clientIn.read(buffer1)) != -1) {
                                        localOut.write(buffer1, 0, len);
                                        localOut.flush();
                                    }
                                } catch (IOException ignored) {}
                            });

                            Thread t2 = new Thread(() -> {
                                try {
                                    int len;
                                    while ((len = localIn.read(buffer2)) != -1) {
                                        clientOut.write(buffer2, 0, len);
                                        clientOut.flush();
                                    }
                                } catch (IOException ignored) {}
                            });

                            t1.start();
                            t2.start();

                            while (t1.isAlive() && t2.isAlive()) {
                                try {
                                    Thread.sleep(50);
                                } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    break;
                                }
                            }

                            t1.interrupt();
                            t2.interrupt();
                            clientIn.close();
                            clientOut.close();
                            localIn.close();
                            localOut.close();
                            t1.join(1000);
                            t2.join(1000);

                            // 正常关闭
                            localSocket.close();
                            clientSocket.close();

                        } catch (Exception e) {
                            if (!Thread.currentThread().isInterrupted()) {
                                Ln.e("Proxy connection error: " + e.getMessage());
                            }
                            try { clientSocket.close(); } catch (IOException ignored) {}
                            if (localSocket != null) {
                                try { localSocket.close(); } catch (IOException ignored) {}
                            }
                        }
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
