package com.maureen.studiora;

import android.Manifest;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.getcapacitor.BridgeActivity;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends BridgeActivity {

    private static final String TAG = "StudioraMain";
    private static final int MIC_PERMISSION_CODE = 2001;
    private Intent pendingIntent = null;
    private TextToSpeech tts;
    private SpeechRecognizer speechRecognizer;
    // Thread dedie aux telechargements reseau : Android interdit tout appel
    // reseau sur le thread principal (NetworkOnMainThreadException).
    private final ExecutorService downloadExecutor = Executors.newSingleThreadExecutor();

    private class WebAppInterface {
        @JavascriptInterface
        public void speak(String text) {
            if (tts != null && text != null && !text.isEmpty()) {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "studiora_tts");
            }
        }

        @JavascriptInterface
        public void startListening() {
            new Handler(Looper.getMainLooper()).post(() -> startNativeListening());
        }

        @JavascriptInterface
        public void stopListening() {
            new Handler(Looper.getMainLooper()).post(() -> {
                if (speechRecognizer != null) speechRecognizer.stopListening();
            });
        }
    }

    private class DownloadInterface {
        @JavascriptInterface
        public void saveFile(String base64Data, String filename, String mimeType) {
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    byte[] bytes = Base64.decode(base64Data, Base64.NO_WRAP);
                    writeBytesToDownloads(bytes, filename, mimeType);
                } catch (Exception e) {
                    Log.e(TAG, "saveFile error", e);
                    notifyDownloadResult(false, safeMsg(e));
                }
            });
        }

        // Telecharge une URL distante (ex: image Pollinations) puis l'ecrit dans le
        // stockage du telephone. Fait sur un thread d'arriere-plan : evite a la fois
        // le NetworkOnMainThreadException ET les blocages CORS que fetch() subit
        // cote WebView (le CORS ne s'applique qu'aux requetes JS, pas a un
        // telechargement natif Java).
        @JavascriptInterface
        public void saveFileFromUrl(String url, String filename, String mimeType) {
            downloadExecutor.execute(() -> {
                HttpURLConnection conn = null;
                try {
                    URL u = new URL(url);
                    conn = (HttpURLConnection) u.openConnection();
                    conn.setConnectTimeout(15000);
                    conn.setReadTimeout(30000);
                    conn.setRequestMethod("GET");
                    conn.connect();

                    int code = conn.getResponseCode();
                    if (code < 200 || code >= 300) {
                        throw new Exception("HTTP " + code);
                    }

                    InputStream is = conn.getInputStream();
                    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                    byte[] chunk = new byte[8192];
                    int n;
                    while ((n = is.read(chunk)) != -1) buffer.write(chunk, 0, n);
                    is.close();
                    byte[] bytes = buffer.toByteArray();

                    new Handler(Looper.getMainLooper()).post(() -> {
                        try {
                            writeBytesToDownloads(bytes, filename, mimeType);
                        } catch (Exception e) {
                            Log.e(TAG, "saveFileFromUrl write error", e);
                            notifyDownloadResult(false, safeMsg(e));
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "saveFileFromUrl download error", e);
                    final String msg = safeMsg(e);
                    new Handler(Looper.getMainLooper()).post(() -> notifyDownloadResult(false, msg));
                } finally {
                    if (conn != null) conn.disconnect();
                }
            });
        }

        @JavascriptInterface
        public void downloadAndInstallApk(String url) {
            new Handler(Looper.getMainLooper()).post(() -> startApkDownload(url));
        }
    }

    private long updateDownloadId = -1;
    private final android.content.BroadcastReceiver downloadReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, Intent intent) {
            long id = intent.getLongExtra(android.app.DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (id == updateDownloadId) {
                installDownloadedApk();
            }
        }
    };

    private void startApkDownload(String url) {
        try {
            android.app.DownloadManager dm = (android.app.DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            android.app.DownloadManager.Request request = new android.app.DownloadManager.Request(Uri.parse(url));
            request.setTitle("Mise a jour Studiora");
            request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, "studiora_update.apk");
            request.setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            updateDownloadId = dm.enqueue(request);
            runJs("window.onApkUpdateStatus && window.onApkUpdateStatus('downloading');");
        } catch (Exception e) {
            Log.e(TAG, "startApkDownload error", e);
            runJs("window.onApkUpdateStatus && window.onApkUpdateStatus('error');");
        }
    }

    private void installDownloadedApk() {
        try {
            File file = new File(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "studiora_update.apk");
            Uri apkUri = androidx.core.content.FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", file);
            Intent installIntent = new Intent(Intent.ACTION_VIEW);
            installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            installIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(installIntent);
            runJs("window.onApkUpdateStatus && window.onApkUpdateStatus('ready');");
        } catch (Exception e) {
            Log.e(TAG, "installDownloadedApk error", e);
            runJs("window.onApkUpdateStatus && window.onApkUpdateStatus('error');");
        }
    }

    // Ecriture reelle dans le stockage du telephone (MediaStore sur Android 10+,
    // fichier direct avant). Appelee UNIQUEMENT depuis le thread principal.
    // Partagee par saveFile() (base64 deja en memoire) et saveFileFromUrl()
    // (bytes telecharges en arriere-plan) pour ne pas dupliquer cette logique.
    private void writeBytesToDownloads(byte[] bytes, String filename, String mimeType) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
                values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
                values.put(MediaStore.Downloads.IS_PENDING, 1);
                Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                Uri item = getContentResolver().insert(collection, values);
                if (item == null) {
                    notifyDownloadResult(false, "insert_failed");
                    return;
                }
                OutputStream out = getContentResolver().openOutputStream(item);
                if (out != null) { out.write(bytes); out.close(); }
                values.clear();
                values.put(MediaStore.Downloads.IS_PENDING, 0);
                getContentResolver().update(item, values, null, null);
            } else {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) dir.mkdirs();
                File file = new File(dir, filename);
                FileOutputStream fos = new FileOutputStream(file);
                fos.write(bytes);
                fos.close();
            }
            notifyDownloadResult(true, filename);
        } catch (Exception e) {
            Log.e(TAG, "writeBytesToDownloads error", e);
            notifyDownloadResult(false, safeMsg(e));
        }
    }

    private void notifyDownloadResult(boolean success, String info) {
        String safeInfo = info != null ? info.replace("'", "\\'") : "";
        runJs("window.onAndroidDownloadResult && window.onAndroidDownloadResult(" + success + ", '" + safeInfo + "');");
    }

    private String safeMsg(Exception e) {
        return e.getMessage() != null ? e.getMessage() : "error";
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            pendingIntent = getIntent();

            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.RECORD_AUDIO}, MIC_PERMISSION_CODE);
            }

            tts = new TextToSpeech(this, status -> {
                if (status == TextToSpeech.SUCCESS && tts != null) {
                    tts.setLanguage(new Locale("fr", "FR"));
                }
            });

            if (getBridge() != null && getBridge().getWebView() != null) {
                getBridge().getWebView().addJavascriptInterface(new WebAppInterface(), "AndroidTTS");
                getBridge().getWebView().addJavascriptInterface(new WebAppInterface(), "AndroidSTT");
                getBridge().getWebView().addJavascriptInterface(new DownloadInterface(), "AndroidDownload");
                androidx.core.content.ContextCompat.registerReceiver(
                    this, downloadReceiver,
                    new android.content.IntentFilter(android.app.DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                    androidx.core.content.ContextCompat.RECEIVER_EXPORTED
                );
                getBridge().getWebView().postDelayed(() -> {
                    try { handleShareIntent(pendingIntent); }
                    catch (Exception e) { Log.e(TAG, "share intent error", e); }
                }, 1200);
            }

        } catch (Exception e) {
            Log.e(TAG, "onCreate error", e);
        }
    }

    private void runJs(String js) {
        if (getBridge() != null && getBridge().getWebView() != null) {
            getBridge().getWebView().post(() -> getBridge().getWebView().evaluateJavascript(js, null));
        }
    }

    private void startNativeListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            runJs("window.onAndroidSTTError && window.onAndroidSTTError('unavailable');");
            return;
        }
        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
            speechRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) {
                    runJs("window.onAndroidSTTStart && window.onAndroidSTTStart();");
                }
                @Override public void onResults(Bundle results) {
                    ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    String text = (matches != null && !matches.isEmpty()) ? matches.get(0) : "";
                    runJs("window.onAndroidSTTResult && window.onAndroidSTTResult('"
                            + text.replace("\\", "\\\\").replace("'", "\\'") + "');");
                }
                @Override public void onError(int error) {
                    runJs("window.onAndroidSTTError && window.onAndroidSTTError('" + error + "');");
                }
                @Override public void onEndOfSpeech() {}
                @Override public void onBeginningOfSpeech() {}
                @Override public void onRmsChanged(float rmsdB) {}
                @Override public void onBufferReceived(byte[] buffer) {}
                @Override public void onPartialResults(Bundle partialResults) {}
                @Override public void onEvent(int eventType, Bundle params) {}
            });
        }
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fr-FR");
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        try {
            speechRecognizer.startListening(intent);
        } catch (Exception e) {
            runJs("window.onAndroidSTTError && window.onAndroidSTTError('start_failed');");
        }
    }

    // Interception au plus bas niveau possible de la touche retour physique —
    // passe avant tout traitement interne de Capacitor/AndroidX, garantissant
    // qu'elle est toujours geree ici en premier.
    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent event) {
        if (event.getKeyCode() == android.view.KeyEvent.KEYCODE_BACK
                && event.getAction() == android.view.KeyEvent.ACTION_UP) {
            if (getBridge() != null && getBridge().getWebView() != null) {
                getBridge().getWebView().evaluateJavascript(
                    "(function(){ try { return !!(window.onAndroidBackPressed && window.onAndroidBackPressed()); } catch(e){ return false; } })();",
                    value -> {
                        boolean handledByJs = "\"true\"".equals(value);
                        if (!handledByJs) {
                            finish();
                        }
                    }
                );
            } else {
                finish();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        try {
            setIntent(intent);
            handleShareIntent(intent);
        } catch (Exception e) { Log.e(TAG, "onNewIntent error", e); }
    }

    @Override
    public void onDestroy() {
        if (tts != null) { tts.stop(); tts.shutdown(); }
        if (speechRecognizer != null) { speechRecognizer.destroy(); }
        downloadExecutor.shutdown();
        try { unregisterReceiver(downloadReceiver); } catch (Exception e) { /* deja desenregistre */ }
        super.onDestroy();
    }

    private void handleShareIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        String type = intent.getType();
        if (Intent.ACTION_SEND.equals(action) && type != null && type.startsWith("image/")) {
            Uri imageUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (imageUri != null) sendImageToWebView(imageUri, type);
        }
    }

    private void sendImageToWebView(Uri uri, String mimeType) {
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = is.read(chunk)) != -1) buffer.write(chunk, 0, n);
            is.close();
            String base64 = Base64.encodeToString(buffer.toByteArray(), Base64.NO_WRAP);
            String fileName = getFileName(uri);
            String js = "window.receiveSharedImage && window.receiveSharedImage('"
                    + base64 + "', '" + mimeType + "', '" + fileName.replace("'", "") + "');";
            runJs(js);
        } catch (Exception e) { Log.e(TAG, "sendImageToWebView error", e); }
    }

    private String getFileName(Uri uri) {
        String result = "image.jpg";
        Cursor cursor = getContentResolver().query(uri, null, null, null, null);
        if (cursor != null) {
            try {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx != -1 && cursor.moveToFirst()) result = cursor.getString(idx);
            } finally { cursor.close(); }
        }
        return result;
    }
}
