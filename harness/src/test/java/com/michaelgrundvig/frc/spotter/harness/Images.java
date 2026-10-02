package com.michaelgrundvig.frc.spotter.harness;

import com.github.dockerjava.api.exception.NotFoundException;
import com.michaelgrundvig.frc.spotter.json.Json;
import com.michaelgrundvig.frc.spotter.json.JsonValue;
import com.michaelgrundvig.frc.spotter.table.Pack;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.images.builder.ImageFromDockerfile;

/**
 * The images the container tests run, each built once and named by a hash of everything it's built
 * from, so an image already built is reused and a change builds a new one:
 *
 * <ul>
 *   <li>{@link #agent}: Debian 13 under systemd with no Java, the agent installed from its .deb as
 *       a board's image build would (the package's maintainer scripts run with no systemd running,
 *       as in a chroot), and the test packs, a stand-in for the software it watches, and a fixture
 *       tree of USB devices;
 *   <li>{@link #agentOnJava17}: a stock Java 17 with the agent installed from its -all.jar.
 * </ul>
 *
 * <p>Built images are kept (named {@code localhost/spotter-test-*}); a runtime's own prune removes
 * old ones.
 */
final class Images {
  /** Debian 13 (trixie), pinned. */
  static final String DEBIAN =
      "docker.io/library/debian:trixie-20260918-slim@sha256:a99cfc517144bc59b1978475ec53b46ecabec7e43635402ee5b77cc54cd1b20a";

  /** A stock Java 17, for the -all.jar. */
  static final String JAVA_17 = "docker.io/library/eclipse-temurin:17.0.19_10-jre-noble";

  /** The team number the tests' computers are on: 10.99.71.x, unlikely on any test machine. */
  static final int TEAM = 9971;

  private static final Map<String, String> BUILT = new LinkedHashMap<>();

  private Images() {}

  /** The base: Debian 13 under systemd, with polkit and D-Bus, and nothing of Java. */
  static String base() {
    return build(
        "base",
        """
        FROM %s
        ENV container=docker
        RUN apt-get update -qq \\
         && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends \\
              systemd systemd-sysv dbus polkitd init-system-helpers busybox curl ca-certificates \\
         && apt-get clean && rm -rf /var/lib/apt/lists/*
        # A board's image starts nothing it doesn't need; neither does this.
        RUN systemctl mask getty@.service console-getty.service systemd-firstboot.service
        STOPSIGNAL SIGRTMIN+3
        ENTRYPOINT ["/sbin/init"]
        """
            .formatted(DEBIAN),
        Map.of());
  }

