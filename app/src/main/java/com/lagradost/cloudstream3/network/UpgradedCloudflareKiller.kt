package com.lagradost.cloudstream3.network

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

class UpgradedCloudflareKiller(context: Context) : Interceptor {

    // Always store applicationContext (even if an Activity context is passed) — no memory leak.
    private val appContext: Context = context.applicationContext

    companion object {
        private val savedCookies = mutableMapOf<String, String>()
        private var webViewUserAgent: String? = null

        // One solve at a time per host; other requests wait, then reuse the clearance.
        private val hostLocks = ConcurrentHashMap<String, Mutex>()
        // After a failed solve, fail fast for a while instead of re-solving on every request.
        private val failedUntil = ConcurrentHashMap<String, Long>()
        private const val FAIL_COOLDOWN_MS = 45_000L
        private const val TAG = "CFKiller"

        fun hostLock(host: String): Mutex = hostLocks.getOrPut(host) { Mutex() }

        fun parseCookieMap(cookie: String): Map<String, String> {
            return cookie.split(";").mapNotNull {
                val parts = it.split("=", limit = 2)
                if (parts.size == 2) {
                    val k = parts[0].trim()
                    val v = parts[1].trim()
                    if (k.isNotBlank()) k to v else null
                } else null
            }.toMap()
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        val originalHost = originalRequest.url.host

        val requestToUse = attachSavedClearance(originalRequest, originalHost)
        val response = chain.proceed(requestToUse)

        if (!isCloudflareChallenge(response)) {
            return response
        }

        // The challenge often comes from a DIFFERENT host than the one that was
        // requested: redirect chains (e.g. dl.fastdlserver.site -> gdflix.dev ->
        // new6.gdflix.io) end on the walled host. response.request is the final
        // request after all redirects, so the WebView solve and the cf_clearance
        // cookie polling MUST target that final URL/host. Solving the original
        // URL polls the wrong domain's cookies and can never succeed.
        val finalRequest = response.request
        val finalUrl = finalRequest.url
        val finalHost = finalUrl.host
        Log.d(TAG, "Challenge detected: requested=$originalHost final=$finalHost code=${response.code}")

        // In post-failure cooldown: return the original response fast, do not re-solve.
        val now = System.currentTimeMillis()
        if ((failedUntil[finalHost] ?: 0L) > now || (failedUntil[originalHost] ?: 0L) > now) {
            Log.d(TAG, "In fail-cooldown for $finalHost, returning response unsolved")
            return response
        }

        response.close()
        val solved = runBlocking {
            hostLock(finalHost).withLock {
                // A concurrent request may already have solved this host.
                if (savedCookies[finalHost]?.contains("cf_clearance=") == true) {
                    Log.d(TAG, "Clearance already saved for $finalHost, reusing")
                    true
                } else {
                    solveWafInWebView(finalUrl.toString(), finalHost)
                }
            }
        }
        return if (solved) {
            val retry = chain.proceed(attachSavedClearance(finalRequest, finalHost))
            if (isCloudflareChallenge(retry)) {
                // The clearance is being rejected — drop it so the next request
                // solves fresh instead of reusing a poisoned cookie forever.
                Log.w(TAG, "Retry still challenged for $finalHost, dropping saved clearance")
                savedCookies.remove(finalHost)
                val until = System.currentTimeMillis() + FAIL_COOLDOWN_MS
                failedUntil[finalHost] = until
                failedUntil[originalHost] = until
            }
            retry
        } else {
            val until = System.currentTimeMillis() + FAIL_COOLDOWN_MS
            failedUntil[finalHost] = until
            failedUntil[originalHost] = until
            // The original response was closed; fetch a fresh one for the caller.
            chain.proceed(requestToUse)
        }
    }

    private fun isCloudflareChallenge(response: Response): Boolean {
        val server = response.header("Server")?.contains("cloudflare", ignoreCase = true) == true
        if ((response.code == 403 || response.code == 503) && server) return true
        // Some hosts serve the challenge with 200/other codes: peek at the HTML body.
        val contentType = response.header("Content-Type") ?: return false
        if (!contentType.contains("text/html", ignoreCase = true)) return false
        return try {
            val peek = response.peekBody(8192).string()
            peek.contains("Just a moment", ignoreCase = true) ||
                peek.contains("challenges.cloudflare.com") ||
                peek.contains("_cf_chl") ||
                peek.contains("cf-turnstile")
        } catch (e: Exception) {
            false
        }
    }

    private fun attachSavedClearance(request: Request, host: String): Request {
        val saved = savedCookies[host] ?: return request
        val merged = LinkedHashMap<String, String>()
        request.header("Cookie")?.let { merged.putAll(parseCookieMap(it)) }
        merged.putAll(parseCookieMap(saved))
        val builder = request.newBuilder()
        webViewUserAgent?.let { builder.header("User-Agent", it) }
        builder.header("Cookie", merged.entries.joinToString("; ") { "${it.key}=${it.value}" })
        return builder.build()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun solveWafInWebView(url: String, host: String): Boolean =
        withContext(Dispatchers.Main) {
            val deferred = CompletableDeferred<Boolean>()
            val handler = Handler(Looper.getMainLooper())
            val webView = WebView(appContext)

            // Give the background WebView a real phone screen size (1080x1920),
            // otherwise Cloudflare sees a 0x0 viewport and never passes the challenge.
            webView.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
            )
            webView.layout(0, 0, 1080, 1920)
            // A freshly created WebView starts paused: JS timers (which the
            // challenge relies on) may not run until it is resumed.
            webView.onResume()
            webView.resumeTimers()
            Log.d(TAG, "Solve start: host=$host url=$url")

            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)
            cookieManager.setAcceptThirdPartyCookies(webView, true)

            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                cacheMode = WebSettings.LOAD_DEFAULT
                // Do NOT modify the User-Agent: Cloudflare binds cf_clearance to the
                // exact UA that solved the challenge, and upstream CloudStream warns
                // that setting a custom UA makes Cloudflare break. Keep the raw
                // WebView UA and reuse it verbatim on the retried request.
                webViewUserAgent = userAgentString
            }

