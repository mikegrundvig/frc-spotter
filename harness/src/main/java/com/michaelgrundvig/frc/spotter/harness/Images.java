package com.michaelgrundvig.frc.spotter.harness;

import com.github.dockerjava.api.exception.NotFoundException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.images.builder.ImageFromDockerfile;

/**
 * Images for container tests of a coprocessor's agent and its packs, each built once and named by a
 * hash of everything it's built from, so an image already built is reused and a change builds a new
 * one. {@link #base} is Debian 13 under systemd with no Java; {@link #installAgent} adds the lines
 * that install the agent from its .deb, as a board's image build would (the package's maintainer
 * scripts run with no systemd running, as in a chroot), and {@link #installPacks} the lines that
 * copy packs in as a team does. A pack's tests build their own image on these, with their software
 * and their pack installed.
 *
 * <p>The agent's package comes from the build: {@code spotter.agentDeb} (a .deb, as Spotter
 * releases it), or {@code spotter.agentPackage} (its files as installed, with {@code DEBIAN/},
 * built into a .deb in the image). Built images are kept (named {@code localhost/spotter-test-*});
 * a runtime's own prune removes old ones.
 */
public final class Images {
  /** Debian 13 (trixie), pinned. */
  public static final String DEBIAN =
      "docker.io/library/debian:trixie-20260918-slim@sha256:a99cfc517144bc59b1978475ec53b46ecabec7e43635402ee5b77cc54cd1b20a";

  /** A stock Java 17, for the -all.jar. */
  public static final String JAVA_17 = "docker.io/library/eclipse-temurin:17.0.19_10-jre-noble";

  /** The team number the tests' computers are on: 10.99.71.x, unlikely on any test machine. */
  public static final int TEAM = 9971;

  private static final Map<String, String> BUILT = new LinkedHashMap<>();

  private Images() {}

  /** The base: Debian 13 under systemd, with polkit and D-Bus, and nothing of Java. */
  public static String base() {
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
   * The Dockerfile lines that install the agent from its .deb, as an image build installs it: no
   * systemd is running there, so its maintainer scripts enable its unit and start nothing. Adds
   * what they copy to the build's {@code context}.
   */
  public static String installAgent(Map<String, Object> context) {
    String deb = System.getProperty("spotter.agentDeb");
    if (deb != null) {
      context.put("frc-spotter.deb", Path.of(deb));
      return """
          COPY frc-spotter.deb /tmp/frc-spotter.deb
          RUN dpkg -i /tmp/frc-spotter.deb && rm /tmp/frc-spotter.deb \\
           && ! command -v java
          """;
    }
    context.put("package", property("spotter.agentPackage"));
    return """
        COPY package /tmp/frc-spotter
        RUN dpkg-deb --root-owner-group --build /tmp/frc-spotter /tmp/frc-spotter.deb \\
         && dpkg -i /tmp/frc-spotter.deb \\
         && rm -rf /tmp/frc-spotter /tmp/frc-spotter.deb \\
         && ! command -v java
        """;
  }

  /**
   * The Dockerfile lines that copy packs into {@code /etc/frc-spotter/packs/} as a team does: each
   * {@code <name>.yaml}, root's, and written by root alone, so the agent trusts it. Adds them to
   * the build's {@code context}.
   *
   * @param packs each pack's YAML, by the name its file takes
   */
  public static String installPacks(Map<String, Object> context, Map<String, String> packs) {
    StringBuilder lines = new StringBuilder();
    packs.forEach(
        (name, yaml) -> {
          context.put("pack-" + name + ".yaml", yaml);
          lines
              .append("COPY pack-")
              .append(name)
              .append(".yaml /etc/frc-spotter/packs/")
              .append(name)
              .append(".yaml\n");
        });
    if (!packs.isEmpty()) {
      lines.append(
          "RUN chown root:root /etc/frc-spotter/packs/*.yaml"
              + " && chmod 0644 /etc/frc-spotter/packs/*.yaml\n");
    }
    return lines.toString();
  }

  /** An address on the tests' network: 10.99.71.x. */
  public static String address(int last) {
    return "10." + TEAM / 100 + "." + TEAM % 100 + "." + last;
  }

  /** A path the build gives the tests as a system property. */
  public static Path property(String name) {
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
  public static synchronized String build(
      String kind, String dockerfile, Map<String, Object> context) {
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
