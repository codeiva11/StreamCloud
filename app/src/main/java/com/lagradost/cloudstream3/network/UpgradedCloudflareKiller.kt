package com.lagradost.cloudstream3.network

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import kotlin.random.Random

class UpgradedCloudflareKiller(private val context: Context) : Interceptor {

    companion object {
        private val savedCookies = mutableMapOf<String, String>()
        private var cleanUserAgent: String? = null
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val host = originalRequest.url.host

        val requestToUse = attachSavedClearance(originalRequest, host)
        val response = chain.proceed(requestToUse)

        val isCloudflareBlock = (response.code == 403 || response.code == 503) &&
                response.header("Server")?.contains("cloudflare", ignoreCase = true) == true

        if (isCloudflareBlock) {
            response.close()
            val solved = runBlocking {
                solveWafInWebView(originalRequest.url.toString(), host)
            }
            if (solved) {
                val retriedRequest = attachSavedClearance(originalRequest, host)
                return chain.proceed(retriedRequest)
            }
        }

        return response
    }

    private fun attachSavedClearance(request: Request, host: String): Request {
        val cookie = savedCookies[host] ?: return request
        val builder = request.newBuilder()
        cleanUserAgent?.let { builder.header("User-Agent", it) }
        builder.header("Cookie", cookie)
        return builder.build()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun solveWafInWebView(url: String, host: String): Boolean =
        withContext(Dispatchers.Main) {
            val deferred = CompletableDeferred<Boolean>()
            val handler = Handler(Looper.getMainLooper())
            val webView = WebView(context)

            webView.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
            )
            webView.layout(0, 0, 1080, 1920)

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(webView, true)

            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT

                userAgentString = userAgentString
                    .replace("; wv", "")
                    .replace(Regex("Version/\\d+\\.\\d+\\s+"), "")
                cleanUserAgent = userAgentString
            }

            val checkAndClickLoop = object : Runnable {
                override fun run() {
                    if (deferred.isCompleted) return

                    val currentCookies = cookieManager.getCookie(url).orEmpty()
                    if (currentCookies.contains("cf_clearance=")) {
                        savedCookies[host] = currentCookies
                        deferred.complete(true)
                        return
                    }

                    val jsFindBox = """
                        (function() {
                            var input = document.querySelector('[name="cf-turnstile-response"]');
                            var box = input ? input.parentElement : null;
                            if (!box || box.getBoundingClientRect().width < 100) {
                                var divs = document.querySelectorAll('div');
                                for (var i = 0; i < divs.length; i++) {
                                    var r = divs[i].getBoundingClientRect();
                                    if (r.width >= 280 && r.width <= 320 && r.height >= 60 && r.height <= 75) {
                                        box = divs[i];
                                        break;
                                    }
                                }
                            }
                            if (box) {
                                var rect = box.getBoundingClientRect();
                                return JSON.stringify({ found: true, x: rect.left + 30, y: rect.top + (rect.height / 2) });
                            }
                            return JSON.stringify({ found: false });
                        })();
                    """.trimIndent()

                    webView.evaluateJavascript(jsFindBox) { result ->
                        try {
                            val clean = result?.removeSurrounding("\"")?.replace("\\\"", "\"")
                            if (!clean.isNullOrEmpty()) {
                                val json = JSONObject(clean)
                                if (json.optBoolean("found")) {
                                    val x = json.getDouble("x").toFloat() * webView.scale
                                    val y = json.getDouble("y").toFloat() * webView.scale
                                    simulateTap(webView, x, y)
                                }
                            }
                        } catch (_: Exception) {}
                    }

                    handler.postDelayed(this, 2500)
                }
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    super.onPageFinished(view, loadedUrl)
                    handler.postDelayed(checkAndClickLoop, 2000)
                }
            }

            webView.loadUrl(url)

            val success = withTimeoutOrNull(30_000L) {
                deferred.await()
            } ?: false

            handler.removeCallbacksAndMessages(null)
            webView.stopLoading()
            webView.destroy()
            return@withContext success
        }

    private fun simulateTap(view: WebView, x: Float, y: Float) {
        val downTime = SystemClock.uptimeMillis()
        val duration = Random.nextLong(80, 150)
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
        val up = MotionEvent.obtain(downTime, downTime + duration, MotionEvent.ACTION_UP, x, y, 0)
        view.dispatchTouchEvent(down)
        view.postDelayed({
            view.dispatchTouchEvent(up)
            down.recycle()
            up.recycle()
        }, duration)
    }
}
