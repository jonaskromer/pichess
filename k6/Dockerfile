# Custom k6 image for the piChess perf stack.
#
# The base `grafana/k6` image bundles Chromium and the `k6/browser` module
# since 0.50, so the browser surface needs no extra build step.
#
# The kafka surface (xk6-kafka) requires a custom k6 binary built with the
# xk6 toolchain — added in a later iteration. When it lands, replace this
# base image with a two-stage build:
#
#     FROM grafana/xk6:latest AS build
#     RUN xk6 build --with github.com/mostafa/xk6-kafka@latest
#     FROM grafana/k6:0.55.0
#     COPY --from=build /xk6/k6 /usr/bin/k6
#
# For now we use the stock image — keeps `make k6-build` a no-op pull
# and makes the browser surface land without extension churn.

FROM grafana/k6:0.55.0

# k6 expects scripts at /scripts and writes summaries to /out. The compose
# profile bind-mounts ./k6/scripts:/scripts and ./perf-reports:/out.
WORKDIR /scripts

# Headless Chromium needs these for stable launch under low-memory hosts.
# K6_BROWSER_ARGS is read by k6/browser at chromium-launch time.
ENV K6_BROWSER_HEADLESS=true \
    K6_BROWSER_ARGS="no-sandbox,disable-dev-shm-usage"
