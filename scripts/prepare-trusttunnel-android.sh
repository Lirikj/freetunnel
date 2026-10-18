#!/usr/bin/env bash
set -euo pipefail
repo_dir="$(cd -- "$(dirname -- "$0")/.." && pwd)"
artifact="trusttunnel-client-android-1.1.7.aar"
expected="5398df61f74610d1d63819059bb596560feb51882e9ca78609df32fc4c7840f7"
destination="$repo_dir/android/app/libs/$artifact"
if [[ -f "$destination" ]] && printf '%s  %s\n' "$expected" "$destination" | shasum -a 256 -c - >/dev/null 2>&1; then
    printf 'Pinned TrustTunnel 1.1.7 already prepared.\n'
    exit 0
fi
temporary_dir="$(mktemp -d "${TMPDIR:-/tmp}/trusttunnel-android.XXXXXX")"
trap 'rm -rf "$temporary_dir"' EXIT
# The public package page supplies download links to the original artifacts.
# No PAT, old APK extraction, or alternate native core.
download_url="$(curl -fsSL --retry 2 --connect-timeout 15 --max-time 90 'https://github.com/TrustTunnel/TrustTunnelClient/packages/2733074?version=1.1.7' | python3 -c '
import html, re, sys
name = sys.argv[1]
links = [html.unescape(u) for u in re.findall(r"href=\"([^\"]+)\"", sys.stdin.read())]
url = next((u for u in links if "filename%3D" + name + "&" in u), None)
if not url:
    sys.exit("Official package asset missing")
print(url)
' "$artifact")"
curl -fsSL --retry 2 --connect-timeout 15 -o "$temporary_dir/$artifact" "$download_url"
printf '%s  %s\n' "$expected" "$temporary_dir/$artifact" | shasum -a 256 -c -
mkdir -p "$(dirname "$destination")"
mv "$temporary_dir/$artifact" "$destination"
printf 'Prepared official TrustTunnel 1.1.7.\n'
