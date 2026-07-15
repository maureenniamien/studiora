package com.maureen.studiora;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.speech.tts.TextToSpeech;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.getcapacitor.BridgeActivity;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Locale;

public class MainActivity extends BridgeActivity {

    private static final String TAG = "StudioraMain";
    private static final int MIC_PERMISSION_CODE = 2001;
    private Intent pendingIntent = null;
    private TextToSpeech tts;

    private class WebAppInterface {
        @JavascriptInterface
        public void speak(String text) {
            if (tts != null && text != null && !text.isEmpty()) {
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "studiora_tts");
            }
        }
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
                getBridge().getWebView().postDelayed(() -> {
                    try { handleShareIntent(pendingIntent); }
                    catch (Exception e) { Log.e(TAG, "share intent error", e); }
                }, 1200);
            }
        } catch (Exception e) {
            Log.e(TAG, "onCreate error", e);
        }
    }

    @Override
    public void onBackPressed() {
        if (getBridge() != null && getBridge().getWebView() != null) {
            getBridge().getWebView().evaluateJavascript(
                "(function(){ try { return !!(window.onAndroidBackPressed && window.onAndroidBackPressed()); } catch(e){ return false; } })();",
                value -> {
                    if (!"true".equals(value)) {
                        runOnUiThread(MainActivity.super::onBackPressed);
                    }
                }
            );
        } else {
            super.onBackPressed();
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
    protected void onDestroy() {
        if (tts != null) { tts.stop(); tts.shutdown(); }
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
            if (getBridge() != null && getBridge().getWebView() != null) {
                getBridge().getWebView().post(() -> getBridge().getWebView().evaluateJavascript(js, null));
            }
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
