#!/bin/sh
# Installs Gantry for the current user, with its own Java runtime, so nothing else needs to be installed first.
#
#   curl -fsSL https://raw.githubusercontent.com/sol-foundry/gantry/main/install.sh | sh
#
# Re-run it to update. Uninstall with `sh install.sh --uninstall` (your apps and their data are kept).
#
# Settings (environment variables):
#   GANTRY_VERSION       version to install, e.g. 0.1.0 (default: the latest release)
#   GANTRY_INSTALL_DIR   where to install (default: ~/.gantry, next to your apps)
#   GANTRY_JAVA_HOME     use this Java (17 or newer) instead of downloading one
#   GANTRY_DIST_FILE     install from a local release archive instead of downloading it
#   GANTRY_NO_MODIFY_PATH=1   don't add Gantry to PATH in your shell's startup file
set -eu

REPO="sol-foundry/gantry"
JAVA_FEATURE=17
DIR="${GANTRY_INSTALL_DIR:-$HOME/.gantry}"
PATH_MARKER="# Added by Gantry's install.sh"

say() { printf '%s\n' "$*"; }
fail() {
    printf 'gantry install: %s\n' "$*" >&2
    exit 1
}

need() { command -v "$1" >/dev/null 2>&1 || fail "needs '$1'; install it and run this again"; }

sha256() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum "$1" | cut -d' ' -f1
    elif command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | cut -d' ' -f1
    else
        fail "needs sha256sum or shasum to check downloads"
    fi
}

# Checks $1 against the hex digest $2, or stops.
verify() {
    actual=$(sha256 "$1")
    [ "$actual" = "$2" ] || fail "checksum mismatch for $(basename "$1") (expected $2, got $actual); try again"
}

download() { curl -fSL --retry 3 --progress-bar -o "$2" "$1" || fail "couldn't download $1"; }

# The shell startup file to put Gantry on PATH in, and the line to add there.
rc_file() {
    case "$(basename "${SHELL:-sh}")" in
        zsh) echo "${ZDOTDIR:-$HOME}/.zshrc" ;;
        bash) if [ "$(uname -s)" = Darwin ]; then echo "$HOME/.bash_profile"; else echo "$HOME/.bashrc"; fi ;;
        fish) echo "$HOME/.config/fish/conf.d/gantry.fish" ;;
        *) echo "$HOME/.profile" ;;
    esac
}

path_line() {
    case "$1" in
        *.fish) echo "fish_add_path \"$DIR/bin\"" ;;
        *) echo "export PATH=\"$DIR/bin:\$PATH\"" ;;
    esac
}

uninstall() {
    rm -rf "$DIR/bin" "$DIR/dist" "$DIR/runtime"
    rc=$(rc_file)
    if [ -f "$rc" ] && grep -qF "$PATH_MARKER" "$rc"; then
        # Drop the marker and the line after it.
        awk -v m="$PATH_MARKER" 'skip { skip = 0; next } $0 == m { skip = 1; next } { print }' "$rc" >"$rc.gantry-tmp" && mv "$rc.gantry-tmp" "$rc"
        say "Removed Gantry from PATH in $rc."
    fi
    say "Gantry is uninstalled. Your apps and their data are still in $DIR/apps and Docker; remove them with"
    say "\`gantry destroy <app>\` before uninstalling, or delete $DIR yourself."
}

if [ "${1:-}" = "--uninstall" ]; then
    uninstall
    exit 0
fi

case "$(uname -s)" in
    Darwin) os=mac ;;
    Linux) os=linux ;;
    *) fail "this script supports macOS and Linux; on Windows, see the README" ;;
esac
case "$(uname -m)" in
    arm64 | aarch64) arch=aarch64 ;;
    x86_64 | amd64) arch=x64 ;;
    *) fail "unsupported CPU architecture $(uname -m)" ;;
esac
need curl
need tar

tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT INT TERM

# --- Gantry ---------------------------------------------------------------------------------------------------
if [ -n "${GANTRY_DIST_FILE:-}" ]; then
    archive="$GANTRY_DIST_FILE"
    [ -f "$archive" ] || fail "GANTRY_DIST_FILE $archive doesn't exist"
    version=$(basename "$archive" | sed -n 's/^gantry-\(.*\)\.tar\.gz$/\1/p')
    [ -n "$version" ] || fail "GANTRY_DIST_FILE must be named gantry-<version>.tar.gz"
else
    version="${GANTRY_VERSION:-}"
    if [ -z "$version" ]; then
        # github.com redirects releases/latest to the latest tag (no API rate limit, unlike api.github.com).
        latest=$(curl -fsSLI -o /dev/null -w '%{url_effective}' "https://github.com/$REPO/releases/latest") || true
        version=$(printf '%s' "${latest:-}" | sed -n 's|.*/tag/v\{0,1\}\([^/]*\)$|\1|p')
        [ -n "$version" ] || fail "couldn't find the latest release of $REPO; set GANTRY_VERSION"
    fi
    url="https://github.com/$REPO/releases/download/v$version/gantry-$version.tar.gz"
    say "Downloading Gantry $version"
    archive="$tmp/gantry-$version.tar.gz"
    download "$url" "$archive"
    download "$url.sha256" "$tmp/gantry.sha256"
    verify "$archive" "$(cut -d' ' -f1 <"$tmp/gantry.sha256")"
