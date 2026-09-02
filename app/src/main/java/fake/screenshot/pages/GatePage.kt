package fake.screenshot.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import fake.screenshot.R
import fake.screenshot.defense.DefenseProtocol
import fake.screenshot.defense.GateManager
import fake.screenshot.defense.GateResult
import fake.screenshot.defense.SensitiveStore
import kotlinx.coroutines.launch

/**
 * 启动门禁：安全密码与胁迫密码共用同一入口，界面不做任何区分。
 * 胁迫密码命中时在进入主界面前完成销毁，随后呈现全新默认状态。
 */
@Composable
fun GateCompose(onUnlocked: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var verifying by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineSmall
            )
            Spacer(modifier = Modifier.height(32.dp))
            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    failed = false
                },
                label = { Text(stringResource(R.string.enter_password)) },
                isError = failed,
                supportingText = if (failed) {
                    { Text(stringResource(R.string.incorrect_password)) }
                } else null,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = {
                    verifying = true
                    scope.launch {
                        // 兜底：验证链任何意外异常按"密码错误"处理——
                        // 不捕获会让 verifying 永久为 true（按钮卡死在
                        // 加载态且无提示）或直接崩溃进程
                        val result = runCatching { GateManager.verifyGate(password) }
                            .getOrNull()
                        when (result) {
                            GateResult.SECURITY -> {
                                // 组装/激活 DK 拆分（失败不阻断解锁，DK 功能 fail-closed）
                                runCatching { GateManager.onSecurityUnlock(password) }
                                // 首装共享密码兜底补跑：冷启动锁定态 DK 不可用
                                // 失败的那次在此重试（幂等，详见其 KDoc）
                                runCatching { SensitiveStore.ensureDefaultSharePassword(context) }
                                onUnlocked()
                            }
                            GateResult.COERCION -> {
                                // NonCancellable 在 DefenseProtocol 内部包裹：
                                // 本协程随 Activity 重建被取消也不中断销毁序列
                                runCatching { DefenseProtocol.destroyForCoercion() }
                                onUnlocked()
                            }
                            else -> {
                                failed = true
                                verifying = false
                            }
                        }
                    }
                },
                enabled = password.isNotEmpty() && !verifying,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (verifying) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(stringResource(R.string.Confirm))
                }
            }
        }
    }
}
