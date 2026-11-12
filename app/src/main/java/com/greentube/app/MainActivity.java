package com.greentube.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PictureInPictureParams;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Rational;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageButton;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.webkit.ScriptHandler;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.regex.Pattern;

public class MainActivity extends Activity {

    // User-Agent de Chrome mobile (sem "wv"): o Google bloqueia login em WebView "puro".
    private static final String UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";
    private static final String HOME = "https://m.youtube.com/";
    private static final Pattern VID = Pattern.compile("^[A-Za-z0-9_-]{11}$");

    private static final String[] BLOCK_HOSTS = {
            "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google."
    };
    private static final String[] BLOCK_PATHS = {
            "/pagead/", "/api/stats/ads", "/get_midroll_", "/ptracking"
    };

    static MainActivity instance;

    private WebView web;
    private SwipeRefreshLayout swipe;
    private FrameLayout root;
    private ImageButton gear;
    private View customView;
    private WebChromeClient.CustomViewCallback customCb;
    private SharedPreferences prefs;
    private ScriptHandler handler;
    private String injectJs = "";
    private String script = "";
    private volatile boolean playing = false;
    private boolean pendingAuto = false;   // fomos nós que pedimos a tela cheia automática
    private boolean autoEntered = false;   // a tela cheia atual foi aberta automaticamente

    // Acha o botão de tela cheia do player e devolve o centro dele (px CSS), ou vazio.
    private static final String FS_JS = "(function(){window.scrollTo(0,0);"
            + "var b=document.querySelector('.fullscreen-icon,.ytp-fullscreen-button,"
            + "button[aria-label*=\"ull\" i],button[aria-label*=\"heia\" i]');"
            + "if(!b)return '';var r=b.getBoundingClientRect();"
            + "if(r.width<=0||r.height<=0||r.bottom>window.innerHeight||r.right>window.innerWidth)return '';"
            + "return Math.round(r.left+r.width/2)+','+Math.round(r.top+r.height/2);})()";

    /** Executa JS na página a partir de qualquer thread (usado pela notificação de mídia). */
    static void js(final String code) {
        final MainActivity a = instance;
        if (a == null) return;
        a.runOnUiThread(() -> a.web.evaluateJavascript(code, null));
    }

    private static boolean isAd(Uri u) {
        String h = u.getHost() == null ? "" : u.getHost();
        String p = u.getPath() == null ? "" : u.getPath();
        for (String b : BLOCK_HOSTS) if (h.contains(b)) return true;
        if (h.endsWith("youtube.com")) for (String b : BLOCK_PATHS) if (p.contains(b)) return true;
        return false;
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (Exception e) { return ""; }
    }

    // ---------- configurações ----------
    private boolean on(String k) { return prefs.getBoolean(k, true); }

    private String cfgJs() {
        return "window.__gtCfg={ads:" + on("ads") + ",shorts:" + on("shorts") + ",sb:" + on("sb")
                + ",dis:" + on("dis") + ",speed:" + prefs.getFloat("speed", 1f) + "};";
    }

