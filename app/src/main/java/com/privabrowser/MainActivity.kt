package com.privabrowser

import android.Manifest
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.*
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.privabrowser.databinding.ActivityMainBinding
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adBlocker: AdBlocker
    private val detectedVideoUrls = mutableListOf<String>()

    // Kya current page YouTube hai
    private var isYoutubePage = false

    companion object {
        const val HOME_URL = "https://duckduckgo.com"
        const val PERMISSION_STORAGE = 100

        // YouTube URL patterns
        private val YOUTUBE_DOMAINS = listOf("youtube.com/watch", "youtu.be/", "youtube.com/shorts")

        // Direct video extensions
        private val VIDEO_EXTENSIONS = listOf(".mp4", ".m3u8", ".mkv", ".webm", ".avi", ".mov", ".ts", ".flv", ".3gp")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // LIFECYCLE
    // ─────────────────────────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()

        // YoutubeDL + FFmpeg engine init
        try {
            YoutubeDL.getInstance().init(applicationContext)
            FFmpeg.getInstance().init(applicationContext)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        adBlocker = AdBlocker(this)
        lifecycleScope.launch { adBlocker.initialize() }

        setupWebView()
        setupToolbar()
        setupBottomBar()

        binding.webView.loadUrl(intent.getStringExtra("url") ?: HOME_URL)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WEBVIEW SETUP
    // ─────────────────────────────────────────────────────────────────────────
    private fun setupWebView() {
        with(binding.webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            userAgentString = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"
            setGeolocationEnabled(false)
            @Suppress("DEPRECATION") saveFormData = false
            @Suppress("DEPRECATION") savePassword = false
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(binding.webView, false)
        }

        binding.webView.webViewClient = PrivaWebViewClient()
        binding.webView.webChromeClient = PrivaWebChromeClient()
        binding.webView.addJavascriptInterface(VideoDetector(), "VideoDetector")

        binding.webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            handleDirectDownload(url, mimeType, contentDisposition)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FIX 1: WebViewClient — YouTube detection onPageFinished mein
    // ─────────────────────────────────────────────────────────────────────────
    inner class PrivaWebViewClient : WebViewClient() {

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val url = request.url.toString()
            if (adBlocker.shouldBlock(url)) return WebResourceResponse("text/plain", "utf-8", null)
            detectVideoUrl(url)
            return super.shouldInterceptRequest(view, request)
        }

        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
            binding.progressBar.visibility = View.VISIBLE
            binding.urlBar.setText(url)
            detectedVideoUrls.clear()
            isYoutubePage = false
            hideDownloadBtn()
        }

        override fun onPageFinished(view: WebView, url: String) {
            binding.progressBar.visibility = View.GONE
            binding.urlBar.setText(url)

            // ✅ FIX 1: YouTube page detect karo aur button dikhao
            isYoutubePage = YOUTUBE_DOMAINS.any { url.contains(it, ignoreCase = true) }
            if (isYoutubePage) {
                showDownloadBtn(isYoutube = true)
            } else {
                injectVideoDetectionScript(view)
            }
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
            if (request.isForMainFrame) binding.progressBar.visibility = View.GONE
        }
    }

    inner class PrivaWebChromeClient : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            binding.progressBar.progress = newProgress
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // VIDEO DETECTION (non-YouTube pages)
    // ─────────────────────────────────────────────────────────────────────────
    private fun detectVideoUrl(url: String) {
        if (VIDEO_EXTENSIONS.any { url.contains(it, ignoreCase = true) }
            && !detectedVideoUrls.contains(url)) {
            detectedVideoUrls.add(url)
            runOnUiThread { showDownloadBtn(isYoutube = false) }
        }
    }

    private fun injectVideoDetectionScript(view: WebView) {
        val script = """
            (function() {
                function notifyVideo(url) {
                    if (!url) return;
                    var exts = ['.mp4','.m3u8','.webm','.mkv','.mov','.avi','.ts','.flv','.3gp'];
                    for (var i = 0; i < exts.length; i++) {
                        if (url.toLowerCase().indexOf(exts[i]) !== -1) {
                            VideoDetector.onVideoFound(url); break;
                        }
                    }
                }
                document.querySelectorAll('video, source').forEach(function(el) {
                    notifyVideo(el.src); notifyVideo(el.currentSrc);
                });
                new MutationObserver(function(mutations) {
                    mutations.forEach(function(m) {
                        m.addedNodes.forEach(function(node) {
                            if (node.tagName === 'VIDEO' || node.tagName === 'SOURCE') {
                                notifyVideo(node.src); notifyVideo(node.currentSrc);
                            }
                        });
                    });
                }).observe(document.documentElement, {childList: true, subtree: true});
            })();
        """.trimIndent()
        view.evaluateJavascript(script, null)
    }

    inner class VideoDetector {
        @JavascriptInterface
        fun onVideoFound(url: String) {
            runOnUiThread { detectVideoUrl(url) }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DOWNLOAD BUTTON UI
    // ─────────────────────────────────────────────────────────────────────────
    private fun showDownloadBtn(isYoutube: Boolean) {
        binding.btnDownload.visibility = View.VISIBLE
        // YouTube = red, normal video = orange
        binding.btnDownload.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (isYoutube) android.graphics.Color.parseColor("#FF0000")
            else android.graphics.Color.parseColor("#FF4500")
        )
        // Pulse animation
        binding.btnDownload.animate()
            .scaleX(1.25f).scaleY(1.25f).setDuration(150)
            .withEndAction {
                binding.btnDownload.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
            }.start()

        binding.tvVideoCount.visibility = View.VISIBLE
        binding.tvVideoCount.text = if (isYoutube) "YT" else detectedVideoUrls.size.toString()
    }

    private fun hideDownloadBtn() {
        binding.btnDownload.visibility = View.GONE
        binding.tvVideoCount.visibility = View.GONE
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FIX 3: Toolbar — btnDownload click -> showQualityPopup
    // ─────────────────────────────────────────────────────────────────────────
    private fun setupToolbar() {
        binding.urlBar.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                navigateTo(binding.urlBar.text.toString()); true
            } else false
        }

        // ✅ FIX 3: btnDownload click -> showQualityPopup(currentUrl)
        binding.btnDownload.setOnClickListener {
            val currentUrl = binding.webView.url
            if (currentUrl.isNullOrEmpty()) {
                Toast.makeText(this, "Koi page nahi khula!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (isYoutubePage || YOUTUBE_DOMAINS.any { currentUrl.contains(it, ignoreCase = true) }) {
                // YouTube page → quality popup
                showQualityPopup(currentUrl)
            } else if (detectedVideoUrls.isNotEmpty()) {
                // Direct video URL detected → direct download dialog
                showDirectDownloadDialog()
            } else {
                Toast.makeText(this, "Is page pe koi video nahi mila!", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnPlaylist.setOnClickListener {
            startActivity(Intent(this, PlaylistActivity::class.java))
        }

        binding.btnOverflow.setOnClickListener { view ->
            val popup = android.widget.PopupMenu(this, view)
            popup.menu.add("🏠 Home")
            popup.menu.add("🔄 Refresh")
            popup.menu.add("🧹 Clear Data")
            popup.menu.add("📋 Playlist")
            popup.setOnMenuItemClickListener { item ->
                when (item.title.toString()) {
                    "🏠 Home" -> startActivity(Intent(this, HomeActivity::class.java))
                    "🔄 Refresh" -> binding.webView.reload()
                    "🧹 Clear Data" -> clearBrowsingData()
                    "📋 Playlist" -> startActivity(Intent(this, PlaylistActivity::class.java))
                }
                true
            }
            popup.show()
        }
    }

    private fun setupBottomBar() {
        binding.btnBack.setOnClickListener { if (binding.webView.canGoBack()) binding.webView.goBack() }
        binding.btnForward.setOnClickListener { if (binding.webView.canGoForward()) binding.webView.goForward() }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // FIX 2: showQualityPopup — YoutubeDL.execute() with format selection
    // ─────────────────────────────────────────────────────────────────────────
    private fun showQualityPopup(videoUrl: String) {
        Toast.makeText(this, "⏳ Video formats fetch ho rahe hain...", Toast.LENGTH_LONG).show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                // YoutubeDL se available formats fetch karo
                val request = YoutubeDLRequest(videoUrl).apply {
                    addOption("--dump-json")
                    addOption("--no-playlist")
                }
                val videoInfo = YoutubeDL.getInstance().getInfo(request)
                val formats = videoInfo.formats

                // Sirf video formats filter karo (height > 0)
                data class FormatItem(
                    val label: String,
                    val formatId: String,
                    val hasAudio: Boolean
                )

                val formatItems = mutableListOf<FormatItem>()

                formats?.forEach { format ->
                    if ((format.height ?: 0) > 0) {
                        val height = format.height ?: 0
                        val ext = format.ext ?: "mp4"
                        val sizeMB = if ((format.fileSize ?: 0L) > 0)
                            " — ${format.fileSize!! / (1024 * 1024)} MB"
                        else ""
                        val hasAudio = format.acodec != null && format.acodec != "none"
                        val audioTag = if (!hasAudio) " [video only]" else ""
                        val label = "${height}p (${ext.uppercase()})$sizeMB$audioTag"
                        formatItems.add(FormatItem(label, format.formatId ?: "best", hasAudio))
                    }
                }

                // Unique quality labels, descending order (4K → 144p)
                val uniqueItems = formatItems
                    .distinctBy { it.label }
                    .sortedByDescending { it.formatId }

                // "Best" option hamesha top pe
                val bestItem = FormatItem("🏆 Best Quality (Auto)", "bestvideo+bestaudio/best", true)
                val allItems = listOf(bestItem) + uniqueItems

                withContext(Dispatchers.Main) {
                    if (allItems.size <= 1) {
                        // Agar formats nahi mile toh seedha best download karo
                        Toast.makeText(this@MainActivity, "Format list nahi mili, best quality se download hoga", Toast.LENGTH_SHORT).show()
                        startYoutubeDLDownload(videoUrl, "bestvideo+bestaudio/best")
                        return@withContext
                    }

                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("📹 Quality Select Karo")
                        .setItems(allItems.map { it.label }.toTypedArray()) { _, which ->
                            val selected = allItems[which]
                            // ✅ FIX 2: YoutubeDL.execute() call with correct format
                            val formatString = if (!selected.hasAudio && selected.formatId != "bestvideo+bestaudio/best") {
                                // Video-only format: audio merge karo
                                "${selected.formatId}+bestaudio/best"
                            } else {
                                selected.formatId
                            }
                            startYoutubeDLDownload(videoUrl, formatString)
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    // YoutubeDL fail hua → fallback: direct DownloadManager
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("⬇ Download")
                        .setMessage("Quality fetch nahi ho paya.\nDirect download karna chahte ho?")
                        .setPositiveButton("Haan") { _, _ ->
                            handleDirectDownload(videoUrl)
                        }
                        .setNegativeButton("Nahi", null)
                        .show()
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // YoutubeDL Execute — actual download trigger
    // ─────────────────────────────────────────────────────────────────────────
    private fun startYoutubeDLDownload(videoUrl: String, formatId: String) {
        // Permission check
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), PERMISSION_STORAGE)
                return
            }
        }

        val outputDir = "${Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)}/AllVideoDownloader"
        java.io.File(outputDir).mkdirs()

        Toast.makeText(this, "⬇ Download shuru ho raha hai...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val request = YoutubeDLRequest(videoUrl).apply {
                    // ✅ FIX 2: -f 'formatId+bestaudio/best' — video + audio merge
                    addOption("-f", formatId)
                    addOption("-o", "$outputDir/%(title)s.%(ext)s")
                    addOption("--merge-output-format", "mp4")
                    addOption("--no-playlist")
                    addOption("--retries", "3")
                    // FFmpeg location (youtubedl-android automatically set karta hai)
                }

                // Progress callback
                val processId = "download_${System.currentTimeMillis()}"
                YoutubeDL.getInstance().execute(request, processId) { progress, _, line ->
                    runOnUiThread {
                        if (progress > 0) {
                            binding.tvVideoCount.text = "${progress.toInt()}%"
                        }
                    }
                }

                // DB mein save karo
                val fileName = "${videoUrl.hashCode()}.mp4"
                AppDatabase.getDatabase(this@MainActivity).videoDao().insert(
                    VideoEntity(
                        title = "YouTube Video",
                        url = videoUrl,
                        localPath = "$outputDir/$fileName",
                        downloadId = System.currentTimeMillis(),
                        timestamp = System.currentTimeMillis(),
                        isDownloaded = true
                    )
                )

                withContext(Dispatchers.Main) {
                    binding.tvVideoCount.text = "✓"
                    Toast.makeText(this@MainActivity, "✅ Download complete!", Toast.LENGTH_LONG).show()
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.tvVideoCount.text = "!"
                    Toast.makeText(this@MainActivity,
                        "❌ Download fail: ${e.message?.take(80)}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DIRECT DOWNLOAD (non-YouTube — DownloadManager)
    // ─────────────────────────────────────────────────────────────────────────
    private fun showDirectDownloadDialog() {
        if (detectedVideoUrls.isEmpty()) {
            Toast.makeText(this, "Koi video nahi mila!", Toast.LENGTH_SHORT).show()
            return
        }
        val titles = detectedVideoUrls.mapIndexed { i, url ->
            "📹 Video ${i + 1} — .${url.substringAfterLast('.').take(4).uppercase()}\n${url.takeLast(50)}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("⬇ Video Download Karo")
            .setItems(titles) { _, which -> handleDirectDownload(detectedVideoUrls[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun handleDirectDownload(
        url: String,
        mimeType: String = "video/mp4",
        contentDisposition: String = ""
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), PERMISSION_STORAGE)
                return
            }
        }

        val fileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(fileName)
            setDescription("Downloading via All Video Downloader Browser")
            setMimeType(mimeType)
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "AllVideoDownloader/$fileName")
            addRequestHeader("User-Agent", binding.webView.settings.userAgentString)
            addRequestHeader("Referer", binding.webView.url ?: "")
        }

        val dm = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadId = dm.enqueue(request)

        lifecycleScope.launch(Dispatchers.IO) {
            AppDatabase.getDatabase(this@MainActivity).videoDao().insert(
                VideoEntity(
                    title = fileName,
                    url = url,
                    localPath = "${Environment.DIRECTORY_DOWNLOADS}/AllVideoDownloader/$fileName",
                    downloadId = downloadId,
                    timestamp = System.currentTimeMillis()
                )
            )
        }
        Toast.makeText(this, "⬇ Downloading: $fileName", Toast.LENGTH_LONG).show()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // NAVIGATION & UTILITY
    // ─────────────────────────────────────────────────────────────────────────
    private fun navigateTo(input: String) {
        val url = when {
            input.startsWith("http://") || input.startsWith("https://") -> input
            input.contains(".") && !input.contains(" ") -> "https://$input"
            else -> "https://duckduckgo.com/?q=${Uri.encode(input)}"
        }
        binding.webView.loadUrl(url)
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(binding.urlBar.windowToken, 0)
    }

    private fun clearBrowsingData() {
        binding.webView.clearCache(true)
        binding.webView.clearHistory()
        binding.webView.clearFormData()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
        WebStorage.getInstance().deleteAllData()
        Toast.makeText(this, "🧹 Browsing data cleared", Toast.LENGTH_SHORT).show()
    }

    override fun onStop() {
        super.onStop()
        if (getSharedPreferences("prefs", Context.MODE_PRIVATE).getBoolean("private_mode", true))
            clearBrowsingData()
    }

    @Deprecated("Use OnBackPressedDispatcher")
    override fun onBackPressed() {
        if (binding.webView.canGoBack()) binding.webView.goBack()
        else @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_STORAGE
            && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
            Toast.makeText(this, "Permission mili! Ab dobara try karo.", Toast.LENGTH_SHORT).show()
    }
}