            // Prevent JS alert/confirm/prompt crashes with applicationContext (no window).
            webView.webChromeClient = object : WebChromeClient() {
                override fun onJsAlert(v: WebView?, u: String?, m: String?, r: JsResult?): Boolean {
                    r?.confirm()
                    return true
                }
                override fun onJsConfirm(v: WebView?, u: String?, m: String?, r: JsResult?): Boolean {
                    r?.confirm()
                    return true
                }
                override fun onJsPrompt(v: WebView?, u: String?, m: String?, d: String?, r: JsPromptResult?): Boolean {
                    r?.confirm()
                    return true
                }
            }

            val solveStartMs = System.currentTimeMillis()
            var pollCount = 0
            var pageFinished = false
            val checkAndClickLoop = object : Runnable {
                override fun run() {
                    if (deferred.isCompleted) return
                    pollCount++

                    val currentCookies = cookieManager.getCookie(url).orEmpty()
                    if (currentCookies.contains("cf_clearance=")) {
                        savedCookies[host] = currentCookies
                        cookieManager.flush()
                        Log.d(TAG, "Solve SUCCESS (cf_clearance) for $host in ${System.currentTimeMillis() - solveStartMs}ms")
                        deferred.complete(true)
                        return
                    }

                    // AUTOMATIC success detection: if a page finished loading and
                    // it is no longer a challenge page, the wall is passed even
                    // when the site did not set a cf_clearance cookie. Keep
                    // whatever cookies the host set and retry with them.
                    if (pageFinished) {
                        val jsChallengeCheck = """
                            (function() {
                                var t = document.title || '';
                                var b = document.body ? document.body.innerHTML : '';
                                var chal = t.indexOf('Just a moment') >= 0 ||
                                    t.indexOf('Attention Required') >= 0 ||
                                    b.indexOf('cf-turnstile') >= 0 ||
                                    b.indexOf('challenges.cloudflare.com') >= 0 ||
                                    b.indexOf('_cf_chl') >= 0;
                                return JSON.stringify({ chal: chal });
                            })();
                        """.trimIndent()
                        webView.evaluateJavascript(jsChallengeCheck) { result ->
                            try {
                                val clean = result?.removeSurrounding("\"")?.replace("\\\"", "\"")
                                if (!clean.isNullOrEmpty()) {
                                    val json = JSONObject(clean)
                                    if (!json.optBoolean("chal", true) && !deferred.isCompleted) {
                                        savedCookies[host] = cookieManager.getCookie(url).orEmpty()
                                        cookieManager.flush()
                                        Log.d(TAG, "Solve SUCCESS (challenge page gone) for $host in ${System.currentTimeMillis() - solveStartMs}ms")
                                        deferred.complete(true)
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }

                    if (pollCount % 7 == 0) {
                        Log.d(TAG, "Solve polling $host: ${System.currentTimeMillis() - solveStartMs}ms, page=${webView.url}")
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
                                    val x = (json.getDouble("x").toFloat() + Random.nextInt(-3, 4)) * webView.scale
                                    val y = (json.getDouble("y").toFloat() + Random.nextInt(-3, 4)) * webView.scale
                                    simulateRealisticFingerTap(webView, x, y)
                                }
                            }
                        } catch (_: Exception) {}
                    }

                    handler.postDelayed(this, 1500)
                }
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    super.onPageFinished(view, loadedUrl)
                    pageFinished = true
                    Log.d(TAG, "Solve page finished for $host: $loadedUrl")
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    // A hard main-frame load error is a definitive event: end
                    // the wait instead of polling a dead page forever.
                    if (request?.isForMainFrame == true && !deferred.isCompleted) {
                        Log.w(TAG, "Solve page ERROR for $host: ${error?.description} (code ${error?.errorCode})")
                        deferred.complete(false)
                    }
                }
            }

