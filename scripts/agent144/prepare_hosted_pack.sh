#!/usr/bin/env bash
set -euo pipefail
: "${EXACT_PACK_TAG:?}" "${EXACT_PACK_ASSET:?}" "${EXACT_PACK_SHA256:?}" "${MCEF_JAVA_CEF_COMMIT:?}" "${MCEF_PLATFORM:?}"
sudo apt-get update -qq
sudo apt-get install -y --no-install-recommends xvfb mesa-utils libgl1-mesa-dri libglx-mesa0 libgtk-3-0 libnss3 libnspr4 libxss1 libasound2 libatk1.0-0 libatk-bridge2.0-0 libdrm2 libgbm1 libxkbcommon0 libxcomposite1 libxdamage1 libxrandr2 libxfixes3 libx11-xcb1 libxshmfence1 libcups2 libatspi2.0-0 libpango-1.0-0 libpangocairo-1.0-0 fonts-liberation unzip
Xvfb "$DISPLAY" -screen 0 1280x720x24 -nolisten tcp > xvfb.log 2>&1 &
for _ in $(seq 1 20); do if glxinfo -B > glxinfo.txt 2>&1; then break; fi; sleep .25; done
grep -qi llvmpipe glxinfo.txt
mkdir -p .exact-pack-cache .mcef-jcef
gh release download "$EXACT_PACK_TAG" --repo "$GITHUB_REPOSITORY" --pattern "$EXACT_PACK_ASSET" --dir .exact-pack-cache --clobber
test "$(sha256sum .exact-pack-cache/$EXACT_PACK_ASSET | awk '{print $1}')" = "$EXACT_PACK_SHA256"
base="https://mcef-download.cinemamod.com/java-cef-builds/$MCEF_JAVA_CEF_COMMIT"
curl --fail --location --retry 5 --retry-all-errors "$base/${MCEF_PLATFORM}.tar.gz.sha256" -o ".mcef-jcef/${MCEF_PLATFORM}.tar.gz.sha256"
curl --fail --location --retry 5 --retry-all-errors "$base/${MCEF_PLATFORM}.tar.gz" -o ".mcef-jcef/${MCEF_PLATFORM}.tar.gz"
expected=$(awk 'NR==1 {print $1}' ".mcef-jcef/${MCEF_PLATFORM}.tar.gz.sha256")
test "$(sha256sum ".mcef-jcef/${MCEF_PLATFORM}.tar.gz" | awk '{print $1}')" = "$expected"
tar -xzf ".mcef-jcef/${MCEF_PLATFORM}.tar.gz" -C .mcef-jcef
rm ".mcef-jcef/${MCEF_PLATFORM}.tar.gz"
rm -rf build/mcef-libraries .mcef-mirror .exact-pack-run run-pack-benchmark
mkdir -p build/mcef-libraries
cp -a .mcef-jcef/. build/mcef-libraries/
chmod +x "build/mcef-libraries/$MCEF_PLATFORM/jcef_helper"
mirror_root="$PWD/.mcef-mirror"
mirror_dir="$mirror_root/java-cef-builds/$MCEF_JAVA_CEF_COMMIT"
mkdir -p "$mirror_dir"
cp ".mcef-jcef/${MCEF_PLATFORM}.tar.gz.sha256" "$mirror_dir/${MCEF_PLATFORM}.tar.gz.sha256"
echo "BOOTOPTIM_MCEF_MIRROR_ROOT=$mirror_root" >> "$GITHUB_ENV"
python scripts/exact-pack/prepare_fixture.py --zip ".exact-pack-cache/$EXACT_PACK_ASSET" --sha256 "$EXACT_PACK_SHA256" --extract .exact-pack-run --mcef-mirror-url "http://127.0.0.1:$BOOTOPTIM_MCEF_MIRROR_PORT"
