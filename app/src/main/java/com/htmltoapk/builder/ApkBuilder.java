package com.htmltoapk.builder;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;

import com.android.apksig.ApkSigner;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.Normalizer;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Gera um APK: clona o molde WebView (assets/template.apk), troca nome/pacote/versão
 * no manifest binário, injeta o site em assets/www, troca o ícone, alinha e assina.
 */
public final class ApkBuilder {

    public static final class Options {
        public String appName = "";
        public String packageName = "";
        public String versionName = "1.0";
        public boolean fullscreen = false;
        public String orientation = "auto"; // auto | portrait | landscape
        public Uri sourceUri;               // .html ou .zip (com index.html)
        public String htmlText;             // código colado (se não houver arquivo)
        public Uri iconUri;                 // opcional
    }

    public interface Progress { void step(String message); }

    private static final String TEMPLATE_PACKAGE = "com.htmltoapk.template";
    private static final Pattern PKG =
            Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$");

    private ApkBuilder() {}

    public static String suggestPackage(String name) {
        String s = Normalizer.normalize(name == null ? "" : name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase().replaceAll("[^a-z0-9]", "");
        if (s.isEmpty()) s = "app";
        if (Character.isDigit(s.charAt(0))) s = "app" + s;
        return "com.html2apk." + s;
    }

    private static String fileSlug(String name) {
        String s = Normalizer.normalize(name, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return s.isEmpty() ? "app" : s;
    }

    public static File build(Context ctx, Options o, Progress pr) throws Exception {
        String name = o.appName == null ? "" : o.appName.trim();
        if (name.isEmpty()) throw new IllegalArgumentException("Dê um nome ao app.");
        String pkg = o.packageName == null ? "" : o.packageName.trim();
        if (pkg.isEmpty()) pkg = suggestPackage(name);
        if (!PKG.matcher(pkg).matches()) {
            throw new IllegalArgumentException("Pacote inválido. Use algo como com.meusite.app");
        }
        String ver = o.versionName == null || o.versionName.trim().isEmpty() ? "1.0" : o.versionName.trim();

        // --- origem do site ---
        pr.step("Lendo o site…");
        byte[] htmlBytes = null;
        String zipPrefix = null;
        if (o.sourceUri != null) {
            if (isZip(ctx, o.sourceUri)) zipPrefix = findZipRoot(ctx, o.sourceUri);
            else try (InputStream is = open(ctx, o.sourceUri)) { htmlBytes = readAll(is); }
        } else if (o.htmlText != null && !o.htmlText.trim().isEmpty()) {
            htmlBytes = o.htmlText.getBytes(StandardCharsets.UTF_8);
        } else {
            throw new IllegalArgumentException("Escolha um HTML/ZIP ou cole o código.");
        }

        byte[] iconPng = null;
        if (o.iconUri != null) {
            pr.step("Preparando o ícone…");
            iconPng = loadIcon(ctx, o.iconUri);
        }

        Map<String, String> repl = new LinkedHashMap<>();
        repl.put(TEMPLATE_PACKAGE, pkg);
        repl.put("__APP_NAME__", name);
        repl.put("__VERSION__", ver);

        JSONObject cfg = new JSONObject();
        cfg.put("fullscreen", o.fullscreen);
        cfg.put("orientation", o.orientation == null ? "auto" : o.orientation);

        File outDir = new File(ctx.getFilesDir(), "apks");
        if (!outDir.exists()) outDir.mkdirs();
        File[] old = outDir.listFiles();
        if (old != null) for (File f : old) f.delete();
        File unsigned = new File(ctx.getCacheDir(), "unsigned.apk");
        File out = new File(outDir, fileSlug(name) + ".apk");

        // --- monta o ZIP ---
        pr.step("Montando o APK…");
        boolean iconDone = false;
        try (InputStream tin = ctx.getAssets().open("template.apk");
             ZipInputStream zin = new ZipInputStream(tin);
             ZipWriter zw = new ZipWriter(new FileOutputStream(unsigned))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String n = e.getName();
                if (e.isDirectory() || n.startsWith("META-INF/")
                        || n.startsWith("assets/www/") || n.equals("assets/config.json")) continue;
                byte[] data = readAll(zin);
                if (n.equals("AndroidManifest.xml")) {
                    data = AxmlPatcher.replaceStrings(data, repl);
                } else if (iconPng != null && n.startsWith("res/")
                        && n.contains("ic_launcher") && n.endsWith(".png")) {
                    data = iconPng;
                    iconDone = true;
                }
                if (n.equals("resources.arsc")) zw.putStored(n, data);
                else zw.putDeflated(n, data);
            }
            zw.putDeflated("assets/config.json", cfg.toString().getBytes(StandardCharsets.UTF_8));

            pr.step("Copiando arquivos do site…");
            if (htmlBytes != null) {
                zw.putDeflated("assets/www/index.html", htmlBytes);
            } else {
                try (ZipInputStream zi = new ZipInputStream(open(ctx, o.sourceUri))) {
                    ZipEntry se;
                    while ((se = zi.getNextEntry()) != null) {
                        if (se.isDirectory()) continue;
                        String n = cleanName(se.getName());
                        if (n == null || !n.startsWith(zipPrefix)) continue;
                        String rel = n.substring(zipPrefix.length());
                        if (rel.isEmpty()) continue;
                        zw.putDeflated("assets/www/" + rel, zi);
                    }
                }
            }
        }
        if (iconPng != null && !iconDone) {
            throw new IOException("Não achei o ícone no molde (res/*ic_launcher*.png).");
        }

        // --- assina ---
        pr.step("Assinando…");
        if (out.exists()) out.delete();
        sign(ctx, unsigned, out);
        unsigned.delete();
        return out;
    }

    private static void sign(Context ctx, File in, File out) throws Exception {
        PrivateKey key;
        X509Certificate cert;
        try (InputStream k = ctx.getAssets().open("signing/key.pk8")) {
            key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(readAll(k)));
        }
        try (InputStream c = ctx.getAssets().open("signing/cert.der")) {
            cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(c);
        }
        ApkSigner.SignerConfig sc = new ApkSigner.SignerConfig.Builder(
                "builder", key, Collections.singletonList(cert)).build();
        new ApkSigner.Builder(Collections.singletonList(sc))
                .setInputApk(in)
                .setOutputApk(out)
                .setMinSdkVersion(21)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .build()
                .sign();
    }

