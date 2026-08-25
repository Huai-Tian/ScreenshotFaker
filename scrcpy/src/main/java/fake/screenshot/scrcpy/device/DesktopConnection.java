package fake.screenshot.scrcpy.device;

import fake.screenshot.scrcpy.control.ControlChannel;
import fake.screenshot.scrcpy.util.IO;
import fake.screenshot.scrcpy.util.StringUtils;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class DesktopConnection implements Closeable {

    private static final int DEVICE_NAME_FIELD_LENGTH = 64;

    private static final String SOCKET_NAME_PREFIX = "scrcpy";

    private final LocalSocket videoSocket;
    private final FileDescriptor videoFd;

    private final LocalSocket audioSocket;
    private final FileDescriptor audioFd;

    private final LocalSocket controlSocket;
    private final ControlChannel controlChannel;

    private DesktopConnection(LocalSocket videoSocket, LocalSocket audioSocket, LocalSocket controlSocket) throws IOException {
        this.videoSocket = videoSocket;
        this.audioSocket = audioSocket;
        this.controlSocket = controlSocket;

        videoFd = videoSocket != null ? videoSocket.getFileDescriptor() : null;
        audioFd = audioSocket != null ? audioSocket.getFileDescriptor() : null;
        controlChannel = controlSocket != null ? new ControlChannel(controlSocket) : null;
    }

    private static LocalSocket connect(String abstractName) throws IOException {
        LocalSocket localSocket = new LocalSocket();
        localSocket.connect(new LocalSocketAddress(abstractName));
        return localSocket;
    }

    public static String getSocketName(int scid) {
        if (scid == -1) {
            // If no SCID is set, use "scrcpy" to simplify using scrcpy-server alone
            return SOCKET_NAME_PREFIX;
        }

        return SOCKET_NAME_PREFIX + String.format("_%08x", scid);
    }

    public static DesktopConnection open(int scid, boolean tunnelForward, boolean video, boolean audio, boolean control, boolean sendDummyByte)
            throws IOException {
        String socketName = getSocketName(scid);

        LocalSocket videoSocket = null;
        LocalSocket audioSocket = null;
        LocalSocket controlSocket = null;
        try {
            if (tunnelForward) {
                try (LocalServerSocket localServerSocket = new LocalServerSocket(socketName)) {
                    // 每个 accept 的通道在建立时立即写入 1 字节通道标识
                    // （video=0 兼容原 dummy byte，audio=1，control=2）。
                    // 客户端据此确认本条连接被配对到了正确的通道，再发起下一条连接，
                    // 消除 TCP proxy 多线程转发时 abstract socket 的配对竞态。
                    if (video) {
                        videoSocket = localServerSocket.accept();
                        if (sendDummyByte) {
                            // send one byte so the client may read() to detect a connection error
                            videoSocket.getOutputStream().write(0);
                        }
                    }
                    if (audio) {
                        audioSocket = localServerSocket.accept();
                        if (sendDummyByte) {
                            audioSocket.getOutputStream().write(1);
                        }
                    }
                    if (control) {
                        controlSocket = localServerSocket.accept();
                        if (sendDummyByte) {
                            controlSocket.getOutputStream().write(2);
                        }
                    }
                }
            } else {
                if (video) {
                    videoSocket = connect(socketName);
                }
                if (audio) {
                    audioSocket = connect(socketName);
                }
                if (control) {
                    controlSocket = connect(socketName);
                }
            }
        } catch (IOException | RuntimeException e) {
            if (videoSocket != null) {
                videoSocket.close();
            }
            if (audioSocket != null) {
                audioSocket.close();
            }
            if (controlSocket != null) {
                controlSocket.close();
            }
            throw e;
        }

        return new DesktopConnection(videoSocket, audioSocket, controlSocket);
    }

    private LocalSocket getFirstSocket() {
        if (videoSocket != null) {
            return videoSocket;
        }
        if (audioSocket != null) {
            return audioSocket;
        }
        return controlSocket;
    }

    public void shutdown() throws IOException {
        if (videoSocket != null) {
            videoSocket.shutdownInput();
            videoSocket.shutdownOutput();
        }
        if (audioSocket != null) {
            audioSocket.shutdownInput();
            audioSocket.shutdownOutput();
        }
        if (controlSocket != null) {
            controlSocket.shutdownInput();
            controlSocket.shutdownOutput();
        }
    }

    public void close() throws IOException {
        if (videoSocket != null) {
            videoSocket.close();
        }
        if (audioSocket != null) {
            audioSocket.close();
        }
        if (controlSocket != null) {
            controlSocket.close();
        }
    }

    public void sendDeviceMeta(String deviceName) throws IOException {
        byte[] buffer = new byte[DEVICE_NAME_FIELD_LENGTH];

        byte[] deviceNameBytes = deviceName.getBytes(StandardCharsets.UTF_8);
        int len = StringUtils.getUtf8TruncationIndex(deviceNameBytes, DEVICE_NAME_FIELD_LENGTH - 1);
        System.arraycopy(deviceNameBytes, 0, buffer, 0, len);
        // byte[] are always 0-initialized in java, no need to set '\0' explicitly

        FileDescriptor fd = getFirstSocket().getFileDescriptor();
        IO.writeFully(fd, buffer, 0, buffer.length);
    }

    public FileDescriptor getVideoFd() {
        return videoFd;
    }

    public FileDescriptor getAudioFd() {
        return audioFd;
    }

    public ControlChannel getControlChannel() {
        return controlChannel;
    }
}
