package com.greentube.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.IBinder;

/** Mantém o áudio vivo em segundo plano e mostra controles na notificação / tela de bloqueio. */
public class KeepAliveService extends Service {
    static final String CH = "greentube_bg";
    static KeepAliveService instance;

    static final String USER_PAUSE = "window.__gtUser=true;v.pause();setTimeout(function(){window.__gtUser=false},500);";
    static final String JS_TOGGLE = "var v=document.querySelector('video');if(v){if(v.paused)v.play();else{" + USER_PAUSE + "}}";
    static final String JS_FF = "var v=document.querySelector('video');if(v)v.currentTime+=10;";
    static final String JS_RW = "var v=document.querySelector('video');if(v)v.currentTime-=10;";

    private MediaSession session;
    private String title = "DevTube";
    private boolean playing = false;

    @Override public void onCreate() {
        super.onCreate();
        instance = this;
        getSystemService(NotificationManager.class)
                .createNotificationChannel(new NotificationChannel(CH, "Reprodução", NotificationManager.IMPORTANCE_LOW));
        session = new MediaSession(this, "DevTube");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { MainActivity.js(JS_TOGGLE_PLAY(true)); }
            @Override public void onPause() { MainActivity.js(JS_TOGGLE_PLAY(false)); }
            @Override public void onFastForward() { MainActivity.js(JS_FF); }
            @Override public void onRewind() { MainActivity.js(JS_RW); }
            @Override public void onSeekTo(long pos) {
                MainActivity.js("var v=document.querySelector('video');if(v)v.currentTime=" + (pos / 1000.0) + ";");
            }
        });
        session.setActive(true);
    }

    private static String JS_TOGGLE_PLAY(boolean play) {
        return "var v=document.querySelector('video');if(v){" + (play ? "v.play();" : USER_PAUSE) + "}";
    }

    private PendingIntent act(String a) {
        return PendingIntent.getService(this, a.hashCode(), new Intent(this, KeepAliveService.class).setAction(a),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification build() {
        session.setMetadata(new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, title).build());
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                        | PlaybackState.ACTION_FAST_FORWARD | PlaybackState.ACTION_REWIND | PlaybackState.ACTION_SEEK_TO)
                .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build());

        PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE);

        return new Notification.Builder(this, CH)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(title)
                .setContentText("DevTube")
                .setOngoing(true)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, android.R.drawable.ic_media_rew), "-10s", act("RW")).build())
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this,
                                playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play),
                        playing ? "Pausar" : "Tocar", act("PP")).build())
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, android.R.drawable.ic_media_ff), "+10s", act("FF")).build())
                .setStyle(new Notification.MediaStyle()
                        .setMediaSession(session.getSessionToken())
                        .setShowActionsInCompactView(0, 1, 2))
                .build();
    }

    /** Chamado pelo app quando o título ou o estado de reprodução mudam. */
    void update(String t, boolean p) {
        title = t; playing = p;
        getSystemService(NotificationManager.class).notify(1, build());
    }

    @Override public int onStartCommand(Intent i, int f, int id) {
        String a = i == null ? null : i.getAction();
        if ("PP".equals(a)) MainActivity.js(JS_TOGGLE);
        else if ("FF".equals(a)) MainActivity.js(JS_FF);
        else if ("RW".equals(a)) MainActivity.js(JS_RW);

        Notification n = build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(1, n);
        }
        return START_NOT_STICKY;
    }

    @Override public void onDestroy() {
        if (session != null) session.release();
        instance = null;
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }
}
