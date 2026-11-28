#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Runs once after the dev container is created. Installs the Android SDK
# components into $ANDROID_HOME (a named volume, so this survives image
# rebuilds) and reports what the host can actually support.
#
# Idempotent: re-running it is cheap, sdkmanager skips what is already there.

set -euo pipefail

log()  { printf '\n\033[1;34m==> %s\033[0m\n' "$*"; }
warn() { printf '\n\033[1;33m!!  %s\033[0m\n' "$*"; }

# compileSdk / build-tools / system image. Override for a one-off, e.g.
#   SDK_PACKAGES="platform-tools platforms;android-38" bash .devcontainer/post-create.sh
#
# Kept in step with the Gradle project's compileSdk (gradle/libs.versions.toml
# / each module's build.gradle.kts). Bumped from 35 to 37.0 when the current
# stable androidx.activity/androidx.core releases started requiring compileSdk
# >= 36 to even resolve (AAR metadata check) — discovered building the hello
# world skeleton, not a pre-emptive bump. The emulator system image stays on
# android-35 for now; Tier 2 is out of scope for that change (see journal).
: "${SDK_PACKAGES:=platform-tools platforms;android-37.2 build-tools;37.0.0 emulator system-images;android-35;google_apis;x86_64}"

# The system image is ~1.5 GB. Set to 0 to skip it if you only use Tier 1
# (JVM tests) and Tier 3 (emulator on the Windows host).
: "${INSTALL_SYSTEM_IMAGE:=1}"

# sdkmanager lives in /opt (in the image) while the SDK lives in $ANDROID_HOME (a
# volume), so it can never infer the root — always pass --sdk_root explicitly.
sdkm() { sdkmanager --sdk_root="${ANDROID_HOME}" "$@"; }

# --- Runtime ownership / group repair -------------------------------------------
# The image bakes USER_UID/USER_GID/KVM_GID/DOCKER_GID in as build args, but the
# values that actually matter belong to the *host*, and they differ per machine.
# Two things then go wrong that no build arg can fix from inside the image:
#
#   1. The devcontainer CLI rewrites this user's UID/GID at container start
#      (updateRemoteUserUID) to match the host user, and chowns only $HOME. On a
#      host whose UID is not USER_UID, $ANDROID_HOME (a named volume, initialised
#      from the image's ownership) is left owned by a stranger.
#   2. /dev/kvm and the bind-mounted /var/run/docker.sock carry the *host's* GIDs,
#      which need not be the ones the image created.
#
# Both were hit for real when this project moved to a second machine (host uid
# 1002, docker gid 108). sdkmanager's failure mode for (1) is silent and
# genuinely misleading: `--licenses` prints "All SDK package licenses accepted"
# while writing nothing at all, and the install that follows then prompts
# "Accept? (y/N):" against a closed stdin and skips every package. So repair the
# ownership here, at runtime, where the real IDs are knowable — rather than
# asking every host to edit devcontainer.json first.
log "Runtime ownership and group repair"

uid="$(id -u)"
gid="$(id -g)"
username="$(id -un)"
needs_new_shell=0

for dir in \
  "${ANDROID_HOME}" \
  "${ANDROID_USER_HOME:-${HOME}/.android}" \
  "${GRADLE_USER_HOME:-${HOME}/.gradle}" \
  "${CLAUDE_CONFIG_DIR:-${HOME}/.claude}" \
  "${HOME}/.config/gh"; do
  [[ -d "${dir}" ]] || continue
  owner="$(stat -c '%u:%g' "${dir}")"
  if [[ "${owner}" != "${uid}:${gid}" ]]; then
    warn "${dir} is owned by ${owner}, not ${uid}:${gid} (${username}) — fixing."
    sudo chown -R "${uid}:${gid}" "${dir}"
  fi
done

# Give the container user access to a host-provided node by aligning a group with
# the GID that owns it. Deliberately never chgrp the node itself: /var/run/docker.sock
# is a bind mount, so changing its group would change it on the HOST too.
align_group() {
  local node="$1" fallback_name="$2" label="$3"
  [[ -e "${node}" ]] || return 0

  local node_gid
  node_gid="$(stat -c '%g' "${node}")"
  if id -G | tr ' ' '\n' | grep -qx "${node_gid}"; then
    echo "${label}: ${node} (gid ${node_gid}) already accessible to ${username}."
    return 0
  fi

  # `|| true` is load-bearing: getent exits 2 when the GID has no group, which
  # under `set -euo pipefail` would abort the script on the very case this
  # function exists to handle.
  local grp
  grp="$(getent group "${node_gid}" | cut -d: -f1 || true)"
  if [[ -z "${grp}" ]]; then
    if getent group "${fallback_name}" >/dev/null; then
      sudo groupmod -g "${node_gid}" "${fallback_name}"
    else
      sudo groupadd -g "${node_gid}" "${fallback_name}"
    fi
    grp="${fallback_name}"
  fi

  sudo usermod -aG "${grp}" "${username}"
  warn "${label}: ${node} is owned by gid ${node_gid}; added ${username} to '${grp}'."
  needs_new_shell=1
}

