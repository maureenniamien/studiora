package com.maureen.studiora;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import androidx.activity.OnBackPressedCallback;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.getcapacitor.BridgeActivity;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Locale;

public class MainActivity extends BridgeActivity {

    private static final String TAG = "StudioraMain";
    private static final int MIC_PERMISSION_CODE = 2001;
    private Intent pendingIntent = null;
    private TextToSpeech tts;
    private SpeechRecognizer speechRecognizer;
    private OnBackPressedCallback backCallback;

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
                getBridge().getWebView().postDelayed(() -> {
                    try { handleShareIntent(pendingIntent); }
                    catch (Exception e) { Log.e(TAG, "share intent error", e); }
                }, 1200);
            }

            // API moderne : remplace l'ancien override onBackPressed(), plus fiable
            // sur Android 13+ (predictive back) et avec les activites AndroidX/Capacitor.
            backCallback = new OnBackPressedCallback(true) {
                @Override
                public void handleOnBackPressed() {
                    if (getBridge() != null && getBridge().getWebView() != null) {
                        getBridge().getWebView().evaluateJavascript(
                            "(function(){ try { return !!(window.onAndroidBackPressed && window.onAndroidBackPressed()); } catch(e){ return false; } })();",
                            value -> {
                                boolean handledByJs = "\"true\"".equals(value);
                                if (!handledByJs) {
                                    // Pas gere par le JS (on est a l'accueil) : on desactive
                                    // temporairement ce callback pour laisser le systeme fermer l'app.
                                    setEnabled(false);
                                    getOnBackPressedDispatcher().onBackPressed();
                                    setEnabled(true);
                                }
                            }
                        );
                    } else {
                        setEnabled(false);
                        getOnBackPressedDispatcher().onBackPressed();
                        setEnabled(true);
                    }
                }
            };
            getOnBackPressedDispatcher().addCallback(this, backCallback);

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