fi

mkdir -p "$DIR/dist" "$DIR/bin"
tar -xzf "$archive" -C "$tmp"
[ -x "$tmp/gantry-$version/bin/gantry" ] || fail "the archive doesn't contain gantry-$version/bin/gantry"
rm -rf "$DIR/dist/gantry-$version"
mv "$tmp/gantry-$version" "$DIR/dist/gantry-$version"
ln -sfn "gantry-$version" "$DIR/dist/current"
# Earlier versions are no longer used.
for old in "$DIR"/dist/gantry-*; do
    [ "$old" = "$DIR/dist/gantry-$version" ] || rm -rf "$old"
done

# --- Java -----------------------------------------------------------------------------------------------------
# Gantry gets a runtime of its own: the developer's Java may be missing, too old, or moved by a later upgrade.
if [ -n "${GANTRY_JAVA_HOME:-}" ]; then
    [ -x "$GANTRY_JAVA_HOME/bin/java" ] || fail "GANTRY_JAVA_HOME has no bin/java: $GANTRY_JAVA_HOME"
    java_home="$GANTRY_JAVA_HOME"
else
    api="https://api.adoptium.net/v3/assets/latest/$JAVA_FEATURE/hotspot?os=$os&architecture=$arch&image_type=jre&vendor=eclipse"
    curl -fsSL "$api" -o "$tmp/java.json" || fail "couldn't look up a Java $JAVA_FEATURE runtime for $os/$arch"
    # The archive's link and checksum sit in its "package" object; the macOS .pkg installer has its own, so read
    # them from that object only (it has no nested objects, so it ends at the first "}").
    json=$(tr -d '\n' <"$tmp/java.json")
    package=$(printf '%s' "$json" | sed -n 's/.*"package": *{\([^}]*\)}.*/\1/p')
    release=$(printf '%s' "$json" | sed -n 's/.*"release_name": *"\([^"]*\)".*/\1/p')
    link=$(printf '%s' "$package" | sed -n 's/.*"link": *"\([^"]*\)".*/\1/p')
    checksum=$(printf '%s' "$package" | sed -n 's/.*"checksum": *"\([0-9a-f]\{64\}\)".*/\1/p')
    [ -n "$release" ] && [ -n "$link" ] && [ -n "$checksum" ] || fail "couldn't read the Java runtime download details"
    if [ ! -d "$DIR/runtime/$release" ]; then
        say "Downloading Java runtime $release (used only by Gantry)"
        download "$link" "$tmp/java.tar.gz"
        verify "$tmp/java.tar.gz" "$checksum"
        mkdir -p "$tmp/java" "$DIR/runtime"
        tar -xzf "$tmp/java.tar.gz" -C "$tmp/java"
        rm -rf "$DIR/runtime/$release"
        mv "$tmp/java/"* "$DIR/runtime/$release"
    fi
    ln -sfn "$release" "$DIR/runtime/current"
    for old in "$DIR"/runtime/*; do
        case "$(basename "$old")" in "$release" | current) ;; *) rm -rf "$old" ;; esac
    done
    # macOS runtimes are bundles with the Java home inside.
    if [ -d "$DIR/runtime/current/Contents/Home" ]; then
        java_home="$DIR/runtime/current/Contents/Home"
    else
        java_home="$DIR/runtime/current"
    fi
fi

# --- The gantry command ---------------------------------------------------------------------------------------
# Stable paths only (the `current` links), so agents connected with `gantry mcp install` survive updates.
cat >"$DIR/bin/gantry" <<EOF
#!/bin/sh
# Generated by Gantry's install.sh; re-run it to update.
JAVA_HOME="$java_home"
GANTRY_LAUNCHER="$DIR/bin/gantry"
export JAVA_HOME GANTRY_LAUNCHER
exec "$DIR/dist/current/bin/gantry" "\$@"
EOF
chmod +x "$DIR/bin/gantry"
"$DIR/bin/gantry" services >/dev/null || fail "the installed gantry didn't run; see the error above"

say ""
say "Gantry $version is installed: $DIR/bin/gantry"

case ":$PATH:" in
    *":$DIR/bin:"*) ;;
    *)
        if [ "${GANTRY_NO_MODIFY_PATH:-}" = "1" ]; then
            say "Add $DIR/bin to your PATH to run \`gantry\`."
        else
            rc=$(rc_file)
            mkdir -p "$(dirname "$rc")"
            if ! { [ -f "$rc" ] && grep -qF "$PATH_MARKER" "$rc"; }; then
                printf '\n%s\n%s\n' "$PATH_MARKER" "$(path_line "$rc")" >>"$rc"
            fi
            say "Added $DIR/bin to PATH in $rc; open a new terminal (or run: $(path_line "$rc")) to use \`gantry\`."
        fi
        ;;
esac

command -v docker >/dev/null 2>&1 || say "Note: Gantry runs your services in Docker, which isn't on PATH here; install Docker Desktop or Colima."
say "Get started: gantry create my-app --services postgres valkey elasticmq && gantry start my-app && gantry dashboard"