align_group /var/run/docker.sock docker "Docker socket"
align_group /dev/kvm kvm "KVM device"

# IPv6-only network? The JVM tries IPv4 first and does not fall back the way curl's
# happy-eyeballs does, so sdkmanager sits on IPv4 connect timeouts for tens of
# minutes and then reports "Failed to download any source lists! / IO exception
# while downloading manifest" — on a host where plain `curl https://dl.google.com`
# returns 200. Steer it to IPv6 instead of letting that look like a broken mirror.
#
# This probes reachability rather than checking for an IPv4 default route: on this
# WSL2 host the route can be present while IPv4 is entirely dead (the access point
# offers IPv6 only), and a route check happily reports "IPv4 fine" and hangs.
probe_url="https://dl.google.com/android/repository/repository2-3.xml"
if ! curl -4 -s -o /dev/null --connect-timeout 5 --max-time 15 "${probe_url}"; then
  if curl -6 -s -o /dev/null --connect-timeout 5 --max-time 15 "${probe_url}"; then
    warn "IPv4 cannot reach dl.google.com, IPv6 can — treating this network as IPv6-only."
    echo "  Pointing the JVM at IPv6 so sdkmanager does not stall on dead IPv4 routes."
    echo "  Gradle will need the same once the project exists:"
    echo "    export _JAVA_OPTIONS=-Djava.net.preferIPv6Addresses=true"
    export _JAVA_OPTIONS="${_JAVA_OPTIONS:+${_JAVA_OPTIONS} }-Djava.net.preferIPv6Addresses=true"
  else
    warn "Neither IPv4 nor IPv6 reaches dl.google.com — the SDK install below will fail."
    echo "  Check the host's connectivity before blaming this script."
  fi
fi

log "Toolchain"
java -version
echo "ANDROID_HOME=${ANDROID_HOME}"
sdkm --version

log "Accepting Android SDK licences"
# `yes` closes the pipe early once sdkmanager stops reading; that is expected.
yes | sdkm --licenses >/dev/null || true

# Verify rather than trust the exit code. sdkmanager prints "All SDK package
# licenses accepted" and exits 0 even when it could not create the licences
# directory at all — the failure only surfaces much later, as every package being
# skipped for an unaccepted licence. Checking for the files turns that into an
# immediate, explicable error.
if ! compgen -G "${ANDROID_HOME}/licenses/*" >/dev/null; then
  warn "sdkmanager reported success but wrote no licence files to ${ANDROID_HOME}/licenses."
  echo "  That means it could not write to \$ANDROID_HOME. Current ownership:"
  ls -ld "${ANDROID_HOME}"
  echo "  Expected owner: $(id -u):$(id -g) ($(id -un))."
  echo "  The repair step above should have handled this — check that sudo works."
  exit 1
fi

packages=()
for pkg in ${SDK_PACKAGES}; do
  if [[ "${pkg}" == system-images* && "${INSTALL_SYSTEM_IMAGE}" != "1" ]]; then
    warn "Skipping ${pkg} (INSTALL_SYSTEM_IMAGE=0)"
    continue
  fi
  packages+=("${pkg}")
done

log "Installing SDK components: ${packages[*]}"
sdkm "${packages[@]}"

log "Installed"
sdkm --list_installed || true

# A bare `adb version` here used to abort the whole script under `set -e` with
# nothing but "adb: command not found" — true, but it names the symptom rather
# than the cause. Say which install did not complete.
if ! command -v adb >/dev/null; then
  warn "adb is not on PATH — the platform-tools install did not complete."
  echo "  Re-read the sdkmanager output above; the usual cause is a licence or"
  echo "  permission problem on \$ANDROID_HOME rather than a download failure."
  exit 1
fi
adb version

