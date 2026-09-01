package fake.screenshot.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import fake.screenshot.R
import fake.screenshot.defense.SensitiveStore
import fake.screenshot.wrappers.ScreenShareReceiverConfig
import fake.screenshot.wrappers.ScreenShareReceiverManager
import fake.screenshot.styles.CommonCard
import fake.screenshot.styles.PreferenceItem
import fake.screenshot.styles.PreferenceItemEx
import fake.screenshot.styles.CenteredAlertDialog
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveScreenSharingCompose(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var configs by remember { mutableStateOf<List<ScreenShareReceiverConfig>>(emptyList()) }
    var addDialog by remember { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<ScreenShareReceiverConfig?>(null) }
    var deleteTarget by remember { mutableStateOf<ScreenShareReceiverConfig?>(null) }

    fun refresh() {
        scope.launch { configs = ScreenShareReceiverManager.loadConfigs(context) }
    }

    LaunchedEffect(Unit) { refresh() }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.receive_stealth_screen_sharing)) })
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 20.dp)
        ) {
            items(configs.size, key = { configs[it].id }) { index ->
                val config = configs[index]
                CommonCard {
                    PreferenceItemEx(
                        icon = Icons.Default.Cast,
                        title = config.name,
                        subtitle = buildString {
                            append(if (config.useSsh) "SSH " else "")
                            append(config.address)
                            append(":")
                            append(config.port)
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { editTarget = config }) {
                                    Icon(
                                        imageVector = Icons.Default.Edit,
                                        contentDescription = stringResource(R.string.edit_receiver)
                                    )
                                }
                                IconButton(onClick = { deleteTarget = config }) {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = stringResource(R.string.delete)
                                    )
                                }
                            }
                        },
                        onClick = { navController.navigate("receive_viewer/${config.id}") }
                    )
                }
            }
            if (configs.isEmpty()) {
                item {
                    CommonCard {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(text = stringResource(R.string.no_receivers))
                        }
                    }
                }
            }
            item {
                CommonCard {
                    PreferenceItem(
                        icon = Icons.Default.Add,
                        title = stringResource(R.string.add_receiver),
                        trailingContent = {},
                        onClick = { addDialog = true }
                    )
                }
            }
        }
    }

    if (addDialog) {
        ReceiverConfigDialog(
            existing = null,
            onDismiss = { addDialog = false },
            onConfirm = { config ->
                scope.launch {
                    // DK 不可用时密文写入失败：静默不提示会造成"保存成功"
                    // 的假象（刷新后配置消失）
                    if (!ScreenShareReceiverManager.saveConfig(context, config)) {
                        android.widget.Toast.makeText(
                            context, R.string.failed, android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    refresh()
                }
                addDialog = false
            }
        )
    }

    editTarget?.let { target ->
        ReceiverConfigDialog(
            existing = target,
            onDismiss = { editTarget = null },
            onConfirm = { config ->
                scope.launch {
                    if (!ScreenShareReceiverManager.saveConfig(context, config)) {
                        android.widget.Toast.makeText(
                            context, R.string.failed, android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                    refresh()
                }
                editTarget = null
            }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.delete)) },
            text = { Text(stringResource(R.string.delete_receiver_confirm, target.name)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        // 删除失败（密文复活）静默无感知：与同页 save 路径
                        // 对齐，失败提示用户手动重试
                        if (!ScreenShareReceiverManager.deleteConfig(context, target.id)) {
                            android.widget.Toast.makeText(
                                context, R.string.failed, android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                        refresh()
                    }
                    deleteTarget = null
                }) {
                    Text(stringResource(R.string.Confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.Cancel))
                }
            }
        )
    }
}

