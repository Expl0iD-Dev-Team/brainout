#!/usr/bin/env bash
# One-time setup of APK release signing. Run it yourself from Git Bash:
#
#   bash scripts/setup-release-signing.sh
#
# It creates android/brainout-release.jks and android/keystore.properties (both
# git-ignored) with a random password, then stores the key and the password in
# the repository's GitHub Actions secrets. The password is never printed.
#
# Back up android/brainout-release.jks and android/keystore.properties somewhere
# private: GitHub secrets can't be read back, and a lost key means every player
# has to uninstall the game (and its local data) to get the next update.
set -euo pipefail

REPO="${REPO:-Expl0iD-Dev-Team/brainout}"
cd "$(dirname "$0")/../android"

KEYTOOL="keytool"
if [ -n "${JAVA_HOME:-}" ]; then
    KEYTOOL="$JAVA_HOME/bin/keytool"
fi

if [ -e brainout-release.jks ] || [ -e keystore.properties ]; then
    echo "android/brainout-release.jks or keystore.properties already exists; not touching them." >&2
    exit 1
fi

PASSWORD="$(head -c 32 /dev/urandom | base64 | tr -d '/+=\n')"

"$KEYTOOL" -genkeypair \
    -keystore brainout-release.jks -storetype PKCS12 \
    -alias brainout -keyalg RSA -keysize 4096 -validity 10000 \
    -storepass "$PASSWORD" -keypass "$PASSWORD" \
    -dname "CN=BrainOut"

umask 077
printf 'storeFile=brainout-release.jks\nstorePassword=%s\nkeyAlias=brainout\nkeyPassword=%s\n' \
    "$PASSWORD" "$PASSWORD" > keystore.properties

base64 -w0 brainout-release.jks | gh secret set ANDROID_KEYSTORE_BASE64 --repo "$REPO"
printf '%s' "$PASSWORD" | gh secret set ANDROID_KEYSTORE_PASSWORD --repo "$REPO"

echo
echo "Done. Secrets now on $REPO:"
gh secret list --repo "$REPO"
echo
echo "Back up these two files somewhere private:"
echo "  $(pwd)/brainout-release.jks"
echo "  $(pwd)/keystore.properties"
