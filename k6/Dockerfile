# Custom k6 image for the piChess perf stack.
#
# Two-stage build:
#   1. `xk6` stage builds a k6 binary with xk6-kafka linked in — the
#      stock binary has no Kafka client, and we need one for the kafka
#      surface to write directly onto `chess.game-events`.
#   2. `grafana/k6:0.55.0-with-browser` provides Chromium for the
#      browser surface. We swap its bundled k6 binary for the
#      xk6-kafka build, keeping both capabilities in one image.
#
# The base k6 version is pinned in both stages so the binary and the
# image's chromium / wrappers match.

ARG K6_VERSION=0.55.0

FROM grafana/xk6:1.0.1 AS xk6
ARG K6_VERSION
ARG TARGETARCH
# xk6 build emits the k6 binary to --output. Pin xk6-kafka to a
# version known to compile against k6 0.55.x; bump in lockstep with
# K6_VERSION if you upgrade k6. TARGETARCH lets buildkit pass through
# the host architecture (amd64 / arm64) without a hardcode in here.
RUN xk6 build \
      --k6-version v${K6_VERSION} \
      --arch ${TARGETARCH:-arm64} \
      --with github.com/mostafa/xk6-kafka@v0.27.0 \
      --output /xk6/k6


FROM grafana/k6:0.55.0-with-browser

# Replace the stock binary with the xk6-built one. /usr/bin/k6 is the
# location the image's entrypoint resolves; both binaries are static
# Go executables, so the substitution is transparent.
COPY --from=xk6 /xk6/k6 /usr/bin/k6

# k6 expects scripts at /scripts and writes summaries to /out. The compose
# profile bind-mounts ./k6/scripts:/scripts, ./k6/lib:/k6lib,
# ./proto/src/main/protobuf:/proto, and ./perf-reports:/out.
WORKDIR /scripts

# Point k6/browser at the *actual* Chromium binary inside the image.
#
# The Alpine chromium package installs three things:
#   /usr/bin/chromium                    → symlink to chromium-browser
#   /usr/bin/chromium-browser            → symlink to the launcher.sh
#   /usr/lib/chromium/chromium-launcher.sh   → shell script
#   /usr/lib/chromium/chromium                → the real ELF binary
#
# k6/browser's executable check fails for both the symlink AND the
# launcher script — it wants the real binary. Point it directly at
# /usr/lib/chromium/chromium.
#
# Headless mode + no-sandbox + disable-dev-shm-usage are needed for
# stable launch in a small Linux container under Docker Desktop on
# macOS. K6_BROWSER_ARGS is read at chromium-launch time.
ENV K6_BROWSER_EXECUTABLE_PATH=/usr/lib/chromium/chromium \
    K6_BROWSER_HEADLESS=true \
    K6_BROWSER_ARGS="no-sandbox,disable-dev-shm-usage"
