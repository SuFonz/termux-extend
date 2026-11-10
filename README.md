# Termux Extend

An unofficial, performance-focused build of the official [Termux](https://termux.dev) app, forked from [termux/termux-app](https://github.com/termux/termux-app) (`master` @ `8629e632`).

The fork adds two commits on top of upstream: `108f11d5` (*rendering optimization*) and a **kitty graphics protocol** implementation. Everything else is upstream.

> Not an official Termux release. Report fork-specific issues here, not upstream.

## What's new

**Rendering** (`108f11d5`)

- **Row-batched images** — drawn once per row instead of once per cell, which is a big win for sixel and iTerm2 inline images.
- **Glyph width cache** — widths of non-ASCII code points are cached instead of re-measured for every cell on every frame (CJK, emoji).
- **Paint state diffing** — text effects are pushed to `Paint` only when they change.
- **Frame-driven fling** — `post()` replaced with `postOnAnimation()`, so scrolling updates at most once per frame.
- **Single invalidation per scroll** — one `invalidate()` per batch instead of one per row.
- **Java 17** — `app`, `terminal-emulator` and `terminal-view` now target Java 17.

**Kitty graphics protocol**

- `APC ESC_G` images with actions `t`/`T`/`p`/`d`/`q`, PNG plus raw RGB/RGBA, chunked transfer (`m=1`), zlib (`o=z`) and file transfer (`t=f`/`t=t`).
- Image numbers (`I=`), feature queries (`e=`), pixel offsets (`X`/`Y`), placement ids (`p=`) and usage hints (`N=`) are supported, as are all the delete specifiers and animations (`a=f` frames, `a=a` playback, `a=c` composition).
- Off by default. Enable it in `~/.termux/termux.properties`:

  ```properties
  kitty-graphics=true
  ```

  then run `termux-reload-settings`. Works with `kitten icat`, `chafa --format kitty`, `timg --kitty`.
- Images use the same cell bitmap model as sixel, so they scroll with the text. Not implemented: unicode placeholders and z-index stacking.

## Compatibility

- Package name is still `com.termux`, so this **replaces** the official app. It cannot be installed alongside it, and the official app has to be uninstalled first (different signing key).
- **Back up first** — uninstalling deletes `home` and `usr`:

  ```bash
  termux-setup-storage
  tar -C /data/data/com.termux/files -cf ~/storage/shared/termux-backup.tar home usr
  ```

- Plugins, the bootstrap and installed packages are unaffected.

## Build

Requires JDK 17+, Android SDK 36 and NDK `29.0.14206865`.

```bash
./gradlew assembleDebug    # app/build/outputs/apk/debug/
./gradlew test
```

Debug APKs are signed with the bundled `testkey_untrusted.jks`; release builds are unsigned.

## Credits & License

Built on the work of the [Termux](https://termux.dev) project and of [Android Terminal Emulator](https://github.com/jackpal/Android-Terminal-Emulator) by Jack Palevich.

GPLv3 only, with Apache 2.0 exceptions for `terminal-view` and `terminal-emulator`. See [LICENSE.md](LICENSE.md).
