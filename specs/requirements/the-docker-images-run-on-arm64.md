# The Docker images run on arm64

Task: DEB-1490

> Owner, 2026-10-16 (DEB-1490): *"Let's add support for apple silicon versions of docker for our docker images."*
> On a MacBook with an M5, `docker compose pull` against the example compose file on jellystructure.jebster.net
> failed with `no matching manifest for linux/arm64/v8` for `ghcr.io/jeggy/jellystructure:1.52`. The other two
> pulls stopped as `Interrupted` because that one failed.

## What happens today

`publish.yml` builds every image with `platforms: linux/amd64`. Phase 167 chose this on purpose (§3, *Non-goals*:
"amd64 only, matching the only architecture this deployment runs on"). The images that pull fails on are ours:

| Image | arm64 today | Why |
|---|---|---|
| `jellyfin/jellyfin` | yes | Upstream publishes a multi-arch manifest. It stopped only because ours failed. |
| `ghcr.io/jeggy/jellystructure` | no | The binary is the Kotlin/Native `linuxX64` executable. |
| `ghcr.io/jeggy/ravilo-web` | no | The wasm bundle is the same on every architecture. The server in front of it, `:web-static-server`, is a `linuxX64` executable. |
| `ghcr.io/jeggy/jellystructure-avsync` | no | Not in the example compose. It is built on a CUDA PyTorch image, and that only runs on an NVIDIA host. |

The constitution already names both targets: *"Target: `linuxX64` / `linuxArm64`, producing a native binary"*
(`constitution.md` §Backend, and `plan.md` says the same). Only `linuxX64` was ever built. This spec builds the
second target. It does not change the architecture.

### What the code makes cheap

- **The libraries are ready.** Ktor 3.6.0 (`server-cio`, `client-curl`, `client-websockets`), SQLDelight 2.0.2
  (`native-driver`, `runtime`), ktoml 0.7.1 and kotlinx-io 0.9.0 all publish `linuxArm64` variants (checked on Maven
  Central, 2026-10-16).
- **The source has no architecture in it.** The only `x86_64` strings under `src/linuxX64Main` are the encoder's
  download pin (FR-4). All 287 files are POSIX through `platform.posix`, which reads the same on both 64-bit
  targets.
- **The runtime base is already multi-arch.** `debian:bookworm-slim` ships arm64 builds of `ffmpeg`, `mkvtoolnix`,
  `libsqlite3-0`, `libchromaprint-tools`, `python3-mutagen` and `xz-utils`.
- **The pinned downloads have arm64 builds.** `dovi_tool` 2.3.4 has `aarch64-unknown-linux-musl`. jellyfin-ffmpeg
  8.1.2-5 has `portable_linuxarm64-gpl`.

### What the code makes expensive

- **Kotlin/Native cannot run on Linux arm64.** Kotlin 2.3.21 ships its compiler only for `linux-x86_64`,
  `macos-x86_64`, `macos-aarch64` and `windows-x86_64` (the `kotlin-native-prebuilt` artifacts on Maven Central).
  The usual one-line fix, `platforms: linux/amd64,linux/arm64` under QEMU, runs the builder stage *as* arm64, and
  that stage cannot start the compiler. The arm64 binary therefore has to be **cross-compiled on amd64**, and only
  the runtime stage is built per architecture.
- **SQLite is linked from the host.** The `linuxX64` link uses `-L/usr/lib/x86_64-linux-gnu -lsqlite3`, against
  the builder image's own `libsqlite3-dev`. A cross link needs the arm64 copy of that library in the builder too.
- **Two compile targets, and two fewer source-set assumptions.** `shared`, `ravilo-i18n`, the root project and
  `:web-static-server` each declare `linuxX64` alone. Their code sits in `linuxX64Main`, and the root build's
  SQLDelight rerouting (the `afterEvaluate` block) names `linuxX64Main` by hand.

## Prior art

Multi-arch is the norm for self-hosted media images. Jellyfin, the linuxserver.io images and the *arr stack all
publish `amd64` and `arm64` under one tag, and most drop 32-bit ARM. Kotlin/Native server projects cross-compile
`linuxArm64` from an x86 host, because no Linux arm64 compiler host exists.

## Options