  /**
   * The agent's image: the base, the agent installed from its .deb, the test packs (a stand-in for
   * the software it watches, and one probe of each kind), the stand-in's unit, a failing unit, a
   * fixture tree of USB devices at {@code /srv/fixture}, and the stamp.
   */
  static String agent() {
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("package", property("spotter.agentPackage"));
    context.put("standin.json", packJson("standin"));
    context.put("kinds.json", packJson("kinds"));
    context.put("61-standin.rules", resource("61-standin.rules"));
    context.put("vision.service", resource("vision.service"));
    context.put("broken.service", resource("broken.service"));
    context.put("fixture-agent.json", resource("fixture-agent.json"));
    context.put("stamp.json", stamp("vision-front", 11, "v-standin"));
    return build(
        "agent",
        """
        FROM %s
        %s
        # The test packs, as a pack's own install would put them.
        COPY standin.json /usr/lib/frc-coprocessor/packs/standin/pack.json
        COPY kinds.json /usr/lib/frc-coprocessor/packs/kinds/pack.json
        COPY 61-standin.rules /usr/share/polkit-1/rules.d/61-frc-coprocessor-standin.rules
        # The software the stand-in pack watches: a web server on 5800, as a vision program's page is.
        COPY vision.service broken.service /etc/systemd/system/
        RUN mkdir -p /srv/vision/api \\
         && printf '{"status": "up"}' > /srv/vision/api/status.json \\
         && printf '{"version": "v-standin"}' > /srv/vision/api/version.json \\
         && printf 'PK stand-in backup' > /srv/vision/backup.zip \\
         && systemctl enable vision.service broken.service
        # A fixture tree of USB devices, with real links, for an agent run with --root: front-left
        # on a USB 3 port, front-right not plugged in.
        COPY fixture-agent.json /srv/fixture/etc/frc-coprocessor/agent.json
        RUN set -e; f=/srv/fixture; usb=$f/sys/devices/platform/xhci-hcd.0.auto/usb7/7-1 \\
         && mkdir -p $f/dev/v4l/by-path $f/sys/class/video4linux/video0 "$usb/7-1:1.0" \\
              $f/usr/lib/frc-coprocessor/packs \\
         && cp -R /usr/lib/frc-coprocessor/packs/builtin $f/usr/lib/frc-coprocessor/packs/ \\
         && : > $f/dev/video0 \\
         && ln -s ../../video0 "$f/dev/v4l/by-path/platform-xhci-hcd.0.auto-usb-0:1:1.0-video-index0" \\
         && ln -s "../../../devices/platform/xhci-hcd.0.auto/usb7/7-1/7-1:1.0" $f/sys/class/video4linux/video0/device \\
         && echo 5000 > $usb/speed && echo 0c45 > $usb/idVendor && echo 6366 > $usb/idProduct \\
         && echo 'Arducam OV9281 USB Camera' > $usb/product
        COPY stamp.json /etc/coprocessor/stamp.json
        RUN mkdir -p /data/frc-coprocessor
        VOLUME /data
        """
            .formatted(base(), INSTALL_AGENT),
        context);
  }

  /**
   * The -all.jar's image: a stock Java 17 (Temurin's, on Ubuntu 24.04) under systemd, its java on
   * the path as a system Java's is, and nothing else of Java; the agent installed from the -all.jar
   * and its install script.
   */
  static String agentOnJava17() {
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("jar", property("spotter.agentJar"));
    context.put("stamp.json", stamp("vision-front", 21, ""));
    return build(
        "java17",
        """
        FROM %s
        ENV container=docker
        RUN apt-get update -qq \\
         && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends \\
              systemd systemd-sysv dbus polkitd curl \\
         && apt-get clean && rm -rf /var/lib/apt/lists/*
        RUN systemctl mask getty@.service console-getty.service systemd-firstboot.service
        # A system Java is on every unit's path (/usr/bin, by update-alternatives); Temurin's image
        # puts it on the shell's only.
        RUN ln -s /opt/java/openjdk/bin/java /usr/local/bin/java
        # The agent from its -all.jar, as a board with its own Java installs it: no systemd is
        # running here, so it's enabled and not started.
        COPY jar /tmp/agent
        RUN sh /tmp/agent/install.sh && rm -rf /tmp/agent \\
         && test ! -e /usr/lib/frc-coprocessor-agent/runtime
        COPY stamp.json /etc/coprocessor/stamp.json
        RUN mkdir -p /data/frc-coprocessor
        VOLUME /data
        STOPSIGNAL SIGRTMIN+3
        ENTRYPOINT ["/sbin/init"]
        """
            .formatted(JAVA_17),
        context);
  }

  /** How an image installs the agent: its .deb, built from the package's files, then dpkg. */
  static final String INSTALL_AGENT =
      """
      # The agent, installed from its .deb as an image build installs it: no systemd is running
      # here, so its maintainer scripts enable its unit and start nothing.
      COPY package /tmp/frc-coprocessor-agent
      RUN dpkg-deb --root-owner-group --build /tmp/frc-coprocessor-agent /tmp/frc-coprocessor-agent.deb \\
       && dpkg -i /tmp/frc-coprocessor-agent.deb \\
       && rm -rf /tmp/frc-coprocessor-agent /tmp/frc-coprocessor-agent.deb \\
       && ! command -v java
      """;

