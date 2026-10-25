package ua.potik.speed

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File

class MainActivity : Activity() {

    private lateinit var web: WebView
    private lateinit var store: Store
    private val main = Handler(Looper.getMainLooper())
    private var ready = false
    private var test: SpeedTest? = null
    private var testThread: Thread? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        web = WebView(this)
        web.setBackgroundColor(Color.parseColor(if (isDark()) "#1E2827" else "#D8E1DF"))
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.isVerticalScrollBarEnabled = false
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                ready = true
                if (BuildConfig.DEBUG && intent.getBooleanExtra("autoTest", false)) {
                    main.postDelayed({ web.evaluateJavascript("document.getElementById('puck').click()", null) }, 1500)
                }
            }
        }
        web.addJavascriptInterface(Bridge(), "Android")
        setContentView(web)
        setBars(isDark())
        web.loadUrl("file:///android_asset/www/index.html")
    }

    override fun onStart() {
        super.onStart()
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = pushNet()
            override fun onLost(network: Network) = pushNet()
            override fun onAvailable(network: Network) = pushNet()
        }
        runCatching { cm.registerDefaultNetworkCallback(cb) }.onSuccess { netCallback = cb }
        pushNet()
    }

    override fun onStop() {
        netCallback?.let { cb -> runCatching { (getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(cb) } }
        netCallback = null
        super.onStop()
    }

    override fun onDestroy() {
        test?.stop()
        super.onDestroy()
    }

    private var netPending = false
    private fun pushNet() {
        if (netPending) return
        netPending = true
        main.postDelayed({
            netPending = false
            send("net", NetInfo.current(this))
        }, 300)
    }

    private fun send(type: String, data: JSONObject) {
        val js = "window.Potik&&Potik.onEvent(" + JSONObject.quote(type) + "," + data.toString() + ")"
        main.post { if (ready) web.evaluateJavascript(js, null) }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("window.Potik?Potik.back():false") { r ->
            if (r != "true") {
                @Suppress("DEPRECATION")
                super.onBackPressed()
            }
        }
    }

    private fun isDark(): Boolean = when (store.theme) {
        "dark" -> true
        "light" -> false
        else -> (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    @Suppress("DEPRECATION")
    private fun setBars(dark: Boolean) {
        val color = Color.parseColor(if (dark) "#1E2827" else "#D8E1DF")
        window.statusBarColor = color
        window.navigationBarColor = color
        web.setBackgroundColor(color)
        if (Build.VERSION.SDK_INT >= 30) {
            val flags = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (dark) 0 else flags, flags)
        } else {
            var f = window.decorView.systemUiVisibility
            f = if (dark) f and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv() else f or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= 26) {
                f = if (dark) f and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv() else f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
            window.decorView.systemUiVisibility = f
        }
    }

    // ---------- вимірювання ----------

    private fun startTest() {
        if (testThread?.isAlive == true) return
        val net = NetInfo.current(this)
        if (net.optString("type") == "none") {
            send("error", JSONObject().put("message", "Немає з'єднання з інтернетом. Увімкніть Wi‑Fi або мобільні дані."))
            return
        }
        val economy = net.optString("type") == "cell" && store.mobileMode == "eco"
        val t = SpeedTest(economy) { type, data -> send(type, data) }
        test = t
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        testThread = Thread({
            try {
                val r = t.run()
                val o = JSONObject()
                    .put("id", System.currentTimeMillis().toString())
                    .put("time", System.currentTimeMillis())
                    .put("ping", round1(r.ping))
                    .put("jitter", round1(r.jitter))
                    .put("down", round2(r.down))
                    .put("up", round2(r.up))
                    .put("loadedDown", r.loadedDown?.let { round1(it) } ?: JSONObject.NULL)
                    .put("loadedUp", r.loadedUp?.let { round1(it) } ?: JSONObject.NULL)
                    .put("bytes", r.bytes)
                    .put("economy", economy)
                    .put("net", net)
                    .put("meta", r.meta)
                store.add(o)
                send("result", o)
                if (BuildConfig.DEBUG) runCatching { File(getExternalFilesDir(null), "last_result.json").writeText(o.toString(2)) }
            } catch (e: SpeedTest.Stopped) {
                send("stopped", JSONObject())
            } catch (e: SpeedTest.TestError) {
                send("error", JSONObject().put("message", e.message))
                if (BuildConfig.DEBUG) runCatching { File(getExternalFilesDir(null), "last_result.json").writeText("ERROR " + e.message) }
            } catch (e: Throwable) {
                send("error", JSONObject().put("message", "Вимірювання не вдалося: " + (e.message ?: e.javaClass.simpleName)))
                if (BuildConfig.DEBUG) runCatching { File(getExternalFilesDir(null), "last_result.json").writeText("ERROR " + e) }
            } finally {
                main.post { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                test = null
            }
        }, "potik-test")
        testThread!!.start()
    }

    private fun round1(v: Double) = Math.round(v * 10) / 10.0
    private fun round2(v: Double) = Math.round(v * 100) / 100.0

    // ---------- міст ----------

    inner class Bridge {
        @JavascriptInterface
        fun hello(): Boolean = true

        @JavascriptInterface
        fun getState(): String = JSONObject()
            .put("prefs", store.prefsJson())
            .put("net", NetInfo.current(this@MainActivity))
            .put("last", store.last() ?: JSONObject.NULL)
            .put("version", BuildConfig.VERSION_NAME)
            .toString()

        @JavascriptInterface
        fun start() = main.post { startTest() }

        @JavascriptInterface
        fun stop() = main.post { test?.stop() }

        @JavascriptInterface
        fun getHistory(): String = store.history().toString()

        @JavascriptInterface
        fun deleteResult(id: String) = store.delete(id)

        @JavascriptInterface
        fun clearHistory() = store.clear()

        @JavascriptInterface
        fun setPref(key: String, value: String) {
            when (key) {
                "units" -> store.units = value
                "theme" -> { store.theme = value }
                "mobileMode" -> store.mobileMode = value
                "haptics" -> store.haptics = value == "true"
            }
        }

        @JavascriptInterface
        fun setBars(dark: Boolean) = main.post { this@MainActivity.setBars(dark) }

        @JavascriptInterface
        fun haptic(kind: String) = main.post {
            if (!store.haptics) return@post
            val c = when (kind) {
                "done" -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS
                "press" -> HapticFeedbackConstants.VIRTUAL_KEY
                else -> if (Build.VERSION.SDK_INT >= 27) HapticFeedbackConstants.KEYBOARD_PRESS else HapticFeedbackConstants.VIRTUAL_KEY
            }
            web.performHapticFeedback(c)
        }

        @JavascriptInterface
        fun share(pngBase64: String, text: String) {
            runCatching {
                val dir = File(cacheDir, "share").apply { mkdirs() }
                val f = File(dir, "potik.png")
                f.writeBytes(Base64.decode(pngBase64, Base64.DEFAULT))
                val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", f)
                val i = Intent(Intent.ACTION_SEND)
                    .setType("image/png")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .putExtra(Intent.EXTRA_TEXT, text)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                main.post { startActivity(Intent.createChooser(i, "Поділитися результатом")) }
            }.onFailure {
                val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                main.post { startActivity(Intent.createChooser(i, "Поділитися результатом")) }
            }
        }
    }
}
