package fake.screenshot

import com.jcraft.jsch.Session
import kotlinx.coroutines.*
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicBoolean

class ScreenShareReceiveManager(
    private val address: String,
    private val localPort: Int,
    private val targetPort: Int,
    private val useSSH: Boolean,
    private val sshPort: Int?,
    private val name: String?,
    private val password: ByteArray?
) {
    private val isRunning = AtomicBoolean(false)
    private var scope: CoroutineScope? = null
    private var serverSocket: ServerSocket? = null
    private var sshSession: Session? = null

    init {
        if (useSSH) {
            require(sshPort != null) { "sshPort must be provided when useSSH is true" }
            require(name != null) { "name must be provided when useSSH is true" }
            require(password != null) { "password must be provided when useSSH is true" }
        }
    }

    fun startProxy(): Boolean = true

    fun stopProxy() {}
}