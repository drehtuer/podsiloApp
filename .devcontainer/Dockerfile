# SPDX-License-Identifier: GPL-3.0-or-later
#
# Podsilo development container.
#
# Deliberately built from plain `ubuntu:24.04` with distro packages only — no
# vendor-specific base image, no devcontainer "features", no third-party apt
# repositories. The only thing fetched from outside Ubuntu's archive is Google's
# Android command-line tools, which have no distro package.
#
# The Android SDK itself is NOT baked into this image. `post-create.sh` installs
# the SDK components into $ANDROID_HOME, which devcontainer.json backs with a
# named volume so a rebuild does not re-download gigabytes.

FROM ubuntu:24.04

# Host UID/GID so bind-mounted workspace files are not root-owned, and the GIDs
# owning /dev/kvm and /var/run/docker.sock on the host.
#
# All four are **best-effort defaults, not requirements** — they are the values on
# the machine this was first built on. Do not edit them for a new host: the real
# IDs are only knowable at container start, so post-create.sh re-chowns the
# persisted directories and re-aligns the kvm/docker groups to whatever the host
# actually uses. That keeps one image working across machines with different IDs
# (this project has already moved between a uid-1000/docker-109 host and a
# uid-1002/docker-108 one).
ARG USERNAME=dev
ARG USER_UID=1000
ARG USER_GID=1000
ARG KVM_GID=993
# NOT the 999 that Ubuntu's docker.io package picks inside the image, which is
# exactly why the user setup below groupmods it.
ARG DOCKER_GID=109

# Android command-line tools build number. Bump deliberately; keep it in sync
# with the platform-tools/adb version documented for Tier 3 (adb over TCP to a
# Windows-hosted emulator) — mismatched adb versions fail the handshake in
# confusing ways.
ARG CMDLINE_TOOLS_BUILD=13114758

ENV DEBIAN_FRONTEND=noninteractive

# C.UTF-8 rather than a generated locale: it is built into glibc, needs no
# `locales` package, and the naming/sanitisation tests depend on a UTF-8
# default charset (umlauts, CJK, NFC normalisation).
ENV LANG=C.UTF-8 \
    LC_ALL=C.UTF-8

# --- Toolchain and everyday CLI ------------------------------------------------
# openjdk-17-jdk (not -headless): AGP crunches PNGs through java.awt/ImageIO.
#
# python3 is here for tooling around the build, never for the app: this image had
# no scripting language beyond perl and awk, so checking a YAML file (there are
# five: the CI workflow, dependabot.yml, the compose file, detekt's config and
# .yamllint.yml itself) meant eyeballing indentation. python3-yaml parses them;
# yamllint is the ready-made check, configured by .yamllint.yml at the root.
#
# shellcheck for the other unlinted language in this repository: five bash
# scripts that re-chown volumes, drive a real phone, and resolve the Windows host
# across two WSL networking modes, none of them covered by a test. Configured by
# .shellcheckrc at the root.
#
# Nothing in Gradle, the app, or the test tiers depends on any of this — dropping
# these packages costs only the ability to lint YAML and shell, and to write
# ad-hoc scripts.
#
# asciidoctor is both the renderer and the linter for the AsciiDoc documentation:
# `asciidoctor --failure-level=WARN -o /dev/null` turns a broken cross-reference or
# a malformed table into a non-zero exit, which is the same shape as yamllint and
# shellcheck above — a language Gradle cannot see, checked by the tool that owns it.
# The Markdown converter is kramdown-asciidoc (`kramdoc`), installed below as a gem
# rather than from apt — the one deliberate exception to the "distro packages only"
# rule, and the reason is that pandoc, which *is* in apt, produces AsciiDoc that has
# to be repaired: an explicit [[anchor]] above every heading (invalid whenever the
# heading starts with a digit, which silently deletes the heading), verbose
# [cols=",,,"] on every table, and `''''' ` for a thematic break. kramdoc emits none
# of those. It is pinned like every other dependency.
#
# python3-venv + python3-pip because noble enforces PEP 668: `pip install` into
# the system interpreter refuses with "externally-managed-environment", so
# anything beyond the distro packages belongs in a `python3 -m venv` and these are
# what make that possible offline-ish. Deliberately no pip packages baked in —
# the header's "distro packages only" rule holds here too.
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      asciidoctor \
      ca-certificates \
      curl \
      git \
      jq \
      less \
      nodejs \
      npm \
      openjdk-17-jdk \
      openssh-client \
      procps \
      python3 \
      python3-pip \
      python3-venv \
      python3-yaml \
      ripgrep \
      shellcheck \
      sudo \
      tzdata \
      unzip \
      yamllint \
      zip \
# kramdoc, the Asciidoctor project's own Markdown converter. `asciidoctor` above
# brings Ruby and rubygems, so this is the only extra step. --no-document skips
# rdoc/ri, which nothing here reads.
 && gem install --no-document kramdown-asciidoc:2.1.1 \