            webView.loadUrl(url)
            // Start polling immediately — do not wait for onPageFinished, the
            // challenge may complete (or stall) before it ever fires.
            handler.post(checkAndClickLoop)

            // AUTOMATIC: no fixed timeout on the solve. The wait ends only when
            // the challenge is actually solved (cf_clearance set / challenge
            // page replaced by real content) or the page load fails outright —
            // never because a guessed number of seconds ran out.
            val success = deferred.await()
            Log.d(TAG, "Solve finished for $host: success=$success after ${System.currentTimeMillis() - solveStartMs}ms")

            handler.removeCallbacksAndMessages(null)
            webView.stopLoading()
            webView.clearHistory()
            webView.removeAllViews()
            webView.destroy()
            return@withContext success
        }

    // Realistic human finger touch (TOOL_TYPE_FINGER + pressure + micro-movement).
    private fun simulateRealisticFingerTap(view: WebView, x: Float, y: Float) {
        val downTime = SystemClock.uptimeMillis()
        val moveDelay = Random.nextLong(30, 60)
        val upDelay = moveDelay + Random.nextLong(50, 90)

        val properties = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        })

        val downCoords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = Random.nextDouble(0.45, 0.75).toFloat()
            size = Random.nextDouble(0.15, 0.30).toFloat()
        })

        val downEvent = MotionEvent.obtain(
            downTime, downTime, MotionEvent.ACTION_DOWN,
            1, properties, downCoords, 0, 0, 1f, 1f, 0, 0, 0x1002, 0
        )
        view.dispatchTouchEvent(downEvent)
        downEvent.recycle()

        view.postDelayed({
            val moveCoords = arrayOf(MotionEvent.PointerCoords().apply {
                this.x = x + Random.nextDouble(-0.8, 0.8).toFloat()
                this.y = y + Random.nextDouble(-0.8, 0.8).toFloat()
                pressure = Random.nextDouble(0.50, 0.80).toFloat()
                size = Random.nextDouble(0.18, 0.32).toFloat()
            })
            val moveEvent = MotionEvent.obtain(
                downTime, downTime + moveDelay, MotionEvent.ACTION_MOVE,
                1, properties, moveCoords, 0, 0, 1f, 1f, 0, 0, 0x1002, 0
            )
            view.dispatchTouchEvent(moveEvent)
            moveEvent.recycle()
        }, moveDelay)

        view.postDelayed({
            val upCoords = arrayOf(MotionEvent.PointerCoords().apply {
                this.x = x + Random.nextDouble(-0.8, 0.8).toFloat()
                this.y = y + Random.nextDouble(-0.8, 0.8).toFloat()
                pressure = 0f
                size = 0f
            })
            val upEvent = MotionEvent.obtain(
                downTime, downTime + upDelay, MotionEvent.ACTION_UP,
                1, properties, upCoords, 0, 0, 1f, 1f, 0, 0, 0x1002, 0
            )
            view.dispatchTouchEvent(upEvent)
            upEvent.recycle()
        }, upDelay)
    }
}