@Composable
private fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = title)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ReceiverConfigDialog(
    existing: ScreenShareReceiverConfig?,
    onDismiss: () -> Unit,
    onConfirm: (ScreenShareReceiverConfig) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var nameInput by remember { mutableStateOf(existing?.name ?: "") }
    var addressInput by remember { mutableStateOf(existing?.address ?: "") }
    var portInput by remember { mutableStateOf(existing?.port?.toString() ?: "") }
    var useSsh by remember { mutableStateOf(existing?.useSsh ?: false) }
    var sshPortInput by remember { mutableStateOf(existing?.sshPort?.toString() ?: "22") }
    var sshUserNameInput by remember { mutableStateOf(existing?.sshUserName ?: "") }
    var sshPasswordInput by remember { mutableStateOf(existing?.sshPassword ?: "") }
    var passwordInput by remember { mutableStateOf(existing?.password ?: "") }

    val portValid = portInput.toIntOrNull()?.let { it in 1024..65535 } == true
    val addressValid = addressInput.isNotEmpty()
    val sshPortValid = sshPortInput.toIntOrNull()?.let { it in 1..65535 } == true
    val sshValid = !useSsh || (sshUserNameInput.isNotEmpty() && sshPortValid)
    val passwordValid = passwordInput.let { it.isEmpty() || it.isNotBlank() }
    val sshPasswordValid = !useSsh || sshPasswordInput.isNotBlank()

    CenteredAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (existing == null) R.string.add_receiver
                    else R.string.edit_receiver
                )
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = { nameInput = it },
                    label = { Text(stringResource(R.string.receiver_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = addressInput,
                    onValueChange = { addressInput = it },
                    label = {
                        Text(
                            stringResource(
                                if (useSsh) R.string.ssh_server_address
                                else R.string.receiver_address
                            )
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = if (useSsh) KeyboardType.Text else KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = portInput,
                    onValueChange = { portInput = it },
                    label = {
                        Text(
                            stringResource(
                                if (useSsh) R.string.ssh_tunnel_remote_port
                                else R.string.receiver_port
                            )
                        )
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                SwitchRow(
                    title = stringResource(R.string.receiver_use_ssh),
                    checked = useSsh,
                    onCheckedChange = { useSsh = it }
                )

                if (useSsh) {
                    OutlinedTextField(
                        value = sshPortInput,
                        onValueChange = { sshPortInput = it },
                        label = { Text(stringResource(R.string.ssh_server_port)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = sshUserNameInput,
                        onValueChange = { sshUserNameInput = it },
                        label = { Text(stringResource(R.string.ssh_server_user_name)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = sshPasswordInput,
                        onValueChange = { sshPasswordInput = it },
                        label = { Text(stringResource(R.string.ssh_server_user_password)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    // —— 主机密钥指纹（TOFU，与发送端 SSH 设置同语义）——
                    // 按当前编辑中的 host:port 显示已固定指纹；服务器重装/换钥
                    // 导致连接被拒（ssh_hostkey_changed）时在此显式重置
                    val pinnedHostKey by SensitiveStore.rememberSensitiveValue(
                        context,
                        SensitiveStore.sshHostKeyStoreKey(
                            addressInput,
                            sshPortInput.toIntOrNull() ?: 22
                        ),
                        ""
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.ssh_host_key_fingerprint),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = if (pinnedHostKey.isEmpty()) {
                                    stringResource(R.string.ssh_host_key_not_pinned)
                                } else {
                                    "SHA256:" + pinnedHostKey.take(16) +
                                            "…" + pinnedHostKey.takeLast(8)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(
                            onClick = {
                                scope.launch {
                                    SensitiveStore.putSensitive(
                                        context,
                                        SensitiveStore.sshHostKeyStoreKey(
                                            addressInput,
                                            sshPortInput.toIntOrNull() ?: 22
                                        ),
                                        ""
                                    )
                                }
                            },
                            enabled = pinnedHostKey.isNotEmpty()
                        ) {
                            Text(stringResource(R.string.ssh_host_key_reset))
                        }
                    }
                }

                OutlinedTextField(
                    value = passwordInput,
                    onValueChange = { passwordInput = it },
                    label = { Text(stringResource(R.string.receiver_shared_password)) },
                    placeholder = { Text(stringResource(R.string.receiver_shared_password_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    scope.launch {
                        val id = existing?.id ?: ScreenShareReceiverManager.nextId(context)
                        onConfirm(
                            ScreenShareReceiverConfig(
                                id = id,
                                name = nameInput,
                                address = addressInput,
                                port = portInput.toInt(),
                                useSsh = useSsh,
                                sshPort = sshPortInput.toIntOrNull() ?: 22,
                                sshUserName = sshUserNameInput,
                                sshPassword = sshPasswordInput,
                                password = passwordInput
                            )
                        )
                    }
                },
                enabled = portValid && addressValid && sshValid && nameInput.isNotEmpty() && passwordValid && sshPasswordValid
            ) {
                Text(stringResource(R.string.Confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.Cancel))
            }
        }
    )
}
