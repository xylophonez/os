package org.permaweb.andee

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * In-app browser for the operator configurator, served from Arweave.
 *
 * The page can hand a config straight back through [Bridge] instead of making the
 * operator download a file and re-pick it. The bridge is only injected for the
 * pinned configurator origin, and it can do exactly one thing: stage a config for
 * the next boot, which is the same operation the ornament's file picker performs.
 */
class ConfiguratorActivity : Activity() {
    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            // The page is static and self-contained; nothing needs file or
            // content access, so leave those off.
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean {
                    // Keep navigation inside the pinned app; anything else is
                    // simply refused rather than silently opened.
                    return !isConfiguratorUrl(request.url.toString())
                }
            }
            addJavascriptInterface(Bridge(), "AndeeBridge")
        }
        setContentView(web)
        hideSystemBars()
        web.loadUrl(CONFIGURATOR_URL)
    }

    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.systemBarsBehavior =
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            window.insetsController?.hide(WindowInsets.Type.systemBars())
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    private inner class Bridge {
        /** Stage a config for the next boot. Returns a message for the page to show. */
        @JavascriptInterface
        fun stageNextBootConfig(json: String): String {
            return try {
                AndeeBootConfigStore.stageNextBootConfig(this@ConfiguratorActivity, json)
                Log.i(TAG, "configurator staged a next-boot config")
                runOnUiThread { finish() }
                "staged"
            } catch (exc: Exception) {
                Log.e(TAG, "configurator config rejected", exc)
                "error: ${exc.message}"
            }
        }

        /** Lets the page show the apply button only when running inside the app. */
        @JavascriptInterface
        fun isAndee(): Boolean = true
    }

    companion object {
        private const val TAG = "ConfiguratorActivity"

        /** Arweave path manifest for the configurator app. */
        const val CONFIGURATOR_TX = "Pq23TZLGlQ4xY87ntJwG322AwtDfAdJPGHm1JqJsSQ0"
        const val CONFIGURATOR_URL = "https://arweave.net/$CONFIGURATOR_TX/"

        fun isConfiguratorUrl(url: String): Boolean =
            url.startsWith("https://arweave.net/$CONFIGURATOR_TX")
    }
}
