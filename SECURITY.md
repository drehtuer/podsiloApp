<!-- SPDX-License-Identifier: GPL-3.0-or-later -->

# Security policy

Podsilo is a personal project by one author, sideloaded from GitHub Releases. There is no company
behind it, no service it talks to that we operate, and no support contract. This file says what that
means for reporting a vulnerability, and what the app already does and does not protect.

## Reporting a vulnerability

**Do not open a public issue for a security problem.** Use either of:

1. **GitHub private vulnerability reporting** — the *Report a vulnerability* button under
   [Security](https://github.com/drehtuer/podsiloApp/security/advisories/new). Preferred: it keeps the
   report, the fix and the advisory in one place.
2. **Email** — <drehtuer@drehtuer.de>. There is no PGP key; if you need encryption, say so in a first
   message and one will be arranged.

Useful things to include: the version (`podsilo-x.y.z.apk`, or a commit hash for a build from
source), the Android version and device, what an attacker gains, and the smallest reproduction you
have. A feed XML fixture or a `MockWebServer`-shaped response is ideal — most of this codebase is
testable without a device.

### What to expect

Honest expectations rather than an SLA: this is a hobby project maintained in the author's own time.

| | |
|---|---|
| First reply | best effort, usually within a week |
| Fix for something exploitable | as fast as the author can manage; it is one person |
| Fix for something theoretical or low-impact | may become a `backlog.adoc` entry with the reasoning, rather than a patch |
| Disclosure | coordinated. Please allow 90 days, or until a release goes out — whichever is sooner. |
| Credit | happily given in the advisory and `journal.adoc`, or withheld if you prefer |

Only the **latest release** is supported. There are no backports to older tags — the fix lands on
`main` and in the next APK.

## Scope

**In scope** — anything in this repository that ships in the app:

- The Nextcloud credential path: Login Flow v2, storage of the app password, the Basic-auth header.
- Transport handling: the `https` upgrade rule, TLS behaviour, redirect following.
- Anything that writes to the user's chosen folder: filename sanitisation and path traversal via a
  hostile feed (`{podcast}`/`{title}` come from remote XML), the cache→tag→SAF copy pipeline.
- Feed and API parsing: XML, JSON, and audio-tag handling of malicious or malformed input.
- Data at rest: the Room database, DataStore, the backup archive, the error log.
- The build and release path: dependency integrity, the signing setup, CI workflows.

**Out of scope** — real problems, but not ours to fix:

- **Your Nextcloud instance** and the [gpoddersync](https://github.com/thrillfall/nextcloud-gpodder)
  app. Report those upstream.
- **Podcast feed servers**, and the content they serve.
- **Your audio player.** Once a file is in your folder it belongs to you and the player
  (`README.adoc`); Podsilo does not manage, track, or delete it.
- **The dev container and the test sync server.** `.env.example` ships throwaway credentials for a
  disposable local [opodsync](https://codeberg.org/kd2/opodsync); they are not secrets and are not
  meant to be.
- **A rooted or compromised device.** The app password is Keystore-encrypted, not hidden from root.
- Missing hardening for features Podsilo deliberately does not have — there is no player, no
  feed-management surface, no telemetry, no account system, and no server of ours (`CLAUDE.md` §1).

## What the app does today

Stated so you can check it rather than trust it. Each claim points at where it lives.

- **The app never sees your Nextcloud account password.** Authentication is **Login Flow v2** only:
  the browser grants an app password, and the app stores that. There is no password field anywhere in
  the UI, and adding one was declined deliberately (`UI.adoc` §8, `backlog.adoc`).
- **The app password is encrypted at rest** with an AES-256/GCM key held in the Android Keystore that
  never leaves the TEE/StrongBox; the plaintext exists only in memory
  (`core/datastore/.../KeystoreAppPasswordCipher.kt`).
- **HTTPS is upgraded, never downgraded.** Nextcloud hands back three URLs derived from its own
  `overwriteprotocol`, which behind a TLS-terminating proxy are frequently `http`. Podsilo rewrites
  them to `https` when the flow started over `https`, because `server` is persisted and one
  misconfigured field would otherwise put the app password on the wire in cleartext on every sync
  (`UI.adoc` §8).
- **The error log is redacted by construction**: it never contains the app password, the Basic-auth
  header, or URLs carrying credentials (`UI.adoc` §11). It is device-local — *Copy*/*Share* are
  user actions, and the log ships nowhere on its own.
- **The backup archive holds no credentials.** The app password is in DataStore, not the database, so
  it is not in the zip and a restored install must be reconnected. The archive *does* contain your
  subscription list, episode titles and show notes in readable form — treat it accordingly
  (`core/model/.../port/DatabaseArchive.kt`).
- **Android's platform backup is off.** `allowBackup` is false and the extraction rules exclude
  everything from cloud backup and device-to-device transfer, so your data leaves the app only through
  an export you ask for. A new phone therefore starts empty unless you restore that export.
- **Four permissions**, all in
  [`app/src/main/AndroidManifest.xml`](https://github.com/drehtuer/podsiloApp/blob/main/app/src/main/AndroidManifest.xml):
  `INTERNET`, `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` (active downloads), and
  `POST_NOTIFICATIONS`. No storage permission — downloads go through the Storage Access Framework
  into a folder you pick, so the app can write there and nowhere else.
- **No telemetry, no analytics, no ads, no crash reporting.** Podsilo talks to your Nextcloud, the
  feed servers on your subscription list, and nothing else.
- **Dependencies are pinned** in `gradle/libs.versions.toml` (no floating versions) and every one is
  licence-reviewed in
  [`third-party.adoc`](https://drehtuer.github.io/podsiloApp/podsilo/third-party.html). Releases are
  minified R8 builds signed with a key that is not in this repository — `*.jks` and `keystore.properties` are
  gitignored. Android enforces signature continuity, so an update that will not install over your
  existing one is a signal worth reporting.

## Known limitations — please don't report these as new

Recorded so a report is a surprise rather than a rediscovery. Each is a deliberate trade-off or an
open backlog item, not an oversight:

- **jaudiotagger comes from JitPack**, which builds from a GitHub tag rather than a reviewed registry
  release — a weaker supply-chain trust model, accepted knowingly
  (`architecture.adoc` §11, `third-party.adoc`).
- **Cleartext `http://` in feeds is blocked by Android and not worked around.** A feed advertising an
  `http://` enclosure fails to download with an unhelpful error. Options are in `backlog.adoc`;
  none weakens TLS by default.
- **A rejected login leaves its app password on the server.** It is unused, harmless and revocable
  under *Security* in Nextcloud; automatic revocation is in `backlog.adoc`.
- **A `DOWNLOAD`/`PLAY` action reaching the shared log is not retractable.** That is the design
  (`decisions/0023`), and the reason S5 confirms the account and bulk marking shows a preview
  dialog before writing — privacy consequences of connecting the wrong account are user-visible by
  intent, not a flaw.
- **The device test tier is not run in CI** (no hosted runner has a device), so device-only code —
  including `KeystoreAppPasswordCipher` — is verified by hand on real hardware. `README.adoc` says when
  that last happened.

## If you are patching it yourself

Pull requests are welcome for security fixes, but for anything exploitable please report privately
first so the advisory and the fix can go out together. `CLAUDE.md` §7 applies to a security fix like
any other: it needs a regression test that fails before the fix, and `./gradlew ktlintCheck detekt
test` has to pass.