  /** A stamp, as the image's stamping writes it. */
  static String stamp(String name, int address, String photonVisionVersion) {
    return Json.compact(
        JsonValue.Obj.builder()
            .put("name", name)
            .put("team", TEAM)
            .put("address", address(address))
            .put("board", "orangepi-5")
            .put("cameras", List.of())
            .put("release", "test")
            .put("recipeHash", "")
            .put("photonvisionVersion", photonVisionVersion)
            .put("settingsHash", "")
            .put("agentPort", 5808)
            .build());
  }

  /** An address on the tests' network: 10.99.71.x. */
  static String address(int last) {
    return "10." + TEAM / 100 + "." + TEAM % 100 + "." + last;
  }

  /** A test pack, read from its YAML and written as the agent reads it. */
  static String packJson(String name) {
    String yaml = resource(name + ".yaml");
    return Json.pretty(Pack.parseYaml(yaml, name + ".yaml").toJson());
  }

  /** A resource of the harness's, as text. */
  static String resource(String name) {
    try (InputStream in =
        Objects.requireNonNull(
            Images.class.getResourceAsStream("/harness/" + name), "no resource harness/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static Path property(String name) {
    String value = System.getProperty(name);
    if (value == null) {
      throw new IllegalStateException(name + " isn't set: run the container tests with Gradle");
    }
    return Path.of(value);
  }

  /**
   * Builds an image unless one built from the same Dockerfile and context is there already.
   *
   * @param context each file in the build's context: a {@link Path} (a file or a folder) or text
   */
  static synchronized String build(String kind, String dockerfile, Map<String, Object> context) {
    String name = "localhost/spotter-test-" + kind + ":" + hash(dockerfile, context);
    String built = BUILT.get(name);
    if (built != null) {
      return built;
    }
    boolean present;
    try {
      DockerClientFactory.instance().client().inspectImageCmd(name).exec();
      present = true;
    } catch (NotFoundException e) {
      present = false;
    }
    if (!present) {
      ImageFromDockerfile image =
          new ImageFromDockerfile(name, false).withFileFromString("Dockerfile", dockerfile);
      context.forEach(
          (file, content) -> {
            if (content instanceof Path) {
              image.withFileFromPath(file, (Path) content);
            } else {
              image.withFileFromString(file, (String) content);
            }
          });
      image.get();
    }
    BUILT.put(name, name);
    return name;
  }

  /** A hash of what an image is built from. */
  private static String hash(String dockerfile, Map<String, Object> context) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      digest.update(dockerfile.getBytes(StandardCharsets.UTF_8));
      for (Map.Entry<String, Object> file : context.entrySet()) {
        digest.update(file.getKey().getBytes(StandardCharsets.UTF_8));
        Object content = file.getValue();
        if (content instanceof Path) {
          Path path = (Path) content;
          if (Files.isDirectory(path)) {
            try (Stream<Path> walk = Files.walk(path)) {
              for (Path each : walk.sorted().toList()) {
                digest.update(path.relativize(each).toString().getBytes(StandardCharsets.UTF_8));
                if (Files.isRegularFile(each)) {
                  digest.update(Files.readAllBytes(each));
                  digest.update(Files.isExecutable(each) ? (byte) 1 : (byte) 0);
                }
              }
            }
          } else if (Files.size(path) < (1 << 22)) {
            digest.update(Files.readAllBytes(path));
          } else {
            // A large file is known by its size and time.
            digest.update(
                (Files.size(path) + "@" + Files.getLastModifiedTime(path))
                    .getBytes(StandardCharsets.UTF_8));
          }
        } else {
          digest.update(((String) content).getBytes(StandardCharsets.UTF_8));
        }
      }
      return HexFormat.of().formatHex(digest.digest()).substring(0, 16);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