    // ---------- helpers ----------

    private static InputStream open(Context ctx, Uri uri) throws IOException {
        InputStream is = ctx.getContentResolver().openInputStream(uri);
        if (is == null) throw new IOException("Não consegui abrir o arquivo escolhido.");
        return is;
    }

    private static boolean isZip(Context ctx, Uri uri) throws IOException {
        try (InputStream is = open(ctx, uri)) {
            byte[] h = new byte[4];
            int n = 0, r;
            while (n < 4 && (r = is.read(h, n, 4 - n)) > 0) n += r;
            return n == 4 && h[0] == 'P' && h[1] == 'K' && h[2] == 3 && h[3] == 4;
        }
    }

    /** Devolve o prefixo (pasta) onde fica o index.html mais raso dentro do ZIP. */
    private static String findZipRoot(Context ctx, Uri uri) throws IOException {
        String best = null;
        int bestDepth = Integer.MAX_VALUE;
        try (ZipInputStream zi = new ZipInputStream(open(ctx, uri))) {
            ZipEntry e;
            while ((e = zi.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String n = cleanName(e.getName());
                if (n == null) continue;
                if (n.equals("index.html") || n.endsWith("/index.html")) {
                    int depth = n.split("/").length;
                    if (depth < bestDepth) {
                        bestDepth = depth;
                        best = n.substring(0, n.length() - "index.html".length());
                    }
                }
            }
        }
        if (best == null) throw new IOException("O ZIP precisa conter um index.html.");
        return best;
    }

    /** Normaliza o caminho e descarta entradas perigosas/lixo (zip-slip, __MACOSX). */
    private static String cleanName(String name) {
        String n = name.replace('\\', '/');
        while (n.startsWith("/")) n = n.substring(1);
        if (n.startsWith("__MACOSX/") || n.endsWith(".DS_Store") || n.isEmpty()) return null;
        for (String seg : n.split("/")) if (seg.equals("..")) return null;
        return n;
    }

    private static byte[] loadIcon(Context ctx, Uri uri) throws IOException {
        BitmapFactory.Options bo = new BitmapFactory.Options();
        bo.inJustDecodeBounds = true;
        try (InputStream is = open(ctx, uri)) { BitmapFactory.decodeStream(is, null, bo); }
        int sample = 1;
        while (bo.outWidth / (sample * 2) >= 512 && bo.outHeight / (sample * 2) >= 512) sample *= 2;
        BitmapFactory.Options d = new BitmapFactory.Options();
        d.inSampleSize = sample;
        Bitmap src;
        try (InputStream is = open(ctx, uri)) { src = BitmapFactory.decodeStream(is, null, d); }
        if (src == null) throw new IOException("Não consegui ler a imagem do ícone.");

        final int S = 192;
        Bitmap sq = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888);
        int w = src.getWidth(), h = src.getHeight(), m = Math.min(w, h);
        Rect from = new Rect((w - m) / 2, (h - m) / 2, (w + m) / 2, (h + m) / 2);
        new Canvas(sq).drawBitmap(src, from, new Rect(0, 0, S, S), new Paint(Paint.FILTER_BITMAP_FLAG));
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        sq.compress(Bitmap.CompressFormat.PNG, 100, bos);
        return bos.toByteArray();
    }

    private static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] b = new byte[1 << 15];
        int n;
        while ((n = is.read(b)) > 0) o.write(b, 0, n);
        return o.toByteArray();
    }
}