# Antora builds the documentation site (docs/antora.yml, antora-playbook.yml). It is
# a Node application, which is the only reason nodejs and npm are in the image at all
# — nothing in the app, Gradle or the test tiers touches them. Pinned like everything
# else; `antora` is the CLI plus the default site generator.
#
# asciidoctor-kroki renders the Mermaid diagrams against the Kroki container in
# docker-compose.yml. It belongs in the image and not in an ad-hoc `npm install`:
# without it `antora antora-playbook.yml` does not degrade to unrendered diagrams,
# it dies with "Cannot find module 'asciidoctor-kroki'" — the playbook names it.
#
# PINNED TO 0.18.x ON PURPOSE, and do not let a bump past 1.0 through. 1.x targets
# Asciidoctor.js 3, while Antora 3.1 bundles 2.x, so the two load fine and then fail
# at render time with `block.$!= is not a function` — an error that names neither the
# extension nor the version. The pin moves when Antora's bundled Asciidoctor does.
# Keep it in step with .github/workflows/docs.yml, which installs the same two.
 && npm install -g --ignore-scripts --no-fund --no-audit antora@3.1.10 asciidoctor-kroki@0.18.1 \
# --- Android emulator runtime libraries ---------------------------------------
# Only needed for Tier 2 (headless emulator inside the container). Safe to drop
# this install if you only ever use Tier 1 (JVM tests) and Tier 3 (emulator on the
# Windows host). Note noble renamed libasound2 -> libasound2t64.
 && apt-get install -y --no-install-recommends \
      fontconfig \
      libasound2t64 \
      libdbus-1-3 \
      libfontconfig1 \
      libfreetype6 \
      libgl1 \
      libglu1-mesa \
      libice6 \
      libnss3 \
      libpulse0 \
      libsm6 \
      libx11-6 \
      libxcb-cursor0 \
      libxcb-icccm4 \
      libxcb-image0 \
      libxcb-keysyms1 \
      libxcb-render-util0 \
      libxcb-shape0 \
      libxcb-xkb1 \
      libxcb1 \
      libxcursor1 \
      libxi6 \
      libxkbcommon-x11-0 \
      libxkbcommon0 \
      libxkbfile1 \
      libxrandr2 \
      libxtst6 \
