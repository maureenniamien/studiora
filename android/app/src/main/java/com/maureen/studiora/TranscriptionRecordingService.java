package com.maureen.studiora;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.File;

/**
 * Service au premier plan (2026-09-19) : garde l'enregistrement micro actif
 * même quand l'app passe en arrière-plan ou que l'écran se verrouille —
 * sans ça, Android coupe l'accès au micro dès que l'Activity n'est plus au
 * premier plan (restriction système depuis Android 9+, pas un bug). Une
 * notification permanente est obligatoire pendant l'enregistrement ; elle
 * inclut un bouton "Arrêter" qui fonctionne même app fermée.
 *
 * Remplace le MediaRecorder qui vivait directement dans MainActivity
 * (NativeRecorderInterface) : mêmes réglages, même dossier de sortie
 * (getFilesDir()/native-recordings, servi ensuite à la page web via
 * WebViewAssetLoader "/rec/"), seule la durée de vie change.
 *
 * Communication avec MainActivity : asynchrone, via SharedPreferences
 * ("studiora_recorder") + une diffusion locale (ACTION_STOPPED_BROADCAST) —
 * voir recordingStoppedReceiver et onResume() dans MainActivity.java. Ça
 * couvre aussi bien "l'app est ouverte quand ça s'arrête" que "l'app a été
 * fermée/tuée entre-temps et ne le découvre qu'au prochain onResume".
 */
public class TranscriptionRecordingService extends Service {

    private static final String TAG = "TranscriptionRecSvc";

    public static final String ACTION_START = "com.maureen.studiora.action.START_RECORDING";
    public static final String ACTION_STOP = "com.maureen.studiora.action.STOP_RECORDING";
    public static final String ACTION_STOPPED_BROADCAST = "com.maureen.studiora.RECORDING_STOPPED";
    public static final String EXTRA_FILE = "file";
    public static final String EXTRA_ERROR = "error";

    public static final String PREFS_NAME = "studiora_recorder";
    public static final String KEY_PENDING_FILE = "pending_file";
    public static final String KEY_PENDING_ERROR = "pending_error";

    private static final String CHANNEL_ID = "studiora_recording";
    private static final int NOTIF_ID = 4471;

    private MediaRecorder recorder;
    private File outputFile;
    private File recDir;

    @Override
    public void onCreate() {
        super.onCreate();
        recDir = new File(getFilesDir(), "native-recordings");
        if (!recDir.exists()) recDir.mkdirs();
        createChannelIfNeeded();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            stopRecordingAndNotify();
        } else {
            startRecording();
        }
        return START_NOT_STICKY;
    }

    private void startRecording() {
        try {
            if (recorder != null) {
                try { recorder.release(); } catch (Exception ignored) {}
                recorder = null;
            }
            outputFile = new File(recDir, "rec_" + System.currentTimeMillis() + ".m4a");
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioEncodingBitRate(32000);
            recorder.setAudioSamplingRate(16000);
            recorder.setOutputFile(outputFile.getAbsolutePath());
            recorder.prepare();
            recorder.start();
            startForeground(NOTIF_ID, buildNotification());
        } catch (Exception e) {
            Log.w(TAG, "startRecording: " + e.getMessage());
            recorder = null;
            savePendingError("start_failed");
            stopSelf();
        }
    }

    private void stopRecordingAndNotify() {
        if (recorder == null) {
            savePendingError("not_recording");
            stopForeground(true);
            stopSelf();
            return;
        }
        String fileName = outputFile != null ? outputFile.getName() : null;
        try {
            recorder.stop();
        } catch (Exception e) {
            Log.w(TAG, "stopRecording (stop): " + e.getMessage());
            fileName = null; // stop() a échoué : fichier probablement invalide/vide
        }
        try { recorder.release(); } catch (Exception ignored) {}
        recorder = null;
        if (fileName == null) {
            savePendingError("stop_failed");
        } else {
            savePendingFile(fileName);
        }
        stopForeground(true);
        stopSelf();
    }

    private void savePendingFile(String fileName) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_PENDING_FILE, fileName)
                .remove(KEY_PENDING_ERROR)
                .apply();
        sendBroadcast(new Intent(ACTION_STOPPED_BROADCAST).setPackage(getPackageName()).putExtra(EXTRA_FILE, fileName));
    }

    private void savePendingError(String error) {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(KEY_PENDING_ERROR, error)
                .remove(KEY_PENDING_FILE)
                .apply();
        sendBroadcast(new Intent(ACTION_STOPPED_BROADCAST).setPackage(getPackageName()).putExtra(EXTRA_ERROR, error));
    }

    private Notification buildNotification() {
        Intent stopIntent = new Intent(this, TranscriptionRecordingService.class).setAction(ACTION_STOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT
                | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0);
        PendingIntent stopPending = PendingIntent.getService(this, 0, stopIntent, flags);

        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent openPending = PendingIntent.getActivity(this, 0, openIntent, flags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Studiora — Transcription en cours")
                .setContentText("Enregistrement actif. Touche pour revenir à l'app.")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openPending)
                .addAction(0, "Arrêter", stopPending)
                .build();
    }

    private void createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager mgr = getSystemService(NotificationManager.class);
            if (mgr != null && mgr.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID, "Transcription vocale", NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("Notification affichée pendant un enregistrement de transcription.");
                channel.setShowBadge(false);
                mgr.createNotificationChannel(channel);
            }
        }
    }

    @Override
    public void onDestroy() {
        if (recorder != null) {
            try { recorder.stop(); } catch (Exception ignored) {}
            try { recorder.release(); } catch (Exception ignored) {}
            recorder = null;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
