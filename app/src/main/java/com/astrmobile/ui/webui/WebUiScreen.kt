package com.astrmobile.ui.webui

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.astrmobile.core.ui.Spacing
import com.astrmobile.ui.components.InlineLoading

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebUiScreen(
    initialPort: Int,
    astrbotPort: Int,
    napcatPort: Int,
    onBack: () -> Unit,
) {
    var currentPort by remember { mutableStateOf(initialPort) }
    var loading by remember { mutableStateOf(true) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var canGoBack by remember { mutableStateOf(false) }

    // 网页里的 <input type="file">（知识库上传、插件安装选包）必须由宿主接管，
    // 否则点了没任何反应——这正是之前"兜底入口"名不副实的原因。
    var fileCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val picked = result.data?.data?.let { arrayOf(it) } ?: emptyArray()
        fileCallback?.onReceiveValue(picked)
        fileCallback = null
    }

    BackHandler(enabled = canGoBack) {
        webViewRef?.goBack()
    }

    // WebView 不会随 Compose 页面自动释放，必须显式销毁，否则离开页面后音频/网络仍被占用
    DisposableEffect(Unit) {
        onDispose {
            webViewRef?.let { view ->
                runCatching { view.stopLoading() }
                runCatching { view.destroy() }
            }
            webViewRef = null
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
            }
            Text(
                text = if (currentPort == astrbotPort) "AstrBot 后台" else "NapCat 登录",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.width(Spacing.md))

            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                PortChip("AstrBot", currentPort == astrbotPort) {
                    currentPort = astrbotPort
                    loading = true
                    webViewRef?.loadUrl("http://127.0.0.1:$astrbotPort")
                }
                PortChip("NapCat", currentPort == napcatPort) {
                    currentPort = napcatPort
                    loading = true
                    webViewRef?.loadUrl("http://127.0.0.1:$napcatPort")
                }
            }

            Spacer(Modifier.weight(1f))
            IconButton(onClick = { webViewRef?.reload() }) {
                Icon(Icons.Rounded.Refresh, contentDescription = "刷新")
            }
        }

        // NapCat WebUI 的口令由 start.sh 固定成 astrmobile（首次登录后请自行修改），
        // 不需要再去日志里翻随机串。
        if (currentPort == napcatPort) {
            Text(
                text = "Token 默认 astrmobile；登录后建议在设置里改掉",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = Spacing.xl, vertical = 2.dp),
            )
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            if (loading) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.xl, vertical = Spacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    InlineLoading(size = 12.dp, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        text = "正在连接 http://127.0.0.1:$currentPort",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background),
                factory = { context ->
                    WebView(context).apply {
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            databaseEnabled = true
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            builtInZoomControls = false
                            displayZoomControls = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                            cacheMode = WebSettings.LOAD_DEFAULT
                        }
                        isVerticalScrollBarEnabled = true
                        webChromeClient = object : WebChromeClient() {
                            override fun onShowFileChooser(
                                view: WebView?,
                                filePathCallback: ValueCallback<Array<Uri>>?,
                                fileChooserParams: FileChooserParams?,
                            ): Boolean {
                                fileCallback?.onReceiveValue(null)
                                fileCallback = filePathCallback
                                val intent = runCatching { fileChooserParams?.createIntent() }.getOrNull()
                                    ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                                        type = "*/*"
                                        addCategory(Intent.CATEGORY_OPENABLE)
                                    }
                                val launched = runCatching { filePicker.launch(intent) }.isSuccess
                                if (!launched) {
                                    filePathCallback?.onReceiveValue(null)
                                    fileCallback = null
                                }
                                return launched
                            }
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                loading = true
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                loading = false
                                canGoBack = view?.canGoBack() == true
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?,
                            ) {
                                // 容器尚未启动时连接会被拒绝，onPageFinished 不会回调，
                                // 不处理会让顶部一直显示"正在连接"。
                                if (request?.isForMainFrame == true) loading = false
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): Boolean = false
                        }
                        loadUrl("http://127.0.0.1:$currentPort")
                        webViewRef = this
                    }
                },
                update = { view ->
                    webViewRef = view
                },
            )
        }
    }
}

@Composable
private fun PortChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
            )
        },
        shape = RoundedCornerShape(10.dp),
    )
}