# --- Virtualisation report ------------------------------------------------------
# Fail loudly rather than silently degrading to a software-rendered emulator that
# nobody can wait for.
log "Hardware virtualisation (Tier 2, emulator inside this container)"
if [[ -r /dev/kvm && -w /dev/kvm ]]; then
  echo "/dev/kvm is readable and writable — in-container emulator should work."
  echo "Expect a nested-virtualisation warning and reduced speed; that is normal in WSL2."
  # The authoritative check: the emulator's own probe, not just device permissions.
  # Written as an `if` rather than `A && B || true`, which reads like if-then-else
  # and is not one (ShellCheck SC2015).
  if command -v emulator >/dev/null; then
    emulator -accel-check || true
  fi
elif [[ -e /dev/kvm ]]; then
  warn "/dev/kvm exists but is not accessible to $(id -un) (gid $(id -g))."
  echo "  Fix on the WSL2 host: getent group kvm   -> rebuild with KVM_GID=<that gid>"
  echo "  and ensure /etc/wsl.conf chowns /dev/kvm to root:kvm 0660 on boot."
else
  warn "/dev/kvm is not present in this container. Tier 2 (in-container emulator) is unavailable."
  echo "  Requires: Windows 11, nestedVirtualization=true in %USERPROFILE%\\.wslconfig,"
  echo "  Docker Engine inside the WSL2 distro (not Docker Desktop), and"
  echo "  \"runArgs\": [\"--device=/dev/kvm\"] in devcontainer.json."
  echo "  Tier 1 (./gradlew test) and Tier 3 (emulator on the Windows host) are unaffected."
fi

# --- GitHub CLI -----------------------------------------------------------------
log "GitHub CLI"
if command -v gh >/dev/null; then
  gh --version | head -1
  echo "config dir: ${HOME}/.config/gh (named volume — 'gh auth login' survives a rebuild)"
  if [[ -n "${GH_TOKEN:-}" || -n "${GITHUB_TOKEN:-}" ]]; then
    echo "GH_TOKEN/GITHUB_TOKEN is set in the environment."
  elif gh auth status >/dev/null 2>&1; then
    echo "Authenticated — no login needed."
  else
    warn "Not authenticated yet. Run 'gh auth login' once; it persists in the volume."
  fi
else
  warn "gh not on PATH — rebuild the container to pick it up (added 2026-07-31)."
fi

# --- Claude Code ----------------------------------------------------------------
log "Claude Code"
if command -v claude >/dev/null; then
  claude --version
  echo "config dir: ${CLAUDE_CONFIG_DIR:-$HOME/.claude} (named volume — survives a rebuild)"
  if [[ -n "${ANTHROPIC_API_KEY:-}" ]]; then
    echo "ANTHROPIC_API_KEY is set in the environment."
  elif [[ -f "${CLAUDE_CONFIG_DIR:-$HOME/.claude}/.credentials.json" ]]; then
    echo "Credentials present — no login needed."
  else
    warn "Not logged in yet. Run 'claude' and then '/login' once; it persists in the volume."
  fi
else
  warn "claude not on PATH — image built with CLAUDE_CODE_VERSION=skip, or the install failed."
  echo "  Install it here once the host has IPv4 (downloads.claude.ai has no IPv6):"
  echo "    curl -fsSL https://claude.ai/install.sh | bash"
fi

# --- Gradle warm-up -------------------------------------------------------------
# Non-fatal on purpose. A failed warm-up must not fail the whole postCreateCommand
# and leave VS Code reporting a broken container — everything above it has already
# succeeded, and the wrapper can be retried by hand.
if [[ -x ./gradlew ]]; then
  log "Gradle wrapper warm-up"
  if ! ./gradlew --version; then
    warn "Gradle wrapper download failed — the container is otherwise fine."
    echo "  On an IPv6-only network this is expected and not fixable from here:"
    echo "  services.gradle.org redirects to downloads.gradle-dn.com, which has no"
    echo "  AAAA record, so the distribution zip is unreachable over IPv6."
    echo "  Maven Central and dl.google.com are dual-stack, so once the wrapper's"
    echo "  distribution is cached in ~/.gradle, builds work again. Re-run ./gradlew"
    echo "  from a network with IPv4."
  fi
else
  warn "No ./gradlew yet — skipping Gradle warm-up (expected until the Gradle skeleton lands)."
fi

log "Dev container ready"

# Group changes only apply to processes started after usermod, so the shell this
# script runs in still has the old set. New VS Code terminals get the new one.
if [[ "${needs_new_shell}" == "1" ]]; then
  warn "Group membership changed — open a NEW terminal before using docker/the emulator."
  echo "  This shell still has the old groups: $(id -Gn)"
fi
