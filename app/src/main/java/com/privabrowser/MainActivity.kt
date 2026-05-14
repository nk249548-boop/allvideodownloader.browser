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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.ffmpeg.FFmpeg

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adBlocker: AdBlocker
    private val detectedVideoUrls = mutableListOf<String>()

    companion object {
        const val HOME_URL = "https://duckduckgo.com"
        const val PERMISSION_STORAGE = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.hide()

                // ENGINE START CODE
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

    // ── WEBVIEW ──────────────────────────────────────────────────────────────
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

        // Direct file download listener
        binding.webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            handleDownload(url, mimeType, contentDisposition)
        }
    }

    // ── AD BLOCKER WEBCLIENT ─────────────────────────────────────────────────
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
            hideDownloadBtn()
        }

        override fun onPageFinished(view: WebView, url: String) {
            binding.progressBar.visibility = View.GONE
            binding.urlBar.setText(url)
            injectVideoDetectionScript(view)
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

    // ── VIDEO DETECTION ───────────────────────────────────────────────────────
    private val VIDEO_EXTENSIONS = listOf(".mp4", ".m3u8", ".mkv", ".webm", ".avi", ".mov", ".ts", ".flv", ".3gp")

    private fun detectVideoUrl(url: String) {
        if (VIDEO_EXTENSIONS.any { url.contains(it, ignoreCase = true) } && !detectedVideoUrls.contains(url)) {
            detectedVideoUrls.add(url)
            runOnUiThread { showDownloadBtn() }
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
                            VideoDetector.onVideoFound(url);
                            break;
                        }
                    }
                }
                // Check existing video/source tags
                document.querySelectorAll('video, source').forEach(function(el) {
                    notifyVideo(el.src);
                    notifyVideo(el.currentSrc);
                });
                // Watch for new elements
                new MutationObserver(function(mutations) {
                    mutations.forEach(function(m) {
                        m.addedNodes.forEach(function(node) {
                            if (node.tagName === 'VIDEO' || node.tagName === 'SOURCE') {
                                notifyVideo(node.src);
                                notifyVideo(node.currentSrc);
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

    private fun showDownloadBtn() {
        binding.btnDownload.visibility = View.VISIBLE
        binding.btnDownload.animate().scaleX(1.2f).scaleY(1.2f).setDuration(150)
            .withEndAction { binding.btnDownload.animate().scaleX(1f).scaleY(1f).setDuration(100).start() }
            .start()
        binding.tvVideoCount.visibility = View.VISIBLE
        binding.tvVideoCount.text = detectedVideoUrls.size.toString()
    }

    private fun hideDownloadBtn() {
        binding.btnDownload.visibility = View.GONE
        binding.tvVideoCount.visibility = View.GONE
    }

    // ── DOWNLOAD HANDLER ──────────────────────────────────────────────────────
    private fun showVideoDownloadDialog() {
        if (detectedVideoUrls.isEmpty()) {
            Toast.makeText(this, "No video found on this page", Toast.LENGTH_SHORT).show()
            return
        }
        val titles = detectedVideoUrls.mapIndexed { i, url ->
            "📹 Video ${i + 1}  —  .${url.substringAfterLast('.').take(4).uppercase()}\n${url.takeLast(45)}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("⬇ Download Video")
            .setItems(titles) { _, which -> handleDownload(detectedVideoUrls[which]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun handleDownload(url: String, mimeType: String = "video/mp4", contentDisposition: String = "") {
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

    // ── TOOLBAR ───────────────────────────────────────────────────────────────
    private fun setupToolbar() {
        binding.urlBar.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                navigateTo(binding.urlBar.text.toString()); true
            } else false
        }
        
               binding.btnDownload.setOnClickListener {
            val currentUrl = binding.webView.url
            if (currentUrl != null && (currentUrl.startsWith("http://") || currentUrl.startsWith("https://"))) {
                showQualityPopup(currentUrl)
            } else {
                Toast.makeText(this, "Pehle koi video play karein!", Toast.LENGTH_SHORT).show()
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

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_STORAGE && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
            Toast.makeText(this, "Permission granted. Try downloading again.", Toast.LENGTH_SHORT).show()
    }
      
    // NAYA QUALITY POPUP FUNCTION
    private fun showQualityPopup(videoUrl: String) {
        Toast.makeText(this, "Video quality check ho rahi hai... Please wait", Toast.LENGTH_LONG).show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val request = com.yausername.youtubedl_android.YoutubeDLRequest(videoUrl)
                val videoInfo = YoutubeDL.getInstance().getInfo(request)
                val formats = videoInfo.formats
                
                val formatList = ArrayList<String>()
                formats?.forEach { format ->
                    if (format.height > 0) {
                        val sizeMB = if (format.fileSize > 0) "${format.fileSize / (1024 * 1024)} MB" else "Unknown Size"
                        formatList.add("${format.height}p (${format.ext}) - $sizeMB")
                    }
                }
                
                val finalQualities = formatList.distinct().toTypedArray()

                launch(Dispatchers.Main) {
                    if (finalQualities.isNotEmpty()) {
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle("Select Video Quality")
                            .setItems(finalQualities) { _, which ->
                                val selected = finalQualities[which]
                                Toast.makeText(this@MainActivity, "Download Start: $selected", Toast.LENGTH_SHORT).show()
                                // Agle step mein hum yahan asal download lagayenge
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    } else {
                        Toast.makeText(this@MainActivity, "Koi quality nahi mili!", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
    
}
