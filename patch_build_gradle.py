#!/usr/bin/env python3
"""
Patch automatique de android/app/build.gradle pour ajouter la signature
avec la clé stable studiora-release.keystore (variables Codemagic).
Usage : python3 patch_build_gradle.py android/app/build.gradle
"""
import re
import sys

path = sys.argv[1] if len(sys.argv) > 1 else "android/app/build.gradle"

with open(path) as f:
    content = f.read()

backup_path = path + ".bak"
with open(backup_path, "w") as f:
    f.write(content)
print(f"Sauvegarde créée : {backup_path}")

SIGNING_BLOCK = '''    signingConfigs {
        release {
            storeFile file("studiora-release.keystore")
            storePassword System.getenv("CM_KEYSTORE_PASSWORD") ?: ""
            keyAlias System.getenv("CM_KEY_ALIAS") ?: ""
            keyPassword System.getenv("CM_KEY_PASSWORD") ?: ""
        }
    }
'''

# 1) Insère signingConfigs juste après "android {"
if "signingConfigs {" not in content:
    content = re.sub(
        r"(android\s*\{\s*\n)",
        r"\1" + SIGNING_BLOCK,
        content,
        count=1,
    )
    print("✓ Bloc signingConfigs ajouté")
else:
    print("… signingConfigs déjà présent, ignoré")

# 2) Trouve le bloc buildTypes { ... } (avec équilibrage d'accolades simplifié)
def find_block(text, start_idx):
    """Retourne (start, end) du bloc { ... } qui commence après start_idx (à la 1ère '{')."""
    brace_start = text.index("{", start_idx)
    depth = 0
    i = brace_start
    while i < len(text):
        if text[i] == "{":
            depth += 1
        elif text[i] == "}":
            depth -= 1
            if depth == 0:
                return brace_start, i
        i += 1
    return brace_start, len(text)

bt_match = re.search(r"buildTypes\s*", content)
if not bt_match:
    print("✗ ATTENTION : bloc buildTypes introuvable, signingConfig non lié automatiquement.")
    print("  Ajoute manuellement 'signingConfig signingConfigs.release' dans debug{} et release{}.")
else:
    bstart, bend = find_block(content, bt_match.end())
    build_types_content = content[bstart:bend+1]

    def ensure_signing(name, block_text):
        pattern = re.compile(name + r"\s*\{")
        m = pattern.search(block_text)
        if not m:
            # Le sous-bloc n'existe pas : on l'ajoute avant la fin de buildTypes
            insertion = f"\n        {name} {{\n            signingConfig signingConfigs.release\n        }}\n"
            return block_text[:-1] + insertion + "}", True
        sstart, send = find_block(block_text, m.end())
        inner = block_text[sstart+1:send]
        if "signingConfig" in inner:
            return block_text, False
        new_inner = "\n            signingConfig signingConfigs.release" + inner
        new_block_text = block_text[:sstart+1] + new_inner + block_text[send:]
        return new_block_text, True

    build_types_content, changed_debug = ensure_signing("debug", build_types_content)
    build_types_content, changed_release = ensure_signing("release", build_types_content)

    content = content[:bstart] + build_types_content + content[bend+1:]
    if changed_debug:
        print("✓ signingConfig ajouté au buildType debug")
    if changed_release:
        print("✓ signingConfig ajouté au buildType release")
    if not changed_debug and not changed_release:
        print("… signingConfig déjà présent dans debug/release, ignoré")

with open(path, "w") as f:
    f.write(content)

print("\n=== Terminé. Relis le fichier avant de builder : ===")
print(f"cat {path}")
