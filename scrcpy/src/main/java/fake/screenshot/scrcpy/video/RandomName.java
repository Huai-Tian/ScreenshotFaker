package fake.screenshot.scrcpy.video;

import java.security.SecureRandom;

/**
 * 为虚拟显示屏等系统可见对象生成随机名称，
 * 避免 "scrcpy" 等特征字样出现在 dumpsys display 等系统输出中。
 * 每次调用生成新的随机名（hex 小写），与系统内部大量 hex 命名风格一致。
 */
public final class RandomName {

    private static final SecureRandom RANDOM = new SecureRandom();

    private RandomName() {
        // not instantiable
    }

    public static String next() {
        StringBuilder sb = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            sb.append("0123456789abcdef".charAt(RANDOM.nextInt(16)));
        }
        return sb.toString();
    }
}