1. **Native arm64 images, cross-compiled (recommended).** Every tag `publish.yml` pushes for `jellystructure` and
   `ravilo-web` becomes a manifest list covering `linux/amd64` and `linux/arm64`. The builder stage runs on amd64
   and links both binaries. The runtime stage is built per architecture. The arm64 binary is a real arm64 binary,
   so it runs at full speed on Apple Silicon, a Raspberry Pi 4 or 5 with a 64-bit OS, an ARM NAS and Graviton.
   The cost is one more Kotlin/Native link per image on every publish run, plus QEMU time for the arm64 runtime
   stage's `apt-get`. The amd64 CI e2e job does not pay it.
2. **No new build: document `platform: linux/amd64`.** Docker Desktop on Apple Silicon runs amd64 images under
   Rosetta, so adding that line to both services works today. It does nothing for a Pi or a NAS, where emulation is
   QEMU and ffmpeg becomes unusably slow. It also makes every reader of the example copy a workaround.
3. **Both: document 2 until 1 has shipped.** The owner's Mac can use the workaround today.

Option 1 is what the owner asked for. Option 2 needs no spec, and the owner can use it today without waiting for a
release (see *For the Mac today* below).

## Scope

The `jellystructure` and `ravilo-web` images. **Not in scope:**
- `jellystructure-avsync`, which is CUDA and stays amd64.
- `ravilo-screen`, which CI builds for e2e and never publishes.
- 32-bit ARM (`linux/arm/v7`).
- The Ravilo client apps, which ship as their own artifacts (R331's `.dmg`, R334's Flatpak) and not as images.
- The example compose on jellystructure.jebster.net. It needs no edit once the tag carries both architectures.

This spec supersedes Phase 167 §3's *"Multi-arch builds. amd64 only"*. A dated pointer has been added to that line.

## Requirements

**FR-1 — Both native modules compile and link for `linuxArm64`.**
- **Targets.** The root project, `:shared`, `:ravilo-i18n` and `:web-static-server` declare `linuxArm64` beside
  `linuxX64`. Each module's `linuxX64` code becomes a source set that both targets compile, and the SQLDelight
  rerouting adds its generated directory and runtime dependency to that shared set.
- **Source layout.** Recommended: point the shared source set at the existing `src/linuxX64Main/kotlin`
  directories with `srcDir`, rather than `git mv` 287 files. Blame and in-flight work stay intact, and the rename
  can come later on its own. The engineer may choose the move if the build reads cleaner that way.
- **Link options.** `linkReleaseExecutableLinuxArm64` links against an arm64 `libsqlite3`. Its `linkerOpts` name
  `/usr/lib/aarch64-linux-gnu`, and the `linuxX64` line is unchanged.
- **Tests.** `linuxX64Test` stays the test target. CI does not run arm64 unit tests. FR-5 is the arm64 proof.

**FR-2 — The Dockerfiles cross-build.**
- **Builder stage.** In `Dockerfile` and `ravilo-web/Dockerfile` the builder stage is
  `FROM --platform=$BUILDPLATFORM`, so it always runs on the build host's own architecture, amd64. It links the
  executable for `$TARGETARCH`: `linuxX64` for `amd64`, `linuxArm64` for `arm64`.
- **Runtime stage.** The runtime stage is per target and copies that binary to the same path as today
  (`/app/jellystructure`, `/app/web-static-server`).
- **Cross link library.** For the cross link, the builder installs the arm64 `libsqlite3-dev` via Debian/Ubuntu
  multiarch. The temurin base is Ubuntu, so this means the arm64 `ports.ubuntu.com` source.
- **amd64 builds are unchanged.** A build for amd64 alone, as the CI e2e job runs through
  `docker-compose.test.yml`, links only `linuxX64`. Its time and its cache use do not change. The cache-mount ids
  stay those `scripts/check-docker-cache-ids.sh` fences (Phase 250).
- **dovi_tool.** The `dovi_tool` download picks its asset and pinned SHA-256 by `$TARGETARCH`. Both are pinned, and
  a mismatch fails the build as it does today.

**FR-3 — Every published tag covers both architectures.** `publish.yml` builds `jellystructure` and `ravilo-web`
with `platforms: linux/amd64,linux/arm64`. It adds `docker/setup-qemu-action` so that the arm64 runtime stage's
`RUN` steps execute. Every tag it pushes is one manifest list for both: `:<sha>` on a push, and `:latest` plus
`:<MAJOR.MINOR>` on a release. `jellystructure-avsync` stays `linux/amd64`. Nothing else in the pipeline changes:
CI still gates the push (Phase 231), and `latest` still moves only on a release.

