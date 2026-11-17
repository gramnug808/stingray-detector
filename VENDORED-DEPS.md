# Vendored / excluded files

The GitHub snapshot tool only accepts UTF-8 text files (max 16 MiB
total, no binaries). The following files are in the local build tree
but are NOT in this repo snapshot:

- `build/` — build outputs (APKs, dex, compiled classes). Regenerated
  by `./build.sh`.
- `debug.keystore` — local debug signing key. Do NOT commit a keystore.
  `./build.sh` generates one if missing; a release build needs its own
  key, kept out of the repo.
- `assets/tiles/` — bundled offline map tile PNGs (Honolulu z12–15).
  Binary files; copy them into `assets/tiles/` locally before building
  if you want the offline map. The app still runs without them (map
  shows no tiles, pins/list still work).
- `res/mipmap-*/ic_launcher.png` — launcher icon PNGs (binary). Needed
  for `aapt2 compile --dir res` in `build.sh`; copy them into `res/`
  locally before building.

Everything else needed to build — `AndroidManifest.xml`, `build.sh`,
`src/**/*.kt`, `test/**/*.kt`, `README.md` — is in this repo.
