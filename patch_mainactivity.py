#!/usr/bin/env python3
"""
Patch MainActivity.java pour activer les boutons d'import de fichiers
(input type=file) dans le WebView Capacitor.
Ajoute : import ValueCallback, champs de callback, onShowFileChooser(),
onActivityResult().
Chaque ancrage est vérifié EXACTEMENT une fois avant modification.
Si un ancrage manque ou est dupliqué, le script s'arrête sans rien changer.
"""
import sys

PATH = "android/app/src/main/java/com/maureen/studiora/MainActivity.java"

with open(PATH, "r", encoding="utf-8") as f:
    content = f.read()

original = content

def apply_patch(content, anchor, insertion, where="after", label=""):
    count = content.count(anchor)
    if count != 1:
        print(f"❌ ARRÊT : ancrage '{label}' trouvé {count} fois (attendu 1). Aucune modification appliquée.")
        sys.exit(1)
    if where == "after":
        return content.replace(anchor, anchor + insertion, 1)
    else:
        return content.replace(anchor, insertion + anchor, 1)

# 1) Import ValueCallback
content = apply_patch(
    content,
    "import android.webkit.WebChromeClient;",
    "\nimport android.webkit.ValueCallback;",
    "after",
    "import WebChromeClient"
)

# 2) Champs de classe + onActivityResult, insérés juste avant handleShareIntent
fields_and_result = """
    // CORRECTIF : gère le retour du sélecteur de fichiers natif ouvert par onShowFileChooser
    private ValueCallback<Uri[]> mFilePathCallback;
    private static final int FILE_CHOOSER_RESULT_CODE = 10001;

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_RESULT_CODE) {
            if (mFilePathCallback == null) {
                super.onActivityResult(requestCode, resultCode, data);
                return;
            }
            Uri[] results = null;
            try {
                if (resultCode == RESULT_OK && data != null) {
                    if (data.getClipData() != null) {
                        int count = data.getClipData().getItemCount();
                        results = new Uri[count];
                        for (int i = 0; i < count; i++) {
                            results[i] = data.getClipData().getItemAt(i).getUri();
                        }
                    } else if (data.getData() != null) {
                        results = new Uri[]{ data.getData() };
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "onActivityResult file chooser error", e);
            }
            mFilePathCallback.onReceiveValue(results);
            mFilePathCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

"""
content = apply_patch(
    content,
    "private void handleShareIntent(Intent intent) {",
    fields_and_result,
    "before",
    "handleShareIntent (pour champs + onActivityResult)"
)

# 3) onShowFileChooser à l'intérieur du WebChromeClient existant
show_file_chooser = """
                    @Override
                    public boolean onShowFileChooser(WebView webView, ValueCallback<Uri[]> filePathCallback, FileChooserParams fileChooserParams) {
                        if (mFilePathCallback != null) {
                            mFilePathCallback.onReceiveValue(null);
                        }
                        mFilePathCallback = filePathCallback;
                        try {
                            Intent intent = fileChooserParams.createIntent();
                            intent.addCategory(Intent.CATEGORY_OPENABLE);
                            startActivityForResult(intent, FILE_CHOOSER_RESULT_CODE);
                        } catch (Exception e) {
                            Log.e(TAG, "onShowFileChooser error", e);
                            mFilePathCallback = null;
                            return false;
                        }
                        return true;
                    }

"""
content = apply_patch(
    content,
    "getBridge().getWebView().setWebChromeClient(new WebChromeClient() {",
    show_file_chooser,
    "after",
    "setWebChromeClient (pour onShowFileChooser)"
)

with open(PATH, "w", encoding="utf-8") as f:
    f.write(content)

print("✅ Patch appliqué avec succès sur", PATH)
print(f"   Taille avant: {len(original)} caractères -> après: {len(content)} caractères")