**FR-4 — The encoder downloads the build for its own architecture.** Phase 313's first-start download
(`Encoder.JELLYFIN_FFMPEG_URL` / `_SHA256`, FR-313-11) picks jellyfin-ffmpeg 8.1.2-5's `portable_linux64-gpl` on
`linuxX64` and `portable_linuxarm64-gpl` on `linuxArm64`, each with its own pinned SHA-256. The version stays the
same as Jellyfin's own container. Without this, an arm64 install with `[encoder]` on downloads an x86 ffmpeg it
cannot execute.

**FR-5 — CI proves the arm64 image starts.** After the push, `publish.yml` runs the arm64 image of each one under
QEMU: `docker run --platform linux/arm64` against the SHA tag it just pushed. It checks two things:
- `jellystructure` answers `/api/health` with 200 within its existing start period, given an empty `/config`.
- `ravilo-web` answers `GET /` with 200.

A failure turns the run red. This catches the failures a cross build can hide: a missing shared library, the wrong
`ld-linux`, a binary for the wrong architecture. It does not test behaviour.

> **Amendment, 2026-10-17 (DEB-1602; Tróndur's decision on that row).** The smoke is **advisory**, not a gate. On the
> v1.54 release run it hung three times out of three, and on a push run it failed once with `last: 000`. The same
> v1.54 `jellystructure` arm64 image started cleanly on the owner's M5 and answered on :9505 within seconds (DEB-1490),
> so the fault is qemu-user on the runner, not the binary. A hung or red smoke must never fail `publish`, because the
> Play, macOS, Tizen and Linux deploy jobs `need` it. What replaces "a failure turns the run red":
> - The step is `continue-on-error: true`. On failure it prints a `::warning::` with the container state, the host
>   listener, the published-port curl, an in-container probe and `docker logs smoke`.
> - The step stays **bounded**: `timeout-minutes: 10`, `curl --connect-timeout 3 --max-time 5`, `timeout` on the docker
>   commands, and a 300 s loop deadline.
> - `Verify the manifest platforms` is the arm64 **gate**: it is deterministic, and it is what makes the pull work.
> - The amd64 path is unchanged and still gates through `ci / e2e`.

**FR-6 — The README says which architectures there are.** `README.md`'s *Running it* section says that the images cover
`linux/amd64` and `linux/arm64` (Apple Silicon, 64-bit Raspberry Pi, ARM NAS). It also says the A/V sync sidecar is
amd64 with an NVIDIA GPU only.

## For the Mac today

1.52 is a fixed tag and stays amd64-only. Re-publishing it with `workflow_dispatch` would rebuild the 1.52 commit's
Dockerfile, which has no arm64 in it. Arm64 therefore arrives with the first release after this ships, and release
timing is Tróndur's. Until then, adding `platform: linux/amd64` under `jellystructure:` and `ravilo-web:` in the
compose file makes `docker compose pull` work on Apple Silicon. Docker Desktop runs those two images under Rosetta.
Jellyfin itself already pulls natively.

## Acceptance

1. `docker buildx imagetools inspect ghcr.io/jeggy/jellystructure:<sha>` and the same for `ravilo-web` each list
   `linux/amd64` and `linux/arm64`. `jellystructure-avsync:<sha>` lists `linux/amd64` alone.
2. `Verify the manifest platforms` is green on that run. FR-5's arm64 smoke is advisory (see its 2026-10-17 amendment): a
   `::warning::` from it does not fail the run.
3. **On an Apple Silicon Mac with Docker Desktop (QA):**
   - **Setup.** Use the website's example compose with both of our images pinned to the SHA tag and no `platform:`
     line.
   - **Pull and start.** `docker compose pull` succeeds. `docker image inspect` reports `Architecture: arm64` for
     both images. `docker compose up -d` brings all three containers to `healthy`/running.
   - **Walk.** `http://localhost:9505` serves the admin's first-run screen. `http://localhost:8080` serves Ravilo
     web's sign-in.
   - **Logs.** `docker logs` for jellystructure shows no `exec format error` and no Rosetta message.
   - **Teardown.** `docker compose down -v` and remove the scratch directory.
4. The CI e2e job's duration on the first push after this lands is within normal variance of the run before it
   (FR-2, amd64 builds are unchanged).
