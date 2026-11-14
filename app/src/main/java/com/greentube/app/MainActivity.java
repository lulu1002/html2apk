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
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Rational;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebBackForwardList;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;
import androidx.webkit.ScriptHandler;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

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
    private boolean pseudoFs = false;      // tela cheia automática (própria) ativa
    private WebView miniWeb;               // mini player: segunda WebView só com o vídeo
    private FrameLayout miniBox;
    private long miniGraceUntil = 0;
    private LinearLayout qcBar;            // atalhos rápidos (CC / qualidade / velocidade), nativos
    private TextView qcQ, qcS, qcL;
    private long sleepAt = 0;              // timer de sono
    private final android.os.Handler sleepH = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable sleepRun = () -> {
        sleepAt = 0;
        js("var v=document.querySelector('video');if(v){" + KeepAliveService.USER_PAUSE + "}");
        Toast.makeText(this, "Timer de sono: vídeo pausado", Toast.LENGTH_LONG).show();
    };
    private final Runnable qcHide = () -> { if (qcBar != null) qcBar.setVisibility(View.GONE); };

    /** Executa JS na página a partir de qualquer thread (usado pela notificação de mídia). */
    static void js(final String code) {
        final MainActivity a = instance;
        if (a == null) return;
        // se o mini player existe, os controles da notificação mandam nele
        a.runOnUiThread(() -> (a.miniWeb != null ? a.miniWeb : a.web).evaluateJavascript(code, null));
    }

    /** Executa JS numa WebView específica (ignora se ela já foi destruída). */
    static void jsOn(final WebView w, final String code) {
        final MainActivity a = instance;
        if (a == null || w == null) return;
        a.runOnUiThread(() -> { if (w == a.web || w == a.miniWeb) w.evaluateJavascript(code, null); });
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

    private String capJson() {
        return "{size:" + prefs.getInt("cap_size", 100) + ",color:" + JSONObject.quote(prefs.getString("cap_color", ""))
                + ",bg:" + JSONObject.quote(prefs.getString("cap_bg", ""))
                + ",edge:" + JSONObject.quote(prefs.getString("cap_edge", "")) + "}";
    }

    private String cfgJs(boolean mini) {
        return (mini ? "window.__gtMini=true;" : "")
                + "window.__gtCfg={ads:" + on("ads") + ",shorts:" + on("shorts") + ",sb:" + on("sb")
                + ",dis:" + (mini ? "false" : on("dis")) + ",notop:" + on("notop") + ",qc:" + on("qc") + ",dtap:" + on("dtap") + ",cap:" + capJson() + ",accent:" + JSONObject.quote(prefs.getString("accent", ""))
                + ",capPref:" + JSONObject.quote(prefs.getString("cap_pref", ""))
                + ",audPref:" + JSONObject.quote(prefs.getString("aud_pref", ""))
                + ",speed:" + prefs.getFloat("speed", 1f) + "};";
    }

    /** (Re)registra o script de início de documento com as configurações atuais. */
    private void registerScript() {
        script = cfgJs(false) + injectJs;
        if (handler != null) { handler.remove(); handler = null; }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            handler = WebViewCompat.addDocumentStartJavaScript(web, script,
                    new HashSet<>(Arrays.asList("https://m.youtube.com", "https://www.youtube.com")));
        }
    }

    private void showSettings() {
        final String[] names = {"Bloquear anúncios", "Esconder Shorts", "SponsorBlock (pular patrocínios)",
                "Contador de dislikes", "Picture-in-picture ao sair do app",
                "Tela cheia automática ao girar o celular",
                "Esconder barra do topo (logo e lupa) no vídeo", "Mini player dentro do app ao voltar do vídeo",
                "Atalhos rápidos no player (legendas, qualidade, velocidade)",
                "Toque duplo na lateral do vídeo pula 10s"};
        final String[] keys = {"ads", "shorts", "sb", "dis", "pip", "autofs", "notop", "mini", "qc", "dtap"};
        final boolean[] st = new boolean[keys.length];
        for (int i = 0; i < keys.length; i++) st[i] = on(keys[i]);
        new AlertDialog.Builder(this)
                .setTitle("DevTube")
                .setMultiChoiceItems(names, st, (d, i, c) -> st[i] = c)
                .setPositiveButton("Salvar", (d, w) -> {
                    boolean reload = false;
                    SharedPreferences.Editor e = prefs.edit();
                    for (int i = 0; i < keys.length; i++) {
                        String k = keys[i];
                        // só estas mexem no que a página carrega; as outras valem na hora, sem recarregar
                        if (on(k) != st[i] && (k.equals("ads") || k.equals("shorts") || k.equals("sb") || k.equals("dis"))) reload = true;
                        e.putBoolean(k, st[i]);
                    }
                    e.apply();
                    registerScript();
                    updatePipParams();
                    web.evaluateJavascript("if(window.__gtCfg){window.__gtCfg.notop=" + on("notop")
                            + ";window.__gtCfg.dtap=" + on("dtap") + ";window.__gtCfg.qc=" + on("qc") + ";}", null);
                    if (!on("qc")) qcBar.setVisibility(View.GONE);
                    if (reload) reloadKeepingPosition();
                })
                .setNeutralButton("Mais…", (d, w) -> showMore())
                .setNegativeButton("Cancelar", null)
                .show();
    }

    /** Recarrega a página voltando pro mesmo segundo do vídeo (em vez de começar do zero). */
    private void reloadKeepingPosition() {
        if (!watching()) { web.reload(); return; }
        web.evaluateJavascript("(function(){var v=document.querySelector('video');"
                + "return JSON.stringify([location.href,v?Math.floor(v.currentTime||0):0]);})()", value -> {
            try {
                JSONArray a = new JSONArray((String) parseJs(value));
                web.loadUrl(withTime(a.getString(0), a.getInt(1)));
            } catch (Exception e) {
                web.reload();
            }
        });
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
        private final boolean mini;
        Bridge(boolean mini) { this.mini = mini; }

        @JavascriptInterface public void meta(final String title, final boolean pl) {
            if (mini ? miniWeb == null : miniWeb != null) return; // só quem está "ativo" fala com a notificação
            playing = pl;
            runOnUiThread(() -> {
                updatePipParams();
                if (KeepAliveService.instance != null)
                    KeepAliveService.instance.update(title == null ? "DevTube" : title, pl);
            });
        }

        @JavascriptInterface public void settings() {
            if (mini) return;
            runOnUiThread(() -> qcSettings());
        }

        @JavascriptInterface public void orient(final boolean landscape) {
            if (mini) return;
            runOnUiThread(() -> handleOrientation(landscape));
        }

        @JavascriptInterface public void sponsor(final String id) {
            if (id == null || !VID.matcher(id).matches()) return;
            final WebView tw = mini ? miniWeb : web;
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
                    jsOn(tw, "window.__gtSB&&window.__gtSB(" + JSONObject.quote(id) + "," + out + ")");
                } catch (Exception ignored) {}
            }).start();
        }

        @JavascriptInterface public void dislikes(final String id) {
            if (id == null || !VID.matcher(id).matches()) return;
            final WebView tw = mini ? miniWeb : web;
            new Thread(() -> {
                try {
                    String body = http("https://returnyoutubedislikeapi.com/votes?videoId=" + id);
                    if (body == null) return;
                    JSONObject o = new JSONObject(body);
                    jsOn(tw, "window.__gtRYD&&window.__gtRYD(" + JSONObject.quote(id) + "," + o.optLong("likes")
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

        qcBar = new LinearLayout(this);
        qcBar.setOrientation(LinearLayout.HORIZONTAL);
        qcBar.setVisibility(View.GONE);
        root.addView(qcBar, new FrameLayout.LayoutParams(-2, -2));
        buildQcChips();
        applyQcPos();
        applyAccentNative();
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

        web.addJavascriptInterface(new Bridge(false), "GT");
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
            // Abriu outro vídeo na tela principal (navegação interna do site): fecha o mini player.
            @Override public void doUpdateVisitedHistory(WebView v, String url, boolean isReload) {
                if (miniWeb != null && url != null && url.contains("/watch")
                        && System.currentTimeMillis() > miniGraceUntil) closeMini(true);
                updatePipParams();
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
                if (pseudoFs) exitPseudoFs();
                swipe.setVisibility(View.GONE);
                gear.setVisibility(View.GONE);
                root.addView(view, new FrameLayout.LayoutParams(-1, -1));
                qcBar.bringToFront();
                goImmersive(true);
                showQc();
                updatePipParams();
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
            gear.setVisibility(View.GONE); // some da janelinha antes de a transição começar
            qcBar.setVisibility(View.GONE);
            try {
                enterPictureInPictureMode(new PictureInPictureParams.Builder()
                        .setAspectRatio(new Rational(16, 9)).build());
            } catch (Exception ignored) {
                if (!pseudoFs && customView == null) gear.setVisibility(View.VISIBLE);
            }
        }
    }

    private boolean pipEligible() {
        return on("pip") && playing && miniWeb == null && (customView != null || watching());
    }

    /**
     * Android 12+: com "entrada automática", o sistema abre o PiP sozinho em qualquer saída do app
     * (Home, gesto, alternar entre apps). Só fica ligado enquanto um vídeo está tocando.
     */
    private void updatePipParams() {
        if (Build.VERSION.SDK_INT < 31) return;
        try {
            setPictureInPictureParams(new PictureInPictureParams.Builder()
                    .setAspectRatio(new Rational(16, 9))
                    .setAutoEnterEnabled(pipEligible())
                    .build());
        } catch (Exception ignored) {}
    }

    @Override public void onPictureInPictureModeChanged(boolean inPip, Configuration c) {
        super.onPictureInPictureModeChanged(inPip, c);
        gear.setVisibility(inPip || customView != null || pseudoFs ? View.GONE : View.VISIBLE);
        swipe.setEnabled(!inPip && !pseudoFs);
        if (miniBox != null) miniBox.setVisibility(inPip ? View.GONE : View.VISIBLE);
        if (inPip) qcBar.setVisibility(View.GONE);
        if (inPip) {
            // o vídeo ocupa a janelinha toda (mesmo método da tela cheia própria, sem controles)
            web.evaluateJavascript("window.__gtGuard=true;window.__gtFS&&window.__gtFS(true,true)", null);
        } else {
            web.evaluateJavascript("window.__gtGuard=false;window.__gtFS&&window.__gtFS(" + pseudoFs + ",false)", null);
        }
        if (!inPip) {
            // A orientação é lida só na hora de executar: logo ao sair do PiP a configuração ainda pode
            // vir "horizontal" (janelinha 16:9) e prendia o vídeo na tela cheia mesmo com o celular em pé.
            web.postDelayed(this::recheckOrientation, 350);
            web.postDelayed(this::recheckOrientation, 1000);
        }
    }

    // ---------- tela cheia automática ao girar ----------
    private boolean watching() {
        return web.getUrl() != null && web.getUrl().contains("/watch");
    }

    /** Chamado quando o celular gira (pelo Android e também pela própria página). É idempotente. */
    private void handleOrientation(boolean landscape) {
        if (!on("autofs") || isInPictureInPictureMode()) return;
        if (landscape) {
            if (!pseudoFs && customView == null && watching()) enterPseudoFs();
        } else {
            if (pseudoFs) exitPseudoFs();
        }
    }

    @Override public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        web.postDelayed(this::recheckOrientation, 250);
    }

    private void recheckOrientation() {
        if (isInPictureInPictureMode()) return;
        boolean land = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        handleOrientation(land);
        if (!land && !pseudoFs && customView == null) {
            // em pé e sem tela cheia própria: se sobrou layout de tela cheia na página, limpa
            web.evaluateJavascript("if(document.getElementById('gt-fs')&&window.__gtFS)window.__gtFS(false,false)", null);
        }
    }

    /** Tela cheia própria: o player ocupa a tela toda e as barras do sistema somem (sem precisar de toque). */
    private void enterPseudoFs() {
        pseudoFs = true;
        web.evaluateJavascript("window.__gtFS&&window.__gtFS(true)", null);
        gear.setVisibility(View.GONE);
        swipe.setEnabled(false);
        goImmersive(true);
        showQc();
    }

    private void exitPseudoFs() {
        if (!pseudoFs) return;
        pseudoFs = false;
        web.evaluateJavascript("window.__gtFS&&window.__gtFS(false)", null);
        gear.setVisibility(customView == null ? View.VISIBLE : View.GONE);
        swipe.setEnabled(true);
        goImmersive(false);
    }

    // ---------- tela cheia ----------
    private void exitFullscreen() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        updatePipParams();
        if (customCb != null) customCb.onCustomViewHidden();
        swipe.setVisibility(View.VISIBLE);
        gear.setVisibility(View.VISIBLE);
        goImmersive(false);
    }

    private void goImmersive(boolean on) {
        if (Build.VERSION.SDK_INT >= 28) {
            getWindow().getAttributes().layoutInDisplayCutoutMode = on
                    ? android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    : android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
            getWindow().setAttributes(getWindow().getAttributes());
        }
        getWindow().getDecorView().setSystemUiVisibility(on
            ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
              | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            : View.SYSTEM_UI_FLAG_VISIBLE);
    }

    @Override public void onBackPressed() {
        if (customView != null) exitFullscreen();
        else if (pseudoFs) exitPseudoFs();
        else if (on("mini") && watching()) backFromWatch();
        else defaultBack();
    }

    private void defaultBack() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    // ---------- atalhos rápidos (menus nativos) ----------
    @Override public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) showQc();
        return super.dispatchTouchEvent(ev);
    }

    private void buildQcChips() {
        qcBar.removeAllViews();
        qcBar.addView(qcChip("CC", v -> qcCaptions()));
        qcQ = qcChip("Qualidade", v -> qcQuality());
        qcBar.addView(qcQ);
        qcS = qcChip("1x", v -> qcSpeed());
        qcBar.addView(qcS);
        qcL = qcChip("\u21BB Loop", v -> qcLoop());
        qcBar.addView(qcL);
        qcBar.addView(qcChip("\u22EF", v -> qcSettings()));
    }

    private TextView qcChip(String text, View.OnClickListener l) {
        float d = getResources().getDisplayMetrics().density;
        int sp = prefs.getInt("qc_size", 13);
        TextView c = new TextView(this);
        c.setText(text);
        c.setTextColor(Color.WHITE);
        c.setTextSize(sp);
        c.setTypeface(null, android.graphics.Typeface.BOLD);
        c.setPadding((int) (sp * 0.92f * d), (int) (sp * 0.54f * d), (int) (sp * 0.92f * d), (int) (sp * 0.54f * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(16 * d);
        bg.setColor(qcBg(false));
        c.setBackground(bg);
        c.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMargins(0, 0, (int) (6 * d), 0);
        c.setLayoutParams(lp);
        return c;
    }

    // ---------- aparência ----------
    private int accentColor() {
        String a = prefs.getString("accent", "");
        try { return a.isEmpty() ? 0 : Color.parseColor(a); } catch (Exception e) { return 0; }
    }

    private int qcBg(boolean active) {
        int a = Math.round(prefs.getInt("qc_op", 70) * 2.55f);
        int base = active ? (accentColor() != 0 ? accentColor() : 0xFF2E7D32) : 0xFF000000;
        return (a << 24) | (base & 0xFFFFFF);
    }

    private int[] miniDp() {
        String s = prefs.getString("mini_size", "m");
        return s.equals("s") ? new int[]{180, 101} : s.equals("l") ? new int[]{288, 162} : new int[]{224, 126};
    }

    private void applyQcPos() {
        float d = getResources().getDisplayMetrics().density;
        String p = prefs.getString("qc_pos", "tl");
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2);
        if (p.equals("tr")) {
            lp.gravity = Gravity.TOP | Gravity.END;
            lp.setMargins(0, (int) (64 * d), (int) (12 * d), 0);   // abaixo dos ícones CC/engrenagem do YouTube
        } else if (p.equals("bottom")) {
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            lp.setMargins(0, 0, 0, (int) (72 * d));
        } else {
            lp.gravity = Gravity.TOP | Gravity.START;
            lp.setMargins((int) (12 * d), (int) (12 * d), 0, 0);
        }
        qcBar.setLayoutParams(lp);
    }

    private void applyAccentNative() {
        int a = accentColor();
        gear.setColorFilter(a != 0 ? a : Color.WHITE);
        swipe.setColorSchemeColors(a != 0 ? a : Color.RED);
    }

    private static final int MINI_VW = 640, MINI_VH = 360;

    private void applyMiniScale() {
        if (miniWeb == null) return;
        float s = (float) miniDp()[0] / MINI_VW;
        miniWeb.setScaleX(s);
        miniWeb.setScaleY(s);
    }

    private void resizeMini() {
        if (miniBox == null) return;
        float d = getResources().getDisplayMetrics().density;
        int[] m = miniDp();
        ViewGroup.LayoutParams lp = miniBox.getLayoutParams();
        lp.width = (int) (m[0] * d);
        lp.height = (int) (m[1] * d);
        miniBox.setLayoutParams(lp);
        applyMiniScale();
    }

    private void applyAppearance() {
        applyQcPos();
        buildQcChips();
        resizeMini();
        applyAccentNative();
        String code = "window.__gtAccent&&window.__gtAccent(" + JSONObject.quote(prefs.getString("accent", "")) + ")";
        web.evaluateJavascript(code, null);
        if (miniWeb != null) miniWeb.evaluateJavascript(code, null);
        showQc();
    }

    private void showAppearance() {
        final String[] miniV = {"s", "m", "l"};
        final String[] miniN = {"Pequeno", "Médio", "Grande"};
        final String[] posV = {"tl", "tr", "bottom"};
        final String[] posN = {"Superior esquerdo", "Superior direito", "Embaixo"};
        final String[] accV = {"", "#2196f3", "#00c853", "#ab47bc", "#ff9100", "#ff4081", "#00e5ff"};
        final String[] accN = {"Padrão", "Azul", "Verde", "Roxo", "Laranja", "Rosa", "Ciano"};
        final String[] opV = {"30", "50", "70", "100"};
        final String[] opN = {"Bem transparente (30%)", "Transparente (50%)", "Normal (70%)", "Sólido (100%)"};
        final String[] szV = {"11", "13", "16"};
        final String[] szN = {"Pequeno", "Médio", "Grande"};
        final int mi = idx(miniV, prefs.getString("mini_size", "m"));
        final int pi = idx(posV, prefs.getString("qc_pos", "tl"));
        final int ai = idx(accV, prefs.getString("accent", ""));
        final int oi = idx(opV, String.valueOf(prefs.getInt("qc_op", 70)));
        final int zi = idx(szV, String.valueOf(prefs.getInt("qc_size", 13)));
        String[] menu = {"Tamanho do mini player: " + miniN[mi], "Posição dos atalhos: " + posN[pi],
                "Cor de destaque: " + accN[ai], "Opacidade dos botões: " + opN[oi], "Tamanho dos botões: " + szN[zi],
                "Restaurar padrão"};
        qcBar.removeCallbacks(qcHide);
        new AlertDialog.Builder(this).setTitle("Aparência")
                .setItems(menu, (dlg, i) -> {
                    if (i == 0) qcDialog("Tamanho do mini player", miniN, mi, k -> { prefs.edit().putString("mini_size", miniV[k]).apply(); applyAppearance(); showAppearance(); });
                    else if (i == 1) qcDialog("Posição dos atalhos", posN, pi, k -> { prefs.edit().putString("qc_pos", posV[k]).apply(); applyAppearance(); showAppearance(); });
                    else if (i == 2) qcDialog("Cor de destaque", accN, ai, k -> { prefs.edit().putString("accent", accV[k]).apply(); applyAppearance(); showAppearance(); });
                    else if (i == 3) qcDialog("Opacidade dos botões", opN, oi, k -> { prefs.edit().putInt("qc_op", Integer.parseInt(opV[k])).apply(); applyAppearance(); showAppearance(); });
                    else if (i == 4) qcDialog("Tamanho dos botões", szN, zi, k -> { prefs.edit().putInt("qc_size", Integer.parseInt(szV[k])).apply(); applyAppearance(); showAppearance(); });
                    else {
                        prefs.edit().remove("mini_size").remove("qc_pos").remove("accent").remove("qc_op").remove("qc_size").apply();
                        applyAppearance();
                        showAppearance();
                    }
                })
                .setNegativeButton("Concluído", null)
                .setOnDismissListener(dlg -> { if (pseudoFs || customView != null) goImmersive(true); })
                .show();
    }

    private void showMore() {
        qcDialog("Mais", new String[]{"Velocidade padrão", "Aparência", "Estilo da legenda", "Timer de sono", "Idioma preferido"}, -1, i -> {
            if (i == 0) showSpeed();
            else if (i == 1) showAppearance();
            else if (i == 2) showCapStyle();
            else if (i == 3) qcSleep();
            else showLang();
        });
    }

    /** Mostra os atalhos por alguns segundos (a cada toque na tela de vídeo / tela cheia). */
    private void showQc() {
        if (qcBar == null || !on("qc") || isInPictureInPictureMode()) return;
        if (!(watching() || customView != null) || miniWeb != null) return;
        web.evaluateJavascript("window.__gtQcState&&window.__gtQcState()", v -> {
            try {
                Object o = new JSONTokener(v == null ? "\"\"" : v).nextValue();
                if (o instanceof String) {
                    JSONObject j = new JSONObject((String) o);
                    if (j.has("q")) qcQ.setText(j.getString("q"));
                    if (j.has("s")) qcS.setText(j.get("s") + "x");
                    if (j.has("l")) setLoopUi(j.getBoolean("l"));
                }
            } catch (Exception ignored) {}
        });
        qcBar.setVisibility(View.VISIBLE);
        qcBar.bringToFront();
        qcBar.removeCallbacks(qcHide);
        qcBar.postDelayed(qcHide, 3500);
    }

    private void qcDialog(String title, String[] names, int sel, final java.util.function.IntConsumer cb) {
        qcBar.removeCallbacks(qcHide);
        new AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(names, sel, (dlg, i) -> { dlg.dismiss(); cb.accept(i); })
                .setOnDismissListener(dlg -> {
                    if (pseudoFs || customView != null) goImmersive(true);
                    showQc();
                })
                .show();
    }

    private static Object parseJs(String v) {
        try { return new JSONTokener(v == null ? "\"\"" : v).nextValue(); } catch (Exception e) { return null; }
    }

    /** Engrenagem do player na tela cheia automática: menu nativo com os mesmos atalhos. */
    private void qcSettings() {
        qcDialog("Mais opções", new String[]{"Legendas", "Qualidade", "Velocidade", "Estilo da legenda", "Timer de sono", "Aparência", "Idioma preferido"}, -1, i -> {
            if (i == 0) qcCaptions();
            else if (i == 1) qcQuality();
            else if (i == 2) qcSpeed();
            else if (i == 3) showCapStyle();
            else if (i == 4) qcSleep();
            else if (i == 5) showAppearance();
            else showLang();
        });
    }

    private void setLoopUi(boolean on) {
        if (qcL != null && qcL.getBackground() instanceof GradientDrawable)
            ((GradientDrawable) qcL.getBackground()).setColor(qcBg(on));
    }

    private void qcLoop() {
        web.evaluateJavascript("window.__gtToggleLoop&&window.__gtToggleLoop()", v -> {
            boolean on = "true".equals(v);
            setLoopUi(on);
            Toast.makeText(this, on ? "Loop ligado neste vídeo" : "Loop desligado", Toast.LENGTH_SHORT).show();
        });
    }

    private void qcSleep() {
        final int[] mins = {0, 15, 30, 45, 60, 90};
        final String[] names = {"Desligado", "15 min", "30 min", "45 min", "1 hora", "1h30"};
        long now = System.currentTimeMillis();
        String title = "Timer de sono";
        if (sleepAt > now) title += " (faltam " + Math.max(1, (sleepAt - now) / 60000) + " min)";
        qcDialog(title, names, sleepAt > now ? -1 : 0, i -> {
            sleepH.removeCallbacks(sleepRun);
            if (mins[i] == 0) {
                sleepAt = 0;
                Toast.makeText(this, "Timer de sono desligado", Toast.LENGTH_SHORT).show();
            } else {
                sleepAt = System.currentTimeMillis() + mins[i] * 60000L;
                sleepH.postDelayed(sleepRun, mins[i] * 60000L);
                Toast.makeText(this, "O vídeo pausa em " + names[i], Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ---------- estilo das legendas ----------
    private static int idx(String[] vals, String v) {
        for (int i = 0; i < vals.length; i++) if (vals[i].equals(v)) return i;
        return 0;
    }

    private void applyCap() {
        String code = "window.__gtCapStyle&&window.__gtCapStyle(" + capJson() + ")";
        web.evaluateJavascript(code, null);
        if (miniWeb != null) miniWeb.evaluateJavascript(code, null);
    }

    private void showCapStyle() {
        final String[] sizeV = {"75", "100", "130", "170", "220"};
        final String[] sizeN = {"Pequeno (75%)", "Normal (100%)", "Grande (130%)", "Muito grande (170%)", "Enorme (220%)"};
        final String[] colV = {"", "#ffffff", "#ffeb3b", "#69f0ae", "#18ffff", "#ff80ab", "#ffab40"};
        final String[] colN = {"Padrão", "Branco", "Amarelo", "Verde", "Ciano", "Rosa", "Laranja"};
        final String[] bgV = {"", "dark", "solid", "none"};
        final String[] bgN = {"Padrão", "Escuro", "Preto sólido", "Sem fundo"};
        final String[] edV = {"", "shadow", "outline", "none"};
        final String[] edN = {"Padrão", "Sombra", "Contorno", "Nenhuma"};
        final int si = idx(sizeV, String.valueOf(prefs.getInt("cap_size", 100)));
        final int ci = idx(colV, prefs.getString("cap_color", ""));
        final int bi = idx(bgV, prefs.getString("cap_bg", ""));
        final int ei = idx(edV, prefs.getString("cap_edge", ""));
        String[] menu = {"Tamanho: " + sizeN[si], "Cor do texto: " + colN[ci], "Fundo: " + bgN[bi],
                "Borda: " + edN[ei], "Restaurar padrão"};
        qcBar.removeCallbacks(qcHide);
        new AlertDialog.Builder(this).setTitle("Estilo da legenda")
                .setItems(menu, (dlg, i) -> {
                    if (i == 0) qcDialog("Tamanho", sizeN, si, k -> { prefs.edit().putInt("cap_size", Integer.parseInt(sizeV[k])).apply(); applyCap(); showCapStyle(); });
                    else if (i == 1) qcDialog("Cor do texto", colN, ci, k -> { prefs.edit().putString("cap_color", colV[k]).apply(); applyCap(); showCapStyle(); });
                    else if (i == 2) qcDialog("Fundo", bgN, bi, k -> { prefs.edit().putString("cap_bg", bgV[k]).apply(); applyCap(); showCapStyle(); });
                    else if (i == 3) qcDialog("Borda", edN, ei, k -> { prefs.edit().putString("cap_edge", edV[k]).apply(); applyCap(); showCapStyle(); });
                    else {
                        prefs.edit().remove("cap_size").remove("cap_color").remove("cap_bg").remove("cap_edge").apply();
                        applyCap();
                        showCapStyle();
                    }
                })
                .setNegativeButton("Concluído", null)
                .setOnDismissListener(dlg -> { if (pseudoFs || customView != null) goImmersive(true); })
                .show();
    }

    private void qcSpeed() {
        web.evaluateJavascript("(function(){var v=document.querySelector('video');return v?v.playbackRate:1})()", val -> {
            float cur = 1f;
            try { cur = Float.parseFloat(val); } catch (Exception ignored) {}
            final float[] vals = {0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f};
            final String[] names = {"0.5x", "0.75x", "Normal (1x)", "1.25x", "1.5x", "1.75x", "2x"};
            int sel = -1;
            for (int i = 0; i < vals.length; i++) if (Math.abs(vals[i] - cur) < 0.01f) sel = i;
            qcDialog("Velocidade", names, sel,
                    i -> web.evaluateJavascript("window.__gtSetSpeed&&window.__gtSetSpeed(" + vals[i] + ")", null));
        });
    }

    private void qcQuality() {
        web.evaluateJavascript("window.__gtQcQuality&&window.__gtQcQuality()", val -> {
            try {
                Object o = parseJs(val);
                JSONObject j = new JSONObject((String) o);
                JSONArray lv = j.getJSONArray("levels");
                if (lv.length() == 0) throw new Exception("vazio");
                final String cur = j.optString("cur");
                final String[] ids = new String[lv.length() + 1];
                final String[] names = new String[lv.length() + 1];
                ids[0] = "auto"; names[0] = "Auto";
                int sel = -1;
                for (int i = 0; i < lv.length(); i++) {
                    ids[i + 1] = lv.getJSONArray(i).getString(0);
                    names[i + 1] = lv.getJSONArray(i).getString(1);
                    if (ids[i + 1].equals(cur)) sel = i + 1;
                }
                qcDialog("Qualidade", names, sel, i -> {
                    web.evaluateJavascript("window.__gtSetQ&&window.__gtSetQ(" + JSONObject.quote(ids[i]) + ")", null);
                    web.postDelayed(this::showQc, 1200);
                });
            } catch (Exception e) {
                Toast.makeText(this, "Qualidade indisponível neste vídeo", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static String base(String s) { return s == null ? "" : s.toLowerCase().split("[-_]")[0]; }

    private static String langName(String c) {
        switch (base(c)) {
            case "pt": return "Português";
            case "en": return "Inglês";
            case "es": return "Espanhol";
            case "ja": return "Japonês";
            default: return c;
        }
    }

    private void saveCapPref(String v) {
        prefs.edit().putString("cap_pref", v).apply();
        web.evaluateJavascript("window.__gtCfg&&(window.__gtCfg.capPref=" + JSONObject.quote(v) + ")", null);
    }

    private void qcCaptions() {
        web.evaluateJavascript("window.__gtCapPrep&&window.__gtCapPrep()", null);
        web.postDelayed(() -> web.evaluateJavascript("window.__gtCapList&&window.__gtCapList()", val -> {
            try {
                JSONObject j = new JSONObject((String) parseJs(val));
                JSONArray tr = j.getJSONArray("tracks");
                if (tr.length() == 0) throw new Exception("sem faixas");
                final String cur = j.optString("cur");
                final String trCode = j.optString("tr");
                String pf = prefs.getString("cap_pref", "");
                final String top = (pf.isEmpty() || pf.equals("off")) ? "pt" : pf;   // português sempre no topo por padrão
                final java.util.ArrayList<String> codes = new java.util.ArrayList<>();
                final java.util.ArrayList<String> names = new java.util.ArrayList<>();
                codes.add(""); names.add("Desativadas");
                boolean hasTop = false;
                for (int k = 0; k < tr.length(); k++) if (base(tr.getJSONArray(k).getString(0)).equals(base(top))) hasTop = true;
                if (!hasTop && !trCode.isEmpty()) {
                    codes.add("tr:" + base(top));
                    names.add(langName(top) + " (tradução automática)");
                }
                for (int pass = 0; pass < 2; pass++) {
                    for (int k = 0; k < tr.length(); k++) {
                        String code = tr.getJSONArray(k).getString(0);
                        boolean m = base(code).equals(base(top));
                        if ((pass == 0) == m) { codes.add(code); names.add(tr.getJSONArray(k).getString(1)); }
                    }
                }
                final int sel = cur.isEmpty() ? 0 : codes.indexOf(cur);
                codes.add(""); names.add("Estilo da legenda…");
                final int styleIdx = names.size() - 1;
                qcDialog("Legendas", names.toArray(new String[0]), sel, i -> {
                    if (i == styleIdx) { showCapStyle(); return; }
                    String c = codes.get(i);
                    if (c.isEmpty()) {
                        web.evaluateJavascript("window.__gtSetCap&&window.__gtSetCap('')", null);
                    } else if (c.startsWith("tr:")) {
                        String lg = c.substring(3);
                        saveCapPref(lg);   // a escolha fica salva e vale nos próximos vídeos
                        web.evaluateJavascript("window.__gtSetCapTr&&window.__gtSetCapTr(" + JSONObject.quote(lg) + ")", null);
                    } else {
                        saveCapPref(c);
                        web.evaluateJavascript("window.__gtSetCap&&window.__gtSetCap(" + JSONObject.quote(c) + ")", null);
                    }
                });
            } catch (Exception e) {
                // plano B: aperta o botão de legendas do próprio YouTube
                web.evaluateJavascript("window.__gtCapNative&&window.__gtCapNative()", r -> {
                    if (!"true".equals(r)) Toast.makeText(this, "Sem legendas neste vídeo", Toast.LENGTH_SHORT).show();
                });
            }
        }), 350);
    }

    // ---------- idioma preferido (legendas e áudio) ----------
    private void applyLang() {
        web.evaluateJavascript("if(window.__gtCfg){window.__gtCfg.capPref=" + JSONObject.quote(prefs.getString("cap_pref", ""))
                + ";window.__gtCfg.audPref=" + JSONObject.quote(prefs.getString("aud_pref", ""))
                + ";}window.__gtAutoLangNow&&window.__gtAutoLangNow(300)", null);
    }

    private void showLang() {
        final String[] capV = {"", "pt", "en", "es", "off"};
        final String[] capN = {"Padrão do YouTube", "Português", "Inglês", "Espanhol", "Sempre desligadas"};
        final String[] audV = {"", "pt"};
        final String[] audN = {"Padrão (original)", "Português (se houver dublagem)"};
        final int ci = idx(capV, prefs.getString("cap_pref", ""));
        final int ai = idx(audV, prefs.getString("aud_pref", ""));
        String[] menu = {"Legendas: " + capN[ci], "Áudio: " + audN[ai]};
        qcBar.removeCallbacks(qcHide);
        new AlertDialog.Builder(this).setTitle("Idioma preferido")
                .setItems(menu, (dlg, i) -> {
                    if (i == 0) qcDialog("Legendas automáticas", capN, ci, k -> { prefs.edit().putString("cap_pref", capV[k]).apply(); applyLang(); showLang(); });
                    else qcDialog("Idioma do áudio", audN, ai, k -> { prefs.edit().putString("aud_pref", audV[k]).apply(); applyLang(); showLang(); });
                })
                .setNegativeButton("Concluído", null)
                .setOnDismissListener(dlg -> { if (pseudoFs || customView != null) goImmersive(true); })
                .show();
    }

    // ---------- mini player dentro do app ----------
    /** Voltar na tela de vídeo: se está tocando, o vídeo vira um mini player e o app volta pra página anterior. */
    private void backFromWatch() {
        web.evaluateJavascript("(function(){var v=document.querySelector('video');if(!v)return '';"
                + "var pl=!v.paused&&!v.ended;var t=Math.floor(v.currentTime||0);"
                + "if(pl)v.pause();return JSON.stringify([location.href,t,pl]);})()", value -> {
            String url = null; int sec = 0; boolean pl = false;
            try {
                Object o = new JSONTokener(value == null ? "\"\"" : value).nextValue();
                if (o instanceof String) {
                    JSONArray a = new JSONArray((String) o);
                    url = a.getString(0); sec = a.getInt(1); pl = a.getBoolean(2);
                }
            } catch (Exception ignored) {}
            if (pl && url != null && url.contains("/watch")) {
                openMini(url, sec);
                String prev = previousUrl();
                // sem página anterior (ou anterior também é vídeo): vai pra home, o mini continua tocando
                if (prev == null || prev.contains("/watch")) web.loadUrl(HOME);
                else web.goBack();
            } else {
                defaultBack();
            }
        });
    }

    private String previousUrl() {
        WebBackForwardList l = web.copyBackForwardList();
        int i = l.getCurrentIndex();
        return i > 0 ? l.getItemAtIndex(i - 1).getUrl() : null;
    }

    private static String withTime(String url, int sec) {
        Uri u = Uri.parse(url);
        Uri.Builder b = u.buildUpon().clearQuery();
        for (String k : u.getQueryParameterNames()) {
            if (k.equals("t") || k.equals("start")) continue;
            for (String v : u.getQueryParameters(k)) b.appendQueryParameter(k, v);
        }
        if (sec > 0) b.appendQueryParameter("t", sec + "s");
        return b.build().toString();
    }

    private static boolean ytHost(String h) {
        return h.endsWith("youtube.com") || h.endsWith("google.com") || h.endsWith("gstatic.com")
                || h.endsWith("googleusercontent.com") || h.endsWith("googleapis.com") || h.equals("youtu.be");
    }

    private void openMini(String url, int sec) {
        closeMini(false);
        final float d = getResources().getDisplayMetrics().density;
        final String ms = cfgJs(true) + injectJs;
        miniGraceUntil = System.currentTimeMillis() + 2500;

        final WebView mw = new WebView(this);
        miniWeb = mw;
        updatePipParams();
        WebSettings s = mw.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setUserAgentString(UA);
        CookieManager.getInstance().setAcceptThirdPartyCookies(mw, true);
        mw.addJavascriptInterface(new Bridge(true), "GT");
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(mw, ms,
                    new HashSet<>(Arrays.asList("https://m.youtube.com", "https://www.youtube.com")));
        }
        mw.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                if (on("ads") && isAd(r.getUrl())) {
                    return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
                }
                return null;
            }
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                String h = r.getUrl().getHost() == null ? "" : r.getUrl().getHost();
                return !ytHost(h);
            }
            @Override public void onPageStarted(WebView v, String u, Bitmap f) { v.evaluateJavascript(ms, null); }
            @Override public void onPageFinished(WebView v, String u) { v.evaluateJavascript(ms, null); }
        });
        mw.setWebChromeClient(new WebChromeClient());

        final FrameLayout box = new FrameLayout(this);
        miniBox = box;
        box.setBackgroundColor(Color.BLACK);
        box.setElevation(10 * d);
        final float r = 10 * d;
        box.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r); }
        });
        box.setClipToOutline(true);
        // A página carrega num tamanho "normal" (640x360 dp) e é só reduzida por fora (escala),
        // em vez de ser espremida numa janelinha: o layout do site não quebra.
        FrameLayout.LayoutParams wl = new FrameLayout.LayoutParams(
                (int) (MINI_VW * d), (int) (MINI_VH * d), Gravity.TOP | Gravity.START);
        mw.setPivotX(0);
        mw.setPivotY(0);
        box.addView(mw, wl);
        applyMiniScale();

        // toque no mini player = maximiza
        View tap = new View(this);
        tap.setOnClickListener(v -> maximizeMini());
        box.addView(tap, new FrameLayout.LayoutParams(-1, -1));

        // X = fecha
        ImageButton x = new ImageButton(this);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xB3000000);
        x.setBackground(bg);
        x.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        x.setColorFilter(Color.WHITE);
        x.setPadding((int) (5 * d), (int) (5 * d), (int) (5 * d), (int) (5 * d));
        x.setOnClickListener(v -> closeMini(true));
        FrameLayout.LayoutParams xl = new FrameLayout.LayoutParams((int) (28 * d), (int) (28 * d), Gravity.TOP | Gravity.END);
        xl.setMargins(0, (int) (4 * d), (int) (4 * d), 0);
        box.addView(x, xl);

        // fica acima da barra de baixo do YouTube
        int[] mdp = miniDp();
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams((int) (mdp[0] * d), (int) (mdp[1] * d), Gravity.BOTTOM | Gravity.END);
        lp.setMargins(0, 0, (int) (8 * d), (int) (64 * d));
        root.addView(box, lp);

        mw.loadUrl(withTime(url, sec));
    }

    private void maximizeMini() {
        if (miniWeb == null) return;
        miniWeb.evaluateJavascript("(function(){var v=document.querySelector('video');"
                + "return JSON.stringify([location.href,v?Math.floor(v.currentTime||0):0]);})()", value -> {
            if (miniWeb == null) return;
            String url = null; int sec = 0;
            try {
                Object o = new JSONTokener(value == null ? "\"\"" : value).nextValue();
                if (o instanceof String) {
                    JSONArray a = new JSONArray((String) o);
                    url = a.getString(0); sec = a.getInt(1);
                }
            } catch (Exception ignored) {}
            if (url == null) return;
            closeMini(false);
            web.loadUrl(withTime(url, sec));
        });
    }

    private void closeMini(boolean resetNotification) {
        if (miniWeb == null) return;
        WebView mw = miniWeb;
        FrameLayout box = miniBox;
        miniWeb = null; miniBox = null;
        updatePipParams();
        if (box != null) { box.removeAllViews(); root.removeView(box); }
        try { mw.loadUrl("about:blank"); } catch (Exception ignored) {}
        mw.destroy();
        if (resetNotification) {
            playing = false;
            if (KeepAliveService.instance != null) KeepAliveService.instance.update("DevTube", false);
        }
    }

    // Propositalmente NÃO chamamos web.onPause(): assim o áudio segue tocando em segundo plano.
    @Override protected void onResume() { super.onResume(); web.onResume(); if (miniWeb != null) miniWeb.onResume(); updatePipParams(); }

    @Override protected void onDestroy() {
        instance = null;
        sleepH.removeCallbacks(sleepRun);
        closeMini(false);
        stopService(new Intent(this, KeepAliveService.class));
        CookieManager.getInstance().flush();
        ((ViewGroup) web.getParent()).removeView(web);
        web.destroy();
        super.onDestroy();
    }
}
