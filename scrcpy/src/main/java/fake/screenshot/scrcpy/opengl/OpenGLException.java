package fake.screenshot.scrcpy.opengl;

import java.io.IOException;

public class OpenGLException extends IOException {
    public OpenGLException(String message) {
        super(message);
    }

    public OpenGLException(String message, Throwable cause) {
        super(message, cause);
    }
}
