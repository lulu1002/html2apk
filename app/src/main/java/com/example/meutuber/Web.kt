package com.example.meutuber

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import java.io.ByteArrayInputStream

private const val CHROME_UA =
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
    "Chrome/126.0.0.0 Mobile Safari/537.36"

private val AD_PATHS = listOf("/pagead/", "/api/stats/ads", "/get_midroll_info", "ad_break", "/ptracking")

/** Roda dentro da página do m.youtube.com: esconde anúncios, pula os de vídeo e libera segundo plano. */
private const val INJECT = """
(function(){
  if (window.__mt) return; window.__mt = 1;
  var css = document.createElement('style');
  css.textContent = [
    '.ad-showing .ytp-ad-module','.ytp-ad-overlay-container','ytm-promoted-sparkles-web-renderer',
    'ytm-companion-ad-renderer','ytm-promoted-video-renderer','ytm-ad-slot-renderer',
    'ytm-in-feed-ad-layout-renderer','ytm-statement-banner-renderer','.masthead-ad-control',
    'ytm-brand-video-singleton-renderer','#player-ads','.ytp-ad-image-overlay'
  ].join(',') + '{display:none !important}';
  document.documentElement.appendChild(css);
  var sped = false;
  setInterval(function(){
    var v = document.querySelector('video');
    var ad = document.querySelector('.ad-showing');
    if (ad && v) {
      sped = true; v.muted = true; v.playbackRate = 16;
      if (isFinite(v.duration) && v.duration > 0) v.currentTime = v.duration;
    } else if (sped && v) { sped = false; v.muted = false; v.playbackRate = 1; }
    var s = document.querySelector('.ytp-ad-skip-button,.ytp-skip-ad-button,.ytp-ad-skip-button-modern');
    if (s) s.click();
  }, 300);
  try {
    Object.defineProperty(document, 'hidden', {get: function(){return false}});
    Object.defineProperty(document, 'visibilityState', {get: function(){return 'visible'}});
    document.addEventListener('visibilitychange', function(e){e.stopImmediatePropagation()}, true);
  } catch(e) {}
})();
"""

@SuppressLint("SetJavaScriptEnabled")
fun newYouTubeWebView(ctx: Context): WebView = WebView(ctx).apply {
    settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        mediaPlaybackRequiresUserGesture = false
        userAgentString = CHROME_UA
        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
    }
    CookieManager.getInstance().setAcceptCookie(true)
    webViewClient = object : WebViewClient() {
        override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? {
            val host = r.url.host ?: ""
            val u = r.url.toString()
            val blocked = HttpDownloader.BLOCKED_HOSTS.any { host == it || host.endsWith(".$it") } ||
                AD_PATHS.any { u.contains(it) }
            return if (blocked) WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0))) else null
        }
        override fun onPageStarted(v: WebView, url: String?, f: Bitmap?) { v.evaluateJavascript(INJECT, null) }
        override fun onPageFinished(v: WebView, url: String?) { v.evaluateJavascript(INJECT, null) }
    }
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
    loadUrl("https://m.youtube.com")
}

@Composable
fun YouTubeWeb(web: WebView, modifier: Modifier = Modifier.fillMaxSize()) {
    AndroidView(factory = { web }, modifier = modifier)
}