    /** (Re)registra o script de início de documento com as configurações atuais. */
    private void registerScript() {
        script = cfgJs() + injectJs;
        if (handler != null) { handler.remove(); handler = null; }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            handler = WebViewCompat.addDocumentStartJavaScript(web, script,
                    new HashSet<>(Arrays.asList("https://m.youtube.com", "https://www.youtube.com")));
        }
    }

    private void showSettings() {
        final String[] names = {"Bloquear anúncios", "Esconder Shorts", "SponsorBlock (pular patrocínios)",
                "Contador de dislikes", "Picture-in-picture ao sair do app",
                "Tela cheia automática ao girar o celular"};
        final String[] keys = {"ads", "shorts", "sb", "dis", "pip", "autofs"};
        final boolean[] st = new boolean[keys.length];
        for (int i = 0; i < keys.length; i++) st[i] = on(keys[i]);
        new AlertDialog.Builder(this)
                .setTitle("DevTube")
                .setMultiChoiceItems(names, st, (d, i, c) -> st[i] = c)
                .setPositiveButton("Salvar", (d, w) -> {
                    SharedPreferences.Editor e = prefs.edit();
                    for (int i = 0; i < keys.length; i++) e.putBoolean(keys[i], st[i]);
                    e.apply();
                    registerScript();
                    web.reload();
                })
                .setNeutralButton("Velocidade", (d, w) -> showSpeed())
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void showSpeed() {
        final String[] names = {"0.75x", "1x (normal)", "1.25x", "1.5x", "2x"};
        final float[] vals = {0.75f, 1f, 1.25f, 1.5f, 2f};
        float cur = prefs.getFloat("speed", 1f);
        int sel = 1;
        for (int i = 0; i < vals.length; i++) if (vals[i] == cur) sel = i;
        new AlertDialog.Builder(this)
                .setTitle("Velocidade padrão")
                .setSingleChoiceItems(names, sel, (d, i) -> {
                    prefs.edit().putFloat("speed", vals[i]).apply();
                    registerScript();
                    js("var v=document.querySelector('video');if(v)v.playbackRate=" + vals[i] + ";");
                    d.dismiss();
                })
                .show();
    }

    // ---------- chamadas nativas feitas pela página (rede fora do CSP do YouTube) ----------
    private static String http(String u) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(6000); c.setReadTimeout(6000);
            c.setRequestProperty("User-Agent", "DevTube/1.2");
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096]; int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        } catch (Exception e) { return null; }
    }

    private static String sha256Prefix(String s) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2; i++) sb.append(String.format("%02x", d[i])); // 4 caracteres hex
        return sb.toString();
    }

    private class Bridge {
        @JavascriptInterface public void meta(final String title, final boolean pl) {
            playing = pl;
            runOnUiThread(() -> {
                if (KeepAliveService.instance != null)
                    KeepAliveService.instance.update(title == null ? "DevTube" : title, pl);
            });
        }

        @JavascriptInterface public void sponsor(final String id) {
            if (id == null || !VID.matcher(id).matches()) return;
            new Thread(() -> {
                try {
                    String url = "https://sponsor.ajay.app/api/skipSegments/" + sha256Prefix(id)
                            + "?categories=" + URLEncoder.encode("[\"sponsor\",\"selfpromo\",\"interaction\",\"intro\",\"outro\"]", "UTF-8")
                            + "&actionTypes=" + URLEncoder.encode("[\"skip\"]", "UTF-8");
                    String body = http(url);
                    JSONArray out = new JSONArray();
                    if (body != null) {
                        JSONArray arr = new JSONArray(body);
                        for (int i = 0; i < arr.length(); i++) {
                            JSONObject o = arr.getJSONObject(i);
                            if (!id.equals(o.optString("videoID"))) continue;
                            JSONArray sg = o.getJSONArray("segments");
                            for (int j = 0; j < sg.length(); j++) {
                                JSONArray se = sg.getJSONObject(j).getJSONArray("segment");
                                out.put(new JSONArray().put(se.getDouble(0)).put(se.getDouble(1)));
                            }
                        }
                    }
                    js("window.__gtSB&&window.__gtSB(" + JSONObject.quote(id) + "," + out + ")");
                } catch (Exception ignored) {}
            }).start();
        }

        @JavascriptInterface public void dislikes(final String id) {
            if (id == null || !VID.matcher(id).matches()) return;
            new Thread(() -> {
                try {
                    String body = http("https://returnyoutubedislikeapi.com/votes?videoId=" + id);
                    if (body == null) return;
                    JSONObject o = new JSONObject(body);
                    js("window.__gtRYD&&window.__gtRYD(" + JSONObject.quote(id) + "," + o.optLong("likes")
                            + "," + o.optLong("dislikes") + ")");
                } catch (Exception ignored) {}
            }).start();
        }
    }

    // ---------- ciclo de vida ----------
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        instance = this;
        prefs = getSharedPreferences("gt", MODE_PRIVATE);
        injectJs = readAsset("inject.js");

        root = new FrameLayout(this);
        web = new WebView(this);
        swipe = new SwipeRefreshLayout(this);
        swipe.addView(web, new ViewGroup.LayoutParams(-1, -1));
        swipe.setColorSchemeColors(Color.RED);
        swipe.setOnRefreshListener(() -> web.reload());
        // só puxa pra atualizar no topo e fora da página de vídeo (não atrapalha o gesto do player)
        swipe.setOnChildScrollUpCallback((p, c) -> web.getScrollY() > 0
                || (web.getUrl() != null && web.getUrl().contains("/watch")));
        root.addView(swipe, new FrameLayout.LayoutParams(-1, -1));

        float d = getResources().getDisplayMetrics().density;
        int px = (int) (38 * d);
        gear = new ImageButton(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x99000000);
        gear.setBackground(bg);
        gear.setImageResource(android.R.drawable.ic_menu_preferences);
        gear.setColorFilter(Color.WHITE);
        gear.setAlpha(0.55f);
        gear.setOnClickListener(v -> showSettings());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(px, px, Gravity.END | Gravity.CENTER_VERTICAL);
        lp.setMargins(0, 0, (int) (4 * d), 0);
        root.addView(gear, lp);
        setContentView(root);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(UA);
        s.setLoadWithOverviewMode(true);
        s.setSupportMultipleWindows(false);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(web, true); // necessário pro login Google manter a sessão

        web.addJavascriptInterface(new Bridge(), "GT");
        registerScript();

        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                if (on("ads") && isAd(r.getUrl())) {
                    return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
                }
                return null;
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                String h = u.getHost() == null ? "" : u.getHost();
                if (h.endsWith("youtube.com") || h.endsWith("google.com") || h.endsWith("gstatic.com")
                        || h.endsWith("googleusercontent.com") || h.endsWith("googleapis.com")
                        || h.equals("youtu.be")) {
                    return false;
                }
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
                return true;
            }
            // Reserva para WebViews sem suporte a script de início de documento.
            @Override public void onPageStarted(WebView v, String url, Bitmap f) { v.evaluateJavascript(script, null); }
            @Override public void onPageFinished(WebView v, String url) {
                v.evaluateJavascript(script, null);
                swipe.setRefreshing(false);
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onShowCustomView(View view, CustomViewCallback cb) {
                if (customView != null) { cb.onCustomViewHidden(); return; }
                customView = view; customCb = cb;
                autoEntered = pendingAuto; pendingAuto = false;
                swipe.setVisibility(View.GONE);
                gear.setVisibility(View.GONE);
                root.addView(view, new FrameLayout.LayoutParams(-1, -1));
                goImmersive(true);
            }
            @Override public void onHideCustomView() { exitFullscreen(); }
        });

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        try { startForegroundService(new Intent(this, KeepAliveService.class)); } catch (Exception ignored) {}

        Uri in = getIntent().getData();
        web.loadUrl(in != null ? in.toString() : HOME);
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        if (i.getData() != null) web.loadUrl(i.getData().toString());
    }

    // ---------- Picture-in-picture ----------
    @Override protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        boolean watching = customView != null || (web.getUrl() != null && web.getUrl().contains("/watch"));
        if (on("pip") && playing && watching) {
            try {
                enterPictureInPictureMode(new PictureInPictureParams.Builder()
                        .setAspectRatio(new Rational(16, 9)).build());
            } catch (Exception ignored) {}
        }
    }

    @Override public void onPictureInPictureModeChanged(boolean inPip, Configuration c) {
        super.onPictureInPictureModeChanged(inPip, c);
        gear.setVisibility(inPip || customView != null ? View.GONE : View.VISIBLE);
        swipe.setEnabled(!inPip);
        js("window.__gtPip&&window.__gtPip(" + inPip + ")");
    }

    // ---------- tela cheia automática ao girar ----------
    private boolean watching() {
        return web.getUrl() != null && web.getUrl().contains("/watch");
    }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        if (isInPictureInPictureMode() || !on("autofs")) return;
        if (c.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            if (customView == null && watching()) web.postDelayed(() -> autoFullscreen(1), 600);
        } else if (c.orientation == Configuration.ORIENTATION_PORTRAIT) {
            pendingAuto = false;
            if (customView != null && autoEntered) exitFullscreen(); // voltou ao retrato: sai da tela cheia
        }
    }

    /** Toca de verdade no botão de tela cheia (o navegador só aceita tela cheia após um toque real). */
    private void autoFullscreen(final int attempt) {
        if (customView != null || !watching() || isInPictureInPictureMode()) return;
        if (getResources().getConfiguration().orientation != Configuration.ORIENTATION_LANDSCAPE) return;
        pendingAuto = true;
        web.evaluateJavascript(FS_JS, v -> {
            String r = v == null ? "" : v.replace("\"", "");
            if (r.contains(",")) {
                try {
                    String[] p = r.split(",");
                    float d = getResources().getDisplayMetrics().density;
                    tap(Float.parseFloat(p[0]) * d, Float.parseFloat(p[1]) * d);
                } catch (Exception ignored) {}
            }
            if (attempt < 3) web.postDelayed(() -> { if (customView == null) autoFullscreen(attempt + 1); }, 900);
            else pendingAuto = false;
        });
    }

    private void tap(final float x, final float y) {
        final long t = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        web.dispatchTouchEvent(down);
        down.recycle();
        web.postDelayed(() -> {
            MotionEvent up = MotionEvent.obtain(t, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0);
            up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            web.dispatchTouchEvent(up);
            up.recycle();
        }, 60);
    }

    // ---------- tela cheia ----------
    private void exitFullscreen() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        autoEntered = false;
        if (customCb != null) customCb.onCustomViewHidden();
        swipe.setVisibility(View.VISIBLE);
        gear.setVisibility(View.VISIBLE);
        goImmersive(false);
    }

    private void goImmersive(boolean on) {
        getWindow().getDecorView().setSystemUiVisibility(on
            ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
              | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            : View.SYSTEM_UI_FLAG_VISIBLE);
    }

    @Override public void onBackPressed() {
        if (customView != null) exitFullscreen();
        else if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    // Propositalmente NÃO chamamos web.onPause(): assim o áudio segue tocando em segundo plano.
    @Override protected void onResume() { super.onResume(); web.onResume(); }

    @Override protected void onDestroy() {
        instance = null;
        stopService(new Intent(this, KeepAliveService.class));
        CookieManager.getInstance().flush();
        ((ViewGroup) web.getParent()).removeView(web);
        web.destroy();
        super.onDestroy();
    }
}
