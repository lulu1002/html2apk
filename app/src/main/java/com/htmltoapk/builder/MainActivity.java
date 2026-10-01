package com.htmltoapk.builder;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {
    private static final int REQ_SOURCE = 1;
    private static final int REQ_ICON = 2;
    private static final String APK_MIME = "application/vnd.android.package-archive";
    private static final String[] ORIENT_VALUES = {"auto", "portrait", "landscape"};

    private EditText etName, etPkg, etVersion, etHtml;
    private CheckBox cbFull;
    private Spinner spOrient;
    private TextView tvSource, tvIcon, tvStatus;
    private Button btnBuild;
    private LinearLayout rowResult;
    private Uri sourceUri, iconUri;
    private File lastApk;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        ScrollView sv = new ScrollView(this);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(14), dp(18), dp(28));
        sv.addView(col);
        setContentView(sv);

        TextView title = new TextView(this);
        title.setText("Construtor de HTML para APK");
        title.setTextSize(22);
        title.setPadding(0, 0, 0, dp(4));
        col.addView(title);

        col.addView(label("Nome do app"));
        etName = edit("Meu App", "");
        col.addView(etName);

        col.addView(label("Pacote (opcional)"));
        etPkg = edit("automático: com.html2apk.nome", "");
        etPkg.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        col.addView(etPkg);

        col.addView(label("Versão"));
        etVersion = edit("1.0", "1.0");
        col.addView(etVersion);

        col.addView(label("Site"));
        Button bSource = new Button(this);
        bSource.setText("Escolher HTML ou ZIP");
        bSource.setOnClickListener(v -> pick(REQ_SOURCE));
        col.addView(bSource);
        tvSource = new TextView(this);
        tvSource.setText("Nenhum arquivo (ou cole o código abaixo)");
        col.addView(tvSource);

        etHtml = edit("<!doctype html>… cole o código aqui", "");
        etHtml.setMinLines(5);
        etHtml.setGravity(Gravity.TOP);
        etHtml.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        col.addView(etHtml);

        col.addView(label("Ícone (opcional)"));
        Button bIcon = new Button(this);
        bIcon.setText("Escolher imagem");
        bIcon.setOnClickListener(v -> pick(REQ_ICON));
        col.addView(bIcon);
        tvIcon = new TextView(this);
        tvIcon.setText("Ícone padrão");
        col.addView(tvIcon);

        col.addView(label("Opções"));
        cbFull = new CheckBox(this);
        cbFull.setText("Tela cheia (esconde barras)");
        col.addView(cbFull);
        spOrient = new Spinner(this);
        spOrient.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"Orientação: automática", "Orientação: retrato", "Orientação: paisagem"}));
        col.addView(spOrient);

        btnBuild = new Button(this);
        btnBuild.setText("Gerar APK");
        btnBuild.setOnClickListener(v -> build());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(18);
        col.addView(btnBuild, lp);

        tvStatus = new TextView(this);
        tvStatus.setPadding(0, dp(10), 0, dp(6));
        col.addView(tvStatus);

        rowResult = new LinearLayout(this);
        rowResult.setOrientation(LinearLayout.HORIZONTAL);
        rowResult.setVisibility(android.view.View.GONE);
        rowResult.addView(smallButton("Instalar", v -> install()));
        rowResult.addView(smallButton("Downloads", v -> saveToDownloads()));
        rowResult.addView(smallButton("Compartilhar", v -> share()));
        col.addView(rowResult);
    }

    // ---------- UI helpers ----------

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView label(String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextSize(13);
        tv.setPadding(0, dp(14), 0, dp(2));
        return tv;
    }

    private EditText edit(String hint, String text) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(text);
        e.setSingleLine(false);
        return e;
    }

    private Button smallButton(String t, android.view.View.OnClickListener l) {
        Button bt = new Button(this);
        bt.setText(t);
        bt.setOnClickListener(l);
        bt.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return bt;
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    private void setStatus(String s) { tvStatus.setText(s); }

    // ---------- seleção de arquivos ----------

    private void pick(int req) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(req == REQ_ICON ? "image/*" : "*/*");
        startActivityForResult(i, req);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri u = data.getData();
        if (req == REQ_SOURCE) {
            sourceUri = u;
            tvSource.setText("Arquivo: " + displayName(u));
        } else if (req == REQ_ICON) {
            iconUri = u;
            tvIcon.setText("Ícone: " + displayName(u));
        }
    }

    private String displayName(Uri u) {
        try (Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst() && c.getString(0) != null) return c.getString(0);
        } catch (Exception ignored) { }
        return String.valueOf(u.getLastPathSegment());
    }

    // ---------- gerar ----------

    private void build() {
        final ApkBuilder.Options o = new ApkBuilder.Options();
        o.appName = etName.getText().toString();
        o.packageName = etPkg.getText().toString();
        o.versionName = etVersion.getText().toString();
        o.fullscreen = cbFull.isChecked();
        o.orientation = ORIENT_VALUES[spOrient.getSelectedItemPosition()];
        o.sourceUri = sourceUri;
        o.htmlText = etHtml.getText().toString();
        o.iconUri = iconUri;

        btnBuild.setEnabled(false);
        rowResult.setVisibility(android.view.View.GONE);
        setStatus("Gerando…");

        new Thread(() -> {
            try {
                final File f = ApkBuilder.build(getApplicationContext(), o,
                        msg -> runOnUiThread(() -> setStatus(msg)));
                runOnUiThread(() -> {
                    lastApk = f;
                    btnBuild.setEnabled(true);
                    setStatus("Pronto: " + f.getName() + " (" + (f.length() / 1024) + " KB)");
                    rowResult.setVisibility(android.view.View.VISIBLE);
                });
            } catch (final Throwable t) {
                runOnUiThread(() -> {
                    btnBuild.setEnabled(true);
                    String m = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                    setStatus("Erro: " + m);
                });
            }
        }).start();
    }

    // ---------- resultado ----------

    private Uri apkUri() {
        return FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", lastApk);
    }

    private void install() {
        if (lastApk == null) return;
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            toast("Permita instalar apps desta fonte e toque em Instalar de novo.");
            startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())));
            return;
        }
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(apkUri(), APK_MIME);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    private void share() {
        if (lastApk == null) return;
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType(APK_MIME);
        i.putExtra(Intent.EXTRA_STREAM, apkUri());
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(i, "Compartilhar APK"));
    }

    private void saveToDownloads() {
        if (lastApk == null) return;
        if (Build.VERSION.SDK_INT < 29) {
            toast("Neste Android use \"Compartilhar\" e salve em Arquivos.");
            return;
        }
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, lastApk.getName());
            v.put(MediaStore.MediaColumns.MIME_TYPE, APK_MIME);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            Uri dst = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (dst == null) throw new java.io.IOException("sem destino");
            try (OutputStream os = getContentResolver().openOutputStream(dst);
                 InputStream is = new FileInputStream(lastApk)) {
                byte[] buf = new byte[1 << 15];
                int n;
                while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
            }
            toast("Salvo em Downloads/" + lastApk.getName());
        } catch (Exception e) {
            toast("Não consegui salvar: " + e.getMessage());
        }
    }
}
