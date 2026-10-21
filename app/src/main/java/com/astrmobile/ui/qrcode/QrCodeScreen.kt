package com.astrmobile.ui.qrcode

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astrmobile.MainViewModel
import com.astrmobile.core.container.NapCatApi
import com.astrmobile.core.container.QrCodeDecoder
import com.astrmobile.ui.components.SectionCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * 原生扫码登录页。
 *
 * 不再把用户丢进 NapCat 的网页后台：App 直接调它的本地接口，
 * 自己取二维码、自己轮询登录状态，二维码画在自己的界面里。
 */
@Composable
fun QrCodeScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onOpenWebFallback: () -> Unit,
) {
    // null = 还在读口令，空列表 = 确实没有候选。不用空列表当初始值，
    // 否则首帧会闪一下红色错误。
    val tokens by produceState<List<String>?>(null) { value = viewModel.readNapCatTokens() }

    var qrcode by remember { mutableStateOf<Bitmap?>(null) }
    var statusText by remember { mutableStateOf("正在连接 NapCat…") }
    var isError by remember { mutableStateOf(false) }
    var isSuccess by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }

    // 自动读到口令失败时，允许手动填一次（NapCat 装在非常规路径时会发生）
    var extraToken by remember { mutableStateOf<String?>(null) }
    var showManual by remember { mutableStateOf(false) }
    var manualInput by remember { mutableStateOf("") }

    LaunchedEffect(tokens, extraToken, reloadKey) {
        val base = tokens ?: return@LaunchedEffect
        val candidates = (base + listOfNotNull(extraToken?.takeIf { it.isNotBlank() })).distinct()
        if (candidates.isEmpty()) {
            statusText = "还没读到 NapCat 的口令，请确认容器已启动并稍等 30 秒"
            isError = true
            return@LaunchedEffect
        }

        qrcode = null
        isError = false
        isSuccess = false
        statusText = "正在登录 NapCat…"

        val api = NapCatApi()

        // NapCat 的口令可能被随机化过，逐个候选尝试，避免一次失败就卡死。
        var credential: String? = null
        var lastError = ""
        for (candidate in candidates) {
            val result = api.login(candidate)
            if (result.isSuccess) {
                credential = result.getOrNull()
                break
            }
            lastError = result.exceptionOrNull()?.message.orEmpty()
        }

        if (credential.isNullOrBlank()) {
            statusText = buildString {
                append("登录 NapCat 失败：")
                append(lastError.ifBlank { "口令不匹配" })
                append("\n试过的口令：")
                append(candidates.joinToString("、") { it.take(4) + "***" })
            }
            isError = true
            showManual = true
            return@LaunchedEffect
        }

        statusText = "正在获取二维码…"
        // NapCat 首次生成二维码要先拉 QQ 客户端，头几次可能返回
        // "QRCode Get Error"。这属于「还没准备好」，重试几次就好了。
        var rawQrcode: String? = null
        var qrError = ""
        for (attempt in 1..3) {
            val result = api.fetchQrcode(credential)
            if (result.isSuccess) {
                rawQrcode = result.getOrNull()
                break
            }
            qrError = result.exceptionOrNull()?.message.orEmpty()
            if (qrError.contains("Is Logined", ignoreCase = true)) break
            if (attempt < 3) {
                statusText = "NapCat 正在准备二维码…（第 $attempt 次）"
                delay(2_000)
            }
        }

        if (rawQrcode == null) {
            // 已经登录过时 NapCat 会直接拒绝发码，这不是故障。
            if (qrError.contains("Is Logined", ignoreCase = true)) {
                statusText = "这个 QQ 已经登录了，无需再扫码"
                isSuccess = true
            } else {
                statusText = buildString {
                    append("获取二维码失败：")
                    append(qrError.ifBlank { "NapCat 没给二维码" })
                    append("\n试过的口令：")
                    append(candidates.joinToString("、") { it.take(4) + "***" })
                }
                isError = true
                showManual = true
            }
            return@LaunchedEffect
        }

        qrcode = QrCodeDecoder.decode(rawQrcode)
        statusText = if (qrcode == null) "二维码解析失败，请点刷新重试" else "用手机 QQ 扫描上方二维码"
        isError = qrcode == null
        if (qrcode == null) return@LaunchedEffect

        var qrFetchedAt = System.currentTimeMillis()
        var failures = 0

        while (isActive) {
            // 二维码有效期约 2 分钟，过期后画面上是张废码，扫了也没反应。
            // 提前刷新，但不用重跑整个流程（会重新登录 NapCat）。
            if (System.currentTimeMillis() - qrFetchedAt > 100_000) {
                val fresh = api.fetchQrcode(credential).getOrNull()
                val bitmap = fresh?.let { QrCodeDecoder.decode(it) }
                if (bitmap != null) {
                    qrcode = bitmap
                    qrFetchedAt = System.currentTimeMillis()
                    statusText = "二维码已自动刷新，请用手机 QQ 扫描"
                    isError = false
                }
            }

            val status = api.checkStatus(credential)
            if (status == null) {
                failures++
                if (failures >= 5) {
                    statusText = "跟 NapCat 的连接断开了，请返回后重新进入本页"
                    isError = true
                    break
                }
            } else {
                failures = 0
                when {
                    status.isLogin -> {
                        statusText = "登录成功，QQ 已接入"
                        isSuccess = true
                        isError = false
                        break
                    }
                    status.isOffline -> statusText = "账号已掉线，请重新扫码"
                    status.loginPhase == "qrcode_scanned" ||
                        status.loginPhase == "initializing" -> statusText = "已扫描，请在手机上确认登录"
                    status.loginPhase == "qrcode_expired" -> {
                        statusText = "二维码已过期，正在重新获取…"
                        qrFetchedAt = 0
                    }
                    !status.error.isNullOrBlank() -> {
                        statusText = "登录出错：${status.error}"
                        isError = true
                    }
                }
            }
            delay(2_000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = "QQ 扫码登录",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { reloadKey++ }) {
                Icon(Icons.Rounded.Refresh, contentDescription = "刷新二维码")
            }
        }

        Spacer(Modifier.height(16.dp))

        // 二维码区域：白底保证识别率
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .background(Color.White, RoundedCornerShape(20.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val current = qrcode
            when {
                isSuccess -> Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = Color(0xFF167A57),
                    modifier = Modifier.size(64.dp),
                )
                current != null -> Image(
                    bitmap = current.asImageBitmap(),
                    contentDescription = "QQ 登录二维码",
                    modifier = Modifier.size(260.dp),
                )
                isError -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = Color(0xFFB3261E),
                        modifier = Modifier.size(56.dp),
                    )
                    Text(
                        text = "没能拿到二维码",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF5F6368),
                    )
                }
                else -> CircularProgressIndicator(color = Color(0xFF167A57))
            }
        }

        Spacer(Modifier.height(16.dp))

        SectionCard(title = "状态") {
            Text(
                text = statusText,
                style = MaterialTheme.typography.titleMedium,
                color = when {
                    isSuccess -> MaterialTheme.colorScheme.primary
                    isError -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                },
                textAlign = TextAlign.Start,
            )
            if (isSuccess) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "回到控制台，AstrBot 的「QQ 扫码登录」会显示已就绪。",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // 自动读口令失败时的兜底：NapCat 装在非常规路径、或口令被改过时，
        // 用户可以从日志里翻到口令，在这里手动填一次。
        if (showManual && !isSuccess) {
            Spacer(Modifier.height(14.dp))
            SectionCard(title = "手动输入 NapCat 口令") {
                Text(
                    text = "去「实时日志」搜 WebUi 或 Token，通常会看到一串口令；" +
                        "也可以在 NapCat 网页版的登录提示里找。填进来再试一次。",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 17.sp,
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = manualInput,
                    onValueChange = { manualInput = it },
                    label = { Text("口令", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        if (manualInput.isNotBlank()) {
                            extraToken = manualInput.trim()
                            reloadKey++
                        }
                    },
                    enabled = manualInput.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) { Text("用这个口令重试") }
            }
        }

        Spacer(Modifier.height(14.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { reloadKey++ },
                modifier = Modifier
                    .weight(1f)
                    .height(46.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("刷新二维码")
            }
        }

        Spacer(Modifier.height(10.dp))

        TextButton(onClick = onOpenWebFallback, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "打不开？改用 NapCat 网页版",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = "扫码只是登录 QQ。登录后还需要在 AstrBot 后台添加 aiocqhttp 通信接口（ws://127.0.0.1:6199/ws）。",
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            lineHeight = 17.sp,
        )
    }
}
