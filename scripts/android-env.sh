#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"

if [[ -d "$root/.tools/jdk" ]]; then
    export JAVA_HOME="$root/.tools/jdk"
elif [[ -z "${JAVA_HOME:-}" ]]; then
    for candidate in \
        /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
        /usr/local/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
        /usr/lib/jvm/java-17-openjdk-amd64 \
        /usr/lib/jvm/java-17-openjdk-arm64; do
        if [[ -x "$candidate/bin/java" ]]; then
            export JAVA_HOME="$candidate"
            break
        fi
    done
    if [[ -z "${JAVA_HOME:-}" && -x /usr/libexec/java_home ]]; then
        JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
        export JAVA_HOME
    fi
fi

if [[ -d "$root/.tools/android-sdk" ]]; then
    export ANDROID_HOME="$root/.tools/android-sdk"
elif [[ -z "${ANDROID_HOME:-}" ]]; then
    if [[ "$(uname -s)" == Darwin ]]; then
        export ANDROID_HOME="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
    else
        export ANDROID_HOME="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
    fi
fi

export ANDROID_SDK_ROOT="$ANDROID_HOME"

export PATH="${JAVA_HOME:+$JAVA_HOME/bin:}$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

for tools in \
    /opt/homebrew/share/android-commandlinetools/cmdline-tools/latest/bin \
    /usr/local/share/android-commandlinetools/cmdline-tools/latest/bin; do
    if [[ -d "$tools" ]]; then
        export PATH="$PATH:$tools"
    fi
done

exec "$@"
