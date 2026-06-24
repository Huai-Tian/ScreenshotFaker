package fake.screenshot

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object DaemonManager {
    private val nameDeferred = CompletableDeferred<String>()

    suspend fun getAbstractNamespace(): String = nameDeferred.await()

    fun init(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            val name = ConfigManager.getDataOnce(
                context.applicationContext,
                "daemon_abstract_name",
                "fake.screenshot.daemon"
            )
            nameDeferred.complete(name)
        }
    }

}