# Third-party software and licenses

Spotter's own code is under the MIT license (`LICENSE`). One file in this repository is someone
else's: `gradle/wrapper/gradle-wrapper.jar`, with `gradlew` and `gradlew.bat`, are Gradle's
wrapper, under the Apache License 2.0.

**What a release carries that isn't Spotter's.** The agent's program (`frc-spotter`, in the `.deb`
and the tarball) is built from Rust crates, each under its own licence (MIT, Apache-2.0,
BSD-3-Clause, Unicode-3.0, Zlib, and Apache-2.0 with the LLVM exception); `agent/deny.toml` lists
the licences allowed, and each package lists its crates and their licences, crate by crate, in
`/usr/share/doc/frc-spotter/THIRD-PARTY-LICENSES`.

The robot's library, `spotter-manager`, bundles nothing: it depends on QuickBuffers' runtime and
JSpecify, which a robot build downloads like any other library.

**Building and testing Spotter** downloads, and doesn't redistribute: Gradle and its plugins, the
protobuf compiler and QuickBuffers' generator, Error Prone, NullAway, Spotless, JUnit, AssertJ and
ArchUnit (`gradle/libs.versions.toml`); the Rust compiler, cargo-deny, and the crates the agent is
built and tested with (`agent/Cargo.lock`); and the container image its container test starts.
