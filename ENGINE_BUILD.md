# Rebuilding the Pikafish engine

The engine ships as `app/src/main/jniLibs/arm64-v8a/libpikafish.so`. Despite the name it is
not a library: it is the Pikafish executable, run as a subprocess over UCI. It lives in
`jniLibs` rather than in `assets` because Android 10 and later refuse to execute anything
inside the app's own writable data directory, and `nativeLibraryDir` is the one place an app
may execute from. `packaging.jniLibs.useLegacyPackaging` is on so that the file is unpacked
to disk at install time and has a real path to hand to `ProcessBuilder`.

The binary must match the network in `app/src/main/assets/pikafish.nnue`. The one in the tree
was built from Pikafish `9ee223cf` (`dev-20260324`).

```bash
git clone https://github.com/official-pikafish/Pikafish.git
cd Pikafish && git checkout 9ee223cf && cd src

export PATH="$HOME/Library/Android/sdk/ndk/26.1.10909125/toolchains/llvm/prebuilt/darwin-x86_64/bin:$PATH"
make -j8 ARCH=armv8 COMP=ndk KERNEL=Linux OS=Android \
     EXTRALDFLAGS='-Wl,-z,max-page-size=16384' build

cp pikafish <repo>/app/src/main/jniLibs/arm64-v8a/libpikafish.so
```

Two flags are not optional. `KERNEL=Linux` overrides the Makefile's `uname -s` detection,
which otherwise sees macOS and passes `-mdynamic-no-pic` and `-arch armv8` to the Android
compiler, neither of which it accepts. `-Wl,-z,max-page-size=16384` is what Google Play
requires of native code in apps targeting Android 15 or higher; without it the LOAD segments
align to 4 KB and the upload is rejected.

Check the result before committing it:

```bash
llvm-readelf -l libpikafish.so | grep LOAD   # alignment must read 0x4000
```
