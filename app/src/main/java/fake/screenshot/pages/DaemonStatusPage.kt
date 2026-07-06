package fake.screenshot.pages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fake.screenshot.DaemonManager
import fake.screenshot.R
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DaemonStatusCompose() {
    val scope = rememberCoroutineScope()

    var isLoading by remember { mutableStateOf(true) }
    var statusText by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // 加载数据
    LaunchedEffect(Unit) {
        isLoading = true
        errorMessage = null
        val response = DaemonManager.sendCommand("detail")
        isLoading = false
        if (response == null) {
            errorMessage = "守护进程未运行或无法连接"
        } else {
            statusText = response
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.daemon)) },
                actions = {
                    // 刷新按钮
                    IconButton(
                        onClick = {
                            scope.launch {
                                isLoading = true
                                errorMessage = null
                                val response = DaemonManager.sendCommand("detail")
                                isLoading = false
                                if (response == null) {
                                    errorMessage = "守护进程未运行或无法连接"
                                } else {
                                    statusText = response
                                }
                            }
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "刷新"
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
                                scope.launch {
                                    isLoading = true
                                    errorMessage = null
                                    val response = DaemonManager.sendCommand("detail")
                                    isLoading = false
                                    if (response == null) {
                                        errorMessage = "守护进程未运行或无法连接"
                                    } else {
                                        statusText = response
                                    }
                                }
                            }
                        ) {
                            Text("重试")
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
                            // 将返回的文本按行拆分并显示
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
    }
}