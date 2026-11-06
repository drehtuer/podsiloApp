#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Tier 3: point this container's adb at a real device attached outside it, and diagnose it when
# that does not work. The helper CLAUDE.md §4 asks for, "rather than making the author remember it".
#
# THE CONTAINER NEVER TOUCHES USB. It has no /dev/bus/usb, no usbip and no libusb access, and does
# not need any: adb is a client/server protocol over TCP, so on the USB path the container runs only
# the *client* and the server that owns the device runs in WSL (or on Windows). That is why
# `linux-tools-virtual` and `hwdata` belong in the WSL distro, where usbipd-win delivers the device,
# and not in this image — see dev-environment.adoc §9.
#
# OVER WIRELESS DEBUGGING (§9.4) THAT IS INVERTED: no USB device is owned by anyone, the server
# reaches the phone over TCP, and the server therefore belongs *here*. This script supports both, and
# the fact that separates them is not "is a server running in the container" — it is whether that
# server can see a device. An empty list from a container-local server is the USB failure; a
# non-empty one is a working link on either transport.
#
# Read-only and side-effect free by design: it will not start an adb server, because the one thing
# that reliably breaks the USB setup is a server started on the *container* side. Every adb client
# command below runs only after a raw TCP probe has proved one is already answering, since a client
# with nothing to talk to is exactly what starts the bad server.
set -euo pipefail

PORT="${ADB_SERVER_PORT:-5037}"

say()  { printf '%s\n' "$*"; }
fail() { printf '\033[1;31m%s\033[0m\n' "$*" >&2; }
note() { printf '\033[1;34m==> %s\033[0m\n' "$*"; }

# A plain TCP connect, deliberately not `adb devices`: every adb *client* command silently starts a
# local server when none answers, and on this side that server would be blind to USB — which is the
# exact failure this script exists to prevent, so it must not cause it while looking for it.
listening() {
    local target_host="$1"
    local target_port="$2"
    (exec 3<>"/dev/tcp/$target_host/$target_port") 2>/dev/null
}

# --- Where should the server be? ------------------------------------------------------------------
# With --network=host (devcontainer.json) the container shares the host's network namespace, so
# WSL's own adb server on 127.0.0.1:5037 is simply *there* and needs no configuration at all.
# Under ordinary bridge networking it does not exist here, and the host has to be addressed by IP —
# which is what ADB_SERVER_SOCKET is for.
if [[ -n "${ADB_SERVER_SOCKET:-}" ]]; then
    host="${ADB_SERVER_SOCKET#tcp:}"; host="${host%:*}"
    note "Using ADB_SERVER_SOCKET=$ADB_SERVER_SOCKET"
else
    host="127.0.0.1"
fi

# --- Is the server local to this container? -------------------------------------------------------
# Only meaningful when we are talking to a local socket at all: with ADB_SERVER_SOCKET pointing at
# another machine, a stray `adb` process in here is not the server we are about to query.
local_server() {
    case "$host" in
        127.0.0.1 | localhost | ::1) pgrep -x adb >/dev/null 2>&1 ;;
        *) return 1 ;;
    esac
}

# --- Is anything answering? -----------------------------------------------------------------------
if ! listening "$host" "$PORT"; then
    if local_server; then
        # A server process exists but is not reachable at the socket we were told to use — almost
        # always a stale ADB_SERVER_SOCKET rather than a broken server.
        fail "An adb server is running here (pid $(pgrep -x adb | paste -sd' ' -)) but nothing answers on $host:$PORT."
        say  ""
        say  "  ADB_SERVER_SOCKET=${ADB_SERVER_SOCKET:-<unset>} is what chose that address."
        say  "  Unset it to talk to the local server, or point it somewhere that is listening."
        exit 1
    fi
    fail "No adb server is listening on $host:$PORT."
    say  ""
    say  "  Nothing is wrong in here — start the server on the side that owns the device."
    say  ""
    say  "  From WSL (the usual path; any adb command starts it):"
    say  "      adb devices"
    say  "  If the phone is not listed there either, it has not been passed through yet."
    say  "  On Windows, in an elevated prompt:"
    say  "      usbipd list"
    say  "      usbipd bind   --busid <busid>     # once per device, persists"
    say  "      usbipd attach --wsl --busid <busid>"
    say  ""
    say  "  Or from a Windows-side adb server instead of WSL's:"
    say  "      # Windows:   adb -a -P 5037 nodaemon server"
    say  "      # here:      export ADB_SERVER_SOCKET=tcp:\$(ip route show default | awk '{print \$3}'):5037"
    exit 1
fi

note "adb server answering on $host:$PORT"

# --- Version skew ---------------------------------------------------------------------------------
# CLAUDE.md §4: the client and server must be the same platform-tools build. When they are not, the
# client KILLS the running server and starts its own — which, from in here, is precisely the
# USB-blind server described above. So a mismatch does not fail loudly; it fails as "no devices".
say "  client here: $(adb version | sed -n '2p')"
say "  (the server must be the same platform-tools build — a mismatch makes this client kill it"
say "   and start a USB-blind one in its place, which reads as 'no devices attached')"

# --- What is attached -----------------------------------------------------------------------------
# Safe to ask now, and only now: a client command starts a server *only when none answers*, and one
# demonstrably answers. Asking earlier is what would create the USB-blind server described below.
say ""
devices="$(adb devices -l | tail -n +2 | grep -v '^[[:space:]]*$' || true)"

# --- The failure mode worth defending against -----------------------------------------------------
# If an adb client runs in this container while no server is listening, adb starts one HERE. It then
# holds 127.0.0.1:5037 in the shared host namespace and answers for WSL too — with an empty device
# list, because it cannot see USB. The symptom is "my device disappeared from WSL as well", which
# looks like a cable or a phone problem and is neither.
#
# THE TEST IS THE EMPTY LIST, NOT THE PROCESS. A container-local server used to be treated as the
# fault itself, which is right for USB and exactly wrong for wireless debugging (§9.4): over Wi-Fi
# nothing owns a USB device, the server reaches the phone over TCP, and so the local server is the
# one that is *supposed* to be there. Refusing on sight made `device-test.sh` unusable on the easier
# of the two transports. A local server holding no devices is still the bug; a local server holding
# a device is doing its job, whatever the transport.
if [[ -z "$devices" ]]; then
    if local_server; then
        fail "An adb server is running INSIDE this container (pid $(pgrep -x adb | paste -sd' ' -)) and sees nothing."
        say  ""
        say  "  If the phone is on USB it cannot see it — this container has no /dev/bus/usb — and"
        say  "  because the network namespace is shared it is also answering for WSL, so both sides"
        say  "  report no devices."
        say  ""
        say  "  For a USB device, fix in this order:"
        say  "      adb kill-server            # here"
        say  "      adb devices                # in WSL, which starts a server that owns the device"
        say  "      $0                         # here again"
        say  ""
        say  "  For wireless debugging (dev-environment.adoc §9.4) this server is the right one —"
        say  "  it just has nothing connected yet. Pair and connect the phone:"
        say  "      adb pair <ip>:<pairing-port> <code>"
        say  "      adb connect <ip>:<connect-port>     # a DIFFERENT port, shown on the same screen"
        exit 1
    fi
    fail "The server is up but no devices are attached."
    say  ""
    say  "  Check in WSL first — if 'adb devices' is empty there too, the phone is not passed"
    say  "  through (usbipd attach), is locked, or has not authorised this computer for USB"
    say  "  debugging. Watch the phone's screen for the RSA fingerprint prompt."
    exit 1
fi

note "Attached"
say "$devices"

# A serial of the form <host>:<port> is a network device — wireless debugging, or an emulator
# reached over TCP. Named explicitly because §9.4's advice inverts §9.1's, and knowing which page
# you are on is most of the battle when this goes wrong.
if printf '%s\n' "$devices" | awk '{print $1}' | grep -qE ':[0-9]+$'; then
    say ""
    say "  (network device — wireless debugging, so the adb server belongs in this container;"
    say "   dev-environment.adoc §9.4. Do not 'fix' it with adb kill-server.)"
fi

say ""
say "Run instrumented tests against it with:"
say "    ./scripts/device-test.sh"
