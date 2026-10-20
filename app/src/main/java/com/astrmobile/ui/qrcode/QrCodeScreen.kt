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
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
    val token by produceState<String?>(null) { value = viewModel.readNapCatToken() }

    var qrcode by remember { mutableStateOf<Bitmap?>(null) }
    var statusText by remember { mutableStateOf("正在连接 NapCat…") }
    var isError by remember { mutableStateOf(false) }
    var isSuccess by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(token, reloadKey) {
        val webuiToken = token
        if (webuiToken.isNullOrBlank()) {
            statusText = "还没读到 NapCat 的 Token，请确认容器已启动并稍等 30 秒"
            isError = true
            return@LaunchedEffect
        }

        qrcode = null
        isError = false
        isSuccess = false
        statusText = "正在登录 NapCat…"

        val api = NapCatApi()

        val credential = api.login(webuiToken).getOrElse { error ->
            statusText = "连接 NapCat 失败：${error.message}"
            isError = true
            return@LaunchedEffect
        }

        statusText = "正在获取二维码…"
        val rawQrcode = api.fetchQrcode(credential).getOrElse { error ->
            statusText = "获取二维码失败：${error.message}"
            isError = true
            return@LaunchedEffect
        }

        qrcode = QrCodeDecoder.decode(rawQrcode)
        statusText = if (qrcode == null) "二维码解析失败" else "用手机 QQ 扫描上方二维码"
        isError = qrcode == null

        while (isActive) {
            val status = api.checkStatus(credential)
            if (status != null) {
                when {
                    status.isLogin -> {
                        statusText = "登录成功，QQ 已接入"
                        isSuccess = true
                        break
                    }
                    status.isOffline -> statusText = "账号已掉线，请重新扫码"
                    status.loginPhase == "qrcode_scanned" ||
                        status.loginPhase == "initializing" -> statusText = "已扫描，请在手机上确认登录"
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
