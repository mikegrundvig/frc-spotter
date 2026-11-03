# Third-party software and licenses

Spotter's own code is under the MIT license (`LICENSE`). One file in this repository is someone
else's: `gradle/wrapper/gradle-wrapper.jar`, with `gradlew` and `gradlew.bat`, are Gradle's
wrapper, under the Apache License 2.0.

**What a release carries that isn't Spotter's.** The agent's jar (`frc-spotter.jar` in the `.deb`
and the tarball, `frc-spotter-<version>-all.jar` on its own) has these libraries inside it, each
under the Apache License 2.0:

- QuickBuffers' runtime (`us.hebi.quickbuf:quickbuf-runtime`)
- JSpecify (`org.jspecify:jspecify`)
- avaje-jsonb and avaje-json-core (`io.avaje:avaje-jsonb`, `io.avaje:avaje-json-core`)
- SnakeYAML Engine (`org.snakeyaml:snakeyaml-engine`)

The `.deb` and the tarball also carry a Java runtime, linked by jlink from the OpenJDK the release
was built with, under the GNU General Public License version 2 with the Classpath Exception. Its
own notices are beside it, in `usr/lib/frc-spotter/runtime/legal/`.

The robot's library, `spotter-manager`, bundles nothing: it depends on QuickBuffers' runtime and
JSpecify, which a robot build downloads like any other library.

**Building and testing Spotter** downloads, and doesn't redistribute: Gradle and its plugins, the
protobuf compiler and QuickBuffers' generator, Error Prone, NullAway, Spotless, JUnit, AssertJ,
ArchUnit, and Testcontainers (`gradle/libs.versions.toml`), and the container images its container
tests start.
