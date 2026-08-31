package fake.screenshot.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fake.screenshot.wrappers.DaemonManager
import fake.screenshot.styles.*
import fake.screenshot.R
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DaemonStatusCompose() {
    val scope = rememberCoroutineScope()

    var isLoading by remember { mutableStateOf(true) }
    var statusText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var detachWarning by remember { mutableStateOf(false) }
    val errorText = stringResource(R.string.daemon_connect_failed)

    // 加载守护进程详情（首次进入/手动刷新/失败重试/detach 后共用；
    // sendCommand 为独立 socket 短连接，并发触发仅晚到者覆盖，无害）
    suspend fun loadStatus() {
        isLoading = true
        errorMessage = null
        val response = DaemonManager.sendCommand("detail")
        isLoading = false
        if (response == null) {
            errorMessage = errorText
        } else {
            statusText = response
        }
    }

    // 加载数据
    LaunchedEffect(Unit) {
        loadStatus()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.daemon)) },
                actions = {
                    IconButton(onClick = {
                        detachWarning = true
                    }) {
                        Icon(
                            imageVector = Icons.Default.SearchOff,
                            contentDescription = stringResource(R.string.detach)
                        )
                    }
                    IconButton(
                        onClick = {
                            scope.launch { loadStatus() }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.refresh)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                errorMessage != null -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.align(Alignment.Center)
                    ) {
                        Text(
                            text = errorMessage!!,
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 16.sp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = {
                                scope.launch { loadStatus() }
                            }
                        ) {
                            Text(stringResource(R.string.refresh))
                        }
                    }
                }

                else -> {
                    Card(
                        modifier = Modifier.fillMaxSize(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        LazyColumn(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val lines = statusText.split("\n")
                            items(lines.size) { index ->
                                val line = lines[index]
                                if (line.isNotEmpty()) {
                                    Text(
                                        text = line,
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        if (detachWarning) {
            CenteredAlertDialog(
                onDismissRequest = { },
                title = {
                    Text(text = stringResource(R.string.warning))
                },
                text = {
                    Text(stringResource(R.string.detach_description))
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            scope.launch {
                                DaemonManager.detachDaemon()
                                loadStatus()
                            }
                            detachWarning = false
                        },
                    ) {
                        Text(stringResource(R.string.Confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = { detachWarning = false }) {
                        Text(stringResource(R.string.Cancel))
                    }
                }
            )
        }
    }
}