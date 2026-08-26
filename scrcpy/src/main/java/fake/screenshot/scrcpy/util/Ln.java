package fake.screenshot.scrcpy.util;

import java.io.OutputStream;
import java.io.PrintStream;

/**
 * 日志门面：所有输出均已移除（隐藏性要求，任何日志都不落 logcat/stdout）。
 * 方法签名保留，避免大量调用点改动；空方法体在编译优化后不产生任何开销。
 */
public final class Ln {

    private Ln() {
        // not instantiable
    }

    public static void disableSystemStreams() {
        PrintStream nullStream = new PrintStream(new NullOutputStream());
        System.setOut(nullStream);
        System.setErr(nullStream);
    }

    /**
     * Initialize the log level.
     * <p>
     * Must be called before starting any new thread.
     *
     * @param level the log level
     */
    public static void initLogLevel(Level level) {
        // no-op
    }

    public static boolean isEnabled(Level level) {
        return false;
    }

    public static void v(String message) {
        // no-op
    }

    public static void d(String message) {
        // no-op
    }

    public static void i(String message) {
        // no-op
    }

    public static void w(String message, Throwable throwable) {
        // no-op
    }

    public static void w(String message) {
        // no-op
    }

    public static void e(String message, Throwable throwable) {
        // no-op
    }

    public static void e(String message) {
        // no-op
    }

    public enum Level {
        VERBOSE, DEBUG, INFO, WARN, ERROR
    }

    static class NullOutputStream extends OutputStream {
        @Override
        public void write(byte[] b) {
            // ignore
        }

        @Override
        public void write(byte[] b, int off, int len) {
            // ignore
        }

        @Override
        public void write(int b) {
            // ignore
        }
    }
}