# --- Docker CLI (docker-outside-of-docker) -------------------------------------
# For the disposable opodsync test sync server (CLAUDE.md §4). devcontainer.json
# bind-mounts the HOST's /var/run/docker.sock, so containers started from in here
# are SIBLINGS on the host's daemon, not children — no daemon runs in this image.
#
# `docker.io` is Ubuntu's combined daemon+CLI package (there is no separate
# docker-cli in noble). The daemon it ships is simply never started; only the
# client binary is used. Both packages come from Ubuntu's own archive, keeping
# this image free of third-party apt repositories as the header promises.
 && apt-get install -y --no-install-recommends \
      docker-compose-v2 \
      docker.io \
 && rm -rf /var/lib/apt/lists/*

# --- GitHub CLI ----------------------------------------------------------------
# Ubuntu noble does package `gh`, but only 2.45.0 (Feb 2024) — well over a year
# behind, and `gh` talks to a moving API. Take the upstream release tarball
# instead: a pinned, static binary fetched over HTTPS, which is the same pattern
# this file already uses for Google's cmdline tools and Claude Code, and still
# not a third-party apt repository (see the header).
#
# Swap for `apt-get install gh` if the older version is ever preferable.
ARG GH_VERSION=2.97.0
RUN arch="$(dpkg --print-architecture)" \
 && curl --proto '=https' --proto-redir '=https' -fsSL -o /tmp/gh.tar.gz \
      "https://github.com/cli/cli/releases/download/v${GH_VERSION}/gh_${GH_VERSION}_linux_${arch}.tar.gz" \
 && tar -xzf /tmp/gh.tar.gz -C /tmp \
 && install -m 0755 "/tmp/gh_${GH_VERSION}_linux_${arch}/bin/gh" /usr/local/bin/gh \
 && rm -rf /tmp/gh.tar.gz "/tmp/gh_${GH_VERSION}_linux_${arch}" \
 && gh --version

ENV JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64

# --- Non-root user -------------------------------------------------------------
# ubuntu:24.04 ships a stock `ubuntu` user at UID 1000, which collides with the
# usual host UID; remove it before creating ours.
RUN if getent passwd "${USER_UID}" >/dev/null; then \
      userdel -r "$(getent passwd "${USER_UID}" | cut -d: -f1)" 2>/dev/null || true; \
    fi \
 && if ! getent group "${USER_GID}" >/dev/null; then groupadd -g "${USER_GID}" "${USERNAME}"; fi \
 && useradd -m -u "${USER_UID}" -g "${USER_GID}" -s /bin/bash "${USERNAME}" \
 && echo "${USERNAME} ALL=(ALL) NOPASSWD:ALL" > "/etc/sudoers.d/${USERNAME}" \
 && chmod 0440 "/etc/sudoers.d/${USERNAME}" \
 && if ! getent group kvm >/dev/null; then groupadd -g "${KVM_GID}" kvm; fi \
 && usermod -aG kvm "${USERNAME}" \
 # The docker.io package already created a `docker` group with an arbitrary GID.
 # It must match the GID owning the host's /var/run/docker.sock or the bind-mounted
 # socket is unreadable — hence groupmod, not groupadd. Falls back to adding a
 # secondary group if that GID is already taken by something else in the image.
 && if getent group "${DOCKER_GID}" >/dev/null; then \
      usermod -aG "$(getent group "${DOCKER_GID}" | cut -d: -f1)" "${USERNAME}"; \
    elif getent group docker >/dev/null; then \
      groupmod -g "${DOCKER_GID}" docker && usermod -aG docker "${USERNAME}"; \
    else \
      groupadd -g "${DOCKER_GID}" docker && usermod -aG docker "${USERNAME}"; \
    fi

# --- Android command-line tools ------------------------------------------------
# Installed under /opt (part of the image) and kept separate from $ANDROID_HOME
# (a named volume), so mounting the volume never shadows sdkmanager.
ENV ANDROID_CMDLINE_TOOLS=/opt/android-cmdline-tools
ADD "https://dl.google.com/android/repository/commandlinetools-linux-${CMDLINE_TOOLS_BUILD}_latest.zip" /tmp/cmdline-tools.zip
RUN mkdir -p "${ANDROID_CMDLINE_TOOLS}" \
 && unzip -q /tmp/cmdline-tools.zip -d /tmp/cmdline-tools \
 && mv /tmp/cmdline-tools/cmdline-tools "${ANDROID_CMDLINE_TOOLS}/latest" \
 && rm -rf /tmp/cmdline-tools.zip /tmp/cmdline-tools

# --- Persisted directories -----------------------------------------------------
# Created here (owned by the dev user) so that when Docker initialises the named
# volumes from the image they inherit the right ownership.
ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    ANDROID_USER_HOME=/home/${USERNAME}/.android \
    GRADLE_USER_HOME=/home/${USERNAME}/.gradle \
    CLAUDE_CONFIG_DIR=/home/${USERNAME}/.claude \
    GH_CONFIG_DIR=/home/${USERNAME}/.config/gh
# These are all volume mount points. Docker creates a missing mount point as
# root, which the container user then cannot write, so create them here with the
# right ownership first. USER_UID/USER_GID are only a best guess at the host's
# IDs — post-create.sh re-chowns these at runtime, where the real IDs are known.
RUN mkdir -p "${ANDROID_HOME}" "${ANDROID_USER_HOME}" "${GRADLE_USER_HOME}" \
      "${CLAUDE_CONFIG_DIR}" "${GH_CONFIG_DIR}" \
 && chown -R "${USER_UID}:${USER_GID}" \
      "${ANDROID_HOME}" "${ANDROID_USER_HOME}" "${GRADLE_USER_HOME}" \
      "${CLAUDE_CONFIG_DIR}" "/home/${USERNAME}/.config"

ENV PATH=/home/${USERNAME}/.local/bin:${ANDROID_CMDLINE_TOOLS}/latest/bin:${ANDROID_HOME}/platform-tools:${ANDROID_HOME}/emulator:${PATH}

# Gradle runs via ./gradlew only — no system Gradle is installed on purpose.
USER ${USERNAME}

# --- Claude Code ---------------------------------------------------------------
# Anthropic's native installer: a checksum-verified static binary. No Node.js, no
# npm, no third-party apt repository — which is why it is preferred here over
# `npm i -g @anthropic-ai/claude-code` (Ubuntu 24.04 only ships Node 18).
#
# Everything lands under $HOME, and deliberately in two separate places:
#   ~/.local/share/claude/versions/<v>  binary (+ ~/.local/bin/claude symlink)
#   ~/.local/state/claude, ~/.cache/claude
#   $CLAUDE_CONFIG_DIR (~/.claude)      credentials, settings, project history
# Only the last one is volume-mounted, so a login survives an image rebuild while
# the mount can never shadow the installed binary.
#
# Runs as the dev user on purpose: the installer refuses sudo and installs into
# whichever HOME it sees. Set to `latest`, a pinned `2.1.220`-style version, or
# `skip` to build without Claude Code; it self-updates at runtime regardless.
ARG CLAUDE_CODE_VERSION=stable
RUN if [ "${CLAUDE_CODE_VERSION}" = "skip" ]; then \
      echo "Skipping Claude Code (CLAUDE_CODE_VERSION=skip)."; \
    else \
      { curl --proto '=https' --proto-redir '=https' -fsSL https://claude.ai/install.sh | bash -s -- "${CLAUDE_CODE_VERSION}" \
        && claude --version; } \
      || { echo; \
           echo "Claude Code install failed."; \
           echo "If that was a connection timeout to downloads.claude.ai: it is an"; \
           echo "IPv4-only host (no real AAAA record), so it is unreachable from a"; \
           echo "network with no IPv4 default route — which the rest of this build"; \
           echo "survives because dl.google.com and archive.ubuntu.com are dual-stack."; \
           echo "Restore IPv4, or rebuild with --build-arg CLAUDE_CODE_VERSION=skip and"; \
           echo "install it later with: curl -fsSL https://claude.ai/install.sh | bash"; \
           exit 1; }; \
    fi

WORKDIR /workspaces/podsilo
