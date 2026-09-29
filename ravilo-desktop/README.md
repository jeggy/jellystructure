# Ravilo for the Mac

The Mac app: Compose Desktop on the JVM, showing `ravilo-ui`'s TV layout driven by a keyboard and a mouse
(R328), playing through AVPlayer (R329), casting with its own Cast v2 client (R330), shipped as a `.dmg` on every
GitHub release (R331).

- `src/desktopMain/` — the window, the menu bar, closing and quitting, About.
- `native/` — `libravilo-mac.dylib`, the Swift library (the Keychain, the Computer Name, the player, Now Playing,
  display sleep, Bonjour). Only a Mac with Xcode's command-line tools builds it (`buildMacNative`); anywhere else the
  app runs without it.
- `icons/` — `ravilo.icns` for the bundle, `ravilo.png` for Linux.

## Running it

```
./gradlew :ravilo-desktop:run
```

works on a Mac and on Linux. On Linux, and on a Mac without the Swift library, the app browses and signs in but
plays nothing, and keeps its tokens in `tokens.json` (mode `0600`) instead of the Keychain. `XDG_DATA_HOME` (or
`-Dravilo.data.dir`) gives a second copy its own sign-in.

**A development build asks for the Keychain.** A Keychain item remembers the signing identity of the app that
made it. Releases are signed with one certificate of our own (R331), so an installed Ravilo never asks; a build from
`gradle run` is signed differently, so macOS asks *Ravilo wants to use your confidential information* — expected.

**Discovery needs the packaged app.** macOS grants Local Network access to an app bundle, not to a bare `java`
process: develop Chromecast discovery against `createDistributable`'s `Ravilo.app`, not against `run` (R330).

## Checking a bundle

```
Ravilo.app/Contents/MacOS/Ravilo --self-test
```

loads the Swift library, reads the version and the data directory, prints one line and exits 0 — without a window.
The release workflow runs it before uploading (R331 FR-R331-4).

## The signing certificate (once, on a Mac)

Every release is signed with **one** self-signed certificate of our own (R331 FR-R331-3). It is what lets a Mac
approve Ravilo once and open every later version without asking; there is no Apple account and no notarisation.
Make it once, on the MacBook, in an empty folder:

```sh
cat > ravilo-signing.cnf <<'CNF'
[req]
distinguished_name = dn
prompt = no
x509_extensions = ext
[dn]
CN = Ravilo
O = jellystructure
[ext]
basicConstraints = critical, CA:FALSE
keyUsage = critical, digitalSignature
extendedKeyUsage = critical, codeSigning
CNF

/usr/bin/openssl req -x509 -newkey rsa:3072 -sha256 -days 7300 -nodes \
  -config ravilo-signing.cnf -keyout ravilo-signing.key -out ravilo-signing.crt
/usr/bin/openssl pkcs12 -export -inkey ravilo-signing.key -in ravilo-signing.crt \
  -name Ravilo -out ravilo-signing.p12          # asks for the export password twice
```

`/usr/bin/openssl` is macOS's own (LibreSSL), which writes the `.p12` in the form `security import` reads on every
macOS version. The certificate is valid for 20 years; it must never need replacing, since a new one costs every Mac one
more *Open Anyway*.

Then give it to GitHub as two repository secrets (with the GitHub CLI signed in, from that folder):

```sh
base64 -i ravilo-signing.p12 | gh secret set MACOS_SIGNING_P12_BASE64 --repo jeggy/jellystructure
gh secret set MACOS_SIGNING_P12_PASSWORD --repo jeggy/jellystructure    # paste the export password
```

(or on github.com: Settings → Secrets and variables → Actions → *New repository secret*, same two names). Put
`ravilo-signing.p12` and its password in the password manager, then delete the four files from the folder — the
`.key` is the private key, unencrypted.

`deploy-macos.yml` refuses to build without both secrets; it never falls back to an ad-hoc signature.

