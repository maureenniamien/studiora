package com.maureen.studiora;

import android.Manifest;
import androidx.activity.OnBackPressedCallback;
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
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewClientCompat;
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
    private static final int STORAGE_PERMISSION_CODE = 2002;
    private Intent pendingIntent = null;
    private TextToSpeech tts;
    private SpeechRecognizer speechRecognizer;
    private Handler sttTimeoutHandler;
    private Runnable sttTimeoutRunnable;
    private static final long STT_TIMEOUT_MS = 12000;
    private final ExecutorService downloadExecutor = Executors.newSingleThreadExecutor();

    private static final String LIVE_INDEX_URL = "https://maureenniamien.github.io/studiora/index.html";
    // Domaine virtuel standard recommande par Google pour WebViewAssetLoader.
    // Ne correspond a aucun vrai serveur : sert uniquement de "nom d'origine"
    // pour que le WebView traite les fichiers locaux comme une vraie page web,
    // sans passer par file:// (bloque par le sandbox du renderer sur Android 10+).
    private static final String ASSET_DOMAIN = "appassets.androidplatform.net";

    private class WebAppInterface {
        @JavascriptInterface
        public void speak(String text) {
            if (tts != null && text != null && !text.isEmpty()) {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "studiora_tts");
            }
        }

        @JavascriptInterface
        public void stop() {
            new Handler(Looper.getMainLooper()).post(() -> {
                if (tts != null) tts.stop();
            });
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
        public void saveFile(String reqId, String base64Data, String filename, String mimeType) {
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    byte[] bytes = Base64.decode(base64Data, Base64.NO_WRAP);
                    writeBytesToDownloads(reqId, bytes, filename, mimeType);
                } catch (Exception e) {
                    Log.e(TAG, "saveFile error", e);
                    notifyDownloadResult(reqId, false, safeMsg(e));
                }
            });
        }

        @JavascriptInterface
        public void saveFileFromUrl(String reqId, String url, String filename, String mimeType) {
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
                            writeBytesToDownloads(reqId, bytes, filename, mimeType);
                        } catch (Exception e) {
                            Log.e(TAG, "saveFileFromUrl write error", e);
                            notifyDownloadResult(reqId, false, safeMsg(e));
                        }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "saveFileFromUrl download error", e);
                    final String msg = safeMsg(e);
                    new Handler(Looper.getMainLooper()).post(() -> notifyDownloadResult(reqId, false, msg));
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

    private void writeBytesToDownloads(String reqId, byte[] bytes, String filename, String mimeType) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
                values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
                values.put(MediaStore.Downloads.IS_PENDING, 1);
                Uri collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI;
                Uri item = getContentResolver().insert(collection, values);
                if (item == null) {
                    notifyDownloadResult(reqId, false, "insert_failed");
                    return;
                }
                OutputStream out = getContentResolver().openOutputStream(item);
                if (out != null) { out.write(bytes); out.close(); }
                values.clear();
                values.put(MediaStore.Downloads.IS_PENDING, 0);
                getContentResolver().update(item, values, null, null);
            } else {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
                    notifyDownloadResult(reqId, false, "permission_stockage_refusee");
                    return;
                }
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) dir.mkdirs();
                File file = new File(dir, filename);
                FileOutputStream fos = new FileOutputStream(file);
                fos.write(bytes);
                fos.close();
            }
            notifyDownloadResult(reqId, true, filename);
        } catch (Exception e) {
            Log.e(TAG, "writeBytesToDownloads error", e);
            notifyDownloadResult(reqId, false, safeMsg(e));
        }
    }

    private void notifyDownloadResult(String reqId, boolean success, String info) {
        String safeReqId = reqId != null ? reqId.replace("'", "\\'") : "";
        String safeInfo = info != null ? info.replace("'", "\\'") : "";
        runJs("window.onAndroidDownloadResult && window.onAndroidDownloadResult('"
                + safeReqId + "', " + success + ", '" + safeInfo + "');");
    }

    private String safeMsg(Exception e) {
        return e.getMessage() != null ? e.getMessage() : "error";
    }

    private void ensureLocalCopyFromAssets() {
        File dir = new File(getFilesDir(), "www-live");
        File index = new File(dir, "index.html");
        if (index.exists()) return;
        try {
            if (!dir.exists()) dir.mkdirs();
            InputStream is = getAssets().open("public/index.html");
            FileOutputStream fos = new FileOutputStream(index);
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
            is.close();
            fos.close();
            Log.i(TAG, "Copie initiale des assets embarques vers le stockage modifiable");
        } catch (Exception e) {
            Log.e(TAG, "ensureLocalCopyFromAssets error", e);
        }
    }

    private boolean isNetworkAvailable() {
        try {
            android.net.ConnectivityManager cm =
                (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) {
            return false;
        }
    }

    private void silentlyRefreshLiveCopy() {
        if (!isNetworkAvailable()) return;
        downloadExecutor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                URL u = new URL(LIVE_INDEX_URL + "?t=" + System.currentTimeMillis());
                conn = (HttpURLConnection) u.openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(20000);
                conn.setRequestMethod("GET");
                conn.connect();
                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) throw new Exception("HTTP " + code);

                InputStream is = conn.getInputStream();
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int n;
                while ((n = is.read(chunk)) != -1) buffer.write(chunk, 0, n);
                is.close();
                byte[] bytes = buffer.toByteArray();

                if (bytes.length < 1024) {
                    Log.w(TAG, "Reponse suspecte (" + bytes.length + " octets), mise a jour ignoree");
                    return;
                }

                File dir = new File(getFilesDir(), "www-live");
                if (!dir.exists()) dir.mkdirs();
                File tmp = new File(dir, "index.html.tmp");
                FileOutputStream fos = new FileOutputStream(tmp);
                fos.write(bytes);
                fos.close();

                File index = new File(dir, "index.html");
                if (!tmp.renameTo(index)) {
                    Log.w(TAG, "Echec du remplacement atomique de index.html");
                } else {
                    Log.i(TAG, "Copie locale mise a jour depuis GitHub Pages (" + bytes.length + " octets)");
                }
            } catch (Exception e) {
                Log.w(TAG, "silentlyRefreshLiveCopy: " + safeMsg(e));
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
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

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, STORAGE_PERMISSION_CODE);
            }

            tts = new TextToSpeech(this, status -> {
                if (status == TextToSpeech.SUCCESS && tts != null) {
                    tts.setLanguage(new Locale("fr", "FR"));
                }
            });

            getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
                @Override
                public void handleOnBackPressed() {
                    handleBack();
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

                // --- Copie locale modifiable + auto-mise a jour silencieuse (Option 2) ---
                ensureLocalCopyFromAssets();

                // CORRECTIF : file:// vers /data/user/0/.../files/ est bloque par le
                // sandbox du processus renderer du WebView sur Android 10+ (ERR_ACCESS_DENIED).
                // On sert donc les fichiers via WebViewAssetLoader, qui les expose comme
                // une origine web normale (https://appassets.androidplatform.net/live/...)
                // sans jamais passer par file://.
                File liveDir = new File(getFilesDir(), "www-live");
                WebViewAssetLoader assetLoader = new WebViewAssetLoader.Builder()
                        .setDomain(ASSET_DOMAIN)
                        .addPathHandler("/live/", new WebViewAssetLoader.InternalStoragePathHandler(this, liveDir))
                        .build();

                getBridge().getWebView().setWebViewClient(new WebViewClientCompat() {
                    @Override
                    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                        return assetLoader.shouldInterceptRequest(request.getUrl());
                    }
                });

                File liveIndex = new File(liveDir, "index.html");
                if (liveIndex.exists()) {
                    getBridge().getWebView().loadUrl("https://" + ASSET_DOMAIN + "/live/index.html");
                }
                silentlyRefreshLiveCopy();
                // --- fin ---

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
                    cancelSttTimeout();
                    ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    String text = (matches != null && !matches.isEmpty()) ? matches.get(0) : "";
                    runJs("window.onAndroidSTTResult && window.onAndroidSTTResult('"
                        + text.replace("\\", "\\\\").replace("'", "\\'") + "');");
                }
                @Override public void onError(int error) {
                    cancelSttTimeout();
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
            armSttTimeout();
        } catch (Exception e) {
            runJs("window.onAndroidSTTError && window.onAndroidSTTError('start_failed');");
        }
    }

    private void armSttTimeout() {
        cancelSttTimeout();
        sttTimeoutHandler = new Handler(Looper.getMainLooper());
        sttTimeoutRunnable = () -> {
            if (speechRecognizer != null) {
                try { speechRecognizer.cancel(); } catch (Exception ignored) {}
            }
            runJs("window.onAndroidSTTError && window.onAndroidSTTError('timeout');");
        };
        sttTimeoutHandler.postDelayed(sttTimeoutRunnable, STT_TIMEOUT_MS);
    }

    private void cancelSttTimeout() {
        if (sttTimeoutHandler != null && sttTimeoutRunnable != null) {
            sttTimeoutHandler.removeCallbacks(sttTimeoutRunnable);
        }
    }

    private void handleBack() {
        if (getBridge() != null && getBridge().getWebView() != null) {
            getBridge().getWebView().evaluateJavascript(
                "(function() { try { return !!(window.onAndroidBackPressed && window.onAndroidBackPressed()); } catch(e){ return false; } })();",
                value -> {
                    boolean handledByJs = "true".equals(value);
                    if (!handledByJs) {
                        finish();
                    }
                }
            );
        } else {
            finish();
        }
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
                + base64 + "', '" + mimeType + "', '" + fileName.replace("'", "\\'") + "');";
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
