package com.apiguanli.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.apiguanli.app.data.ApiFormat
import com.apiguanli.app.data.Repo
import com.apiguanli.app.data.apiKey
import com.apiguanli.app.data.inputUrl
import com.apiguanli.app.data.isKeyOnly
import com.apiguanli.app.data.SiteStore
import com.apiguanli.app.net.LoginResult
import com.apiguanli.app.net.NewApiClient
import com.apiguanli.app.net.SiteStatus
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(siteId: String?, onBack: () -> Unit, onDone: (String) -> Unit) {
    val existing = remember(siteId) { siteId?.let { SiteStore.get(it) } }
    // Key sites show the address as typed, including any API path.
    var url by rememberSaveable { mutableStateOf(existing?.let { if (it.isKeyOnly) it.inputUrl else it.baseUrl }.orEmpty()) }
    var apiFormat by rememberSaveable { mutableStateOf(existing?.apiFormat ?: ApiFormat.OPENAI) }
    // Editing a key site may keep its key and only change the address.
    val keptKey = existing?.takeIf { it.isKeyOnly }?.apiKey().orEmpty()
    // Editing an existing site keeps its kind; a new site starts with account login.
    var keyMode by rememberSaveable { mutableStateOf(existing?.isKeyOnly == true) }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var siteName by rememberSaveable { mutableStateOf(existing?.name.orEmpty()) }
    var username by rememberSaveable { mutableStateOf(existing?.username.orEmpty()) }
    var password by rememberSaveable { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var need2fa by remember { mutableStateOf(false) }
    // Logged in, but creating the access token needs a second, fresh 2FA code.
    var needProof by remember { mutableStateOf(false) }
    var proofUserId by remember { mutableStateOf<Int?>(null) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var infoMsg by remember { mutableStateOf<String?>(null) }
    // Kept between the password step and the 2FA step so the session cookie survives.
    var pending by remember { mutableStateOf<Triple<NewApiClient, String, SiteStatus>?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun finish(client: NewApiClient, baseUrl: String, status: SiteStatus, userId: Int, proofCode: String? = null) {
        step = "生成访问令牌…"
        val token = try {
            client.generateAccessToken(password, proofCode)
        } catch (e: NewApiClient.NeedProofCode) {
            pending = Triple(client, baseUrl, status)
            proofUserId = userId
            need2fa = false
            needProof = true
            code = ""
            infoMsg = "已登录。这个账号开了两步验证，生成访问令牌还要再输入一个验证码（请等验证器刷新出下一个新码）。"
            return
        }
        step = "读取账户信息…"
        val site = Repo.saveLogin(baseUrl, status, userId, token, username.trim(), existing?.id)
        onDone(site.id)
    }

    fun submit() {
        if (busy) return
        errorMsg = null
        infoMsg = null
        busy = true
        scope.launch {
            try {
                val p = pending
                if (keyMode) {
                    step = "验证 Key、拉取模型…"
                    val site = Repo.saveKeySite(url, apiKey.ifBlank { keptKey }, existing?.id, siteName, apiFormat)
                    onDone(site.id)
                } else if (needProof && p != null && proofUserId != null) {
                    finish(p.first, p.second, p.third, proofUserId!!, code.trim())
                } else if (need2fa && p != null) {
                    step = "两步验证…"
                    val id = p.first.login2fa(code.trim())
                    finish(p.first, p.second, p.third, id)
                } else {
                    val baseUrl = Repo.normalizeUrl(url)
                    val client = NewApiClient(baseUrl)
                    step = "连接站点…"
                    val status = client.status()
                    if (!status.passwordLogin) throw IllegalStateException("该站点关闭了账号密码登录")
                    if (status.turnstile) throw IllegalStateException("该站点开启了人机验证（Turnstile），暂不支持在 App 内用账号密码登录")
                    step = "登录中…"
                    when (val r = client.login(username.trim(), password)) {
                        is LoginResult.Need2FA -> {
                            pending = Triple(client, baseUrl, status)
                            need2fa = true
                        }
                        is LoginResult.Success -> finish(client, baseUrl, status, r.userId)
                    }
                }
            } catch (e: Exception) {
                errorMsg = e.message ?: e.javaClass.simpleName
            } finally {
                busy = false
                step = ""
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "添加站点" else if (keyMode) "修改 Key" else "重新登录") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (existing == null) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(false to "账号密码登录", true to "只填 URL + Key").forEachIndexed { i, (mode, label) ->
                        SegmentedButton(
                            selected = keyMode == mode,
                            onClick = { keyMode = mode; errorMsg = null },
                            shape = SegmentedButtonDefaults.itemShape(i, 2),
                            enabled = !busy,
                        ) { Text(label) }
                    }
                }
            }
            OutlinedTextField(
                value = url,
                onValueChange = { url = it; pending = null; need2fa = false; needProof = false },
                label = { Text(if (keyMode) "接口地址" else "站点地址") },
                placeholder = { Text("例如 api.gemai.cc") },
                singleLine = true,
                enabled = (existing == null || keyMode) && !busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            if (keyMode) {
                EndpointFormatPicker(url, apiFormat, enabled = !busy) { apiFormat = it }
                OutlinedTextField(
                    value = siteName,
                    onValueChange = { siteName = it },
                    label = { Text("名称") },
                    placeholder = { Text("例如 火山方舟；不填就用站点自己的名字") },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it.trim() },
                    label = { Text(if (keptKey.isNotEmpty()) "API Key（不改就留空）" else "API Key（sk-…）") },
                    singleLine = true,
                    enabled = !busy,
                    visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, "显示 Key")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text("用户名") },
                singleLine = true,
                enabled = !busy && !need2fa && !needProof,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("密码") },
                singleLine = true,
                enabled = !busy && !need2fa && !needProof,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { showPassword = !showPassword }) {
                        Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, "显示密码")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (need2fa || needProof) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text(if (needProof) "新的两步验证码（用于生成访问令牌）" else "两步验证码 / 备用码") },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            }
            if (url.trim().startsWith("http://", ignoreCase = true)) {
                Text(
                    "这是不加密的 http 地址，密码和 Key 在网络上是明文传输的，只建议用于自己搭建、在内网的站点。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            infoMsg?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            errorMsg?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = { submit() },
                enabled = !busy && url.isNotBlank() && if (keyMode) apiKey.length >= 8 || (apiKey.isEmpty() && keptKey.isNotEmpty()) else
                    username.isNotBlank() && password.isNotBlank() && (!(need2fa || needProof) || code.isNotBlank()),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(step.ifBlank { "请稍候…" })
                    }
                } else {
                    Text(if (keyMode) "保存" else if (needProof) "生成访问令牌" else if (need2fa) "验证并登录" else "登录")
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Text(
                    if (keyMode) "· 只需要接口地址和一个 API Key，不用登录账号。地址带路径也可以，如火山方舟的 …/api/coding/v3。\n" +
                        "· 能做：一键复制、拉取模型列表、对话、测试模型；OpenAI 格式还能体检；NewAPI 站点还能看这个 Key 的用量和记录。\n" +
                        "· 拉不到模型列表时（常见于 Anthropic 格式），可以在站点设置里手动添加模型。\n" +
                        "· 看不到：账户总余额、其他令牌。\n" +
                        "· Key 用系统密钥库加密，只保存在本机。"
                    else "· 密码只用于这次登录，不会保存。\n" +
                        "· 登录后 App 会为该账号生成一个访问令牌，加密保存在本机，之后都用它访问站点。\n" +
                        "· 新版 NewAPI：会新建一个名为「中转站管家 日期」的令牌，只能查看信息和签到，不能改动账号和 Key，可在网页「访问令牌」里管理。\n" +
                        "· 旧版 NewAPI：生成新令牌会让之前的「系统访问令牌」失效（不影响 API Key / sk- 令牌）。",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

/** OpenAI / Anthropic / no completion, like the endpoint option in other AI clients, with the resulting URL. */
@Composable
private fun EndpointFormatPicker(url: String, format: String, enabled: Boolean, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("自动补全接口后缀", style = MaterialTheme.typography.labelLarge)
        val options = listOf(ApiFormat.OPENAI to "OpenAI", ApiFormat.ANTHROPIC to "Anthropic", ApiFormat.RAW to "不补全")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (value, label) ->
                SegmentedButton(
                    selected = format == value,
                    onClick = { onChange(value) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                    enabled = enabled,
                ) { Text(label, maxLines = 1) }
            }
        }
        val hint = when (format) {
            ApiFormat.ANTHROPIC -> "按 Anthropic（Claude）格式，自动补上 /v1/messages。"
            ApiFormat.RAW -> "地址原样使用，请填完整，例如 …/v1/chat/completions；以 /messages 结尾的按 Anthropic 格式发送。"
            else -> "按 OpenAI 格式，自动补上 /v1/chat/completions；地址自带路径（如 …/api/coding/v3）时只补 /chat/completions。"
        }
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val preview = Repo.previewUrl(url, format)
        Text(
            if (preview != null) "实际请求：$preview"
            else if (format == ApiFormat.RAW && url.isNotBlank()) "实际请求：地址还不完整"
            else "实际请求：填好地址后显示",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
