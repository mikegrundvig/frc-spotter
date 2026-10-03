package com.michaelgrundvig.frc.spotter.harness;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The images Spotter's own container tests run, on the harness's {@link Images}:
 *
 * <ul>
 *   <li>{@link #agent}: Debian 13 under systemd with no Java, the agent installed from its .deb,
 *       the stand-in pack and three it mustn't trust, a stand-in for the software it watches, and
 *       its image labeled in os-release;
 *   <li>{@link #agentOnJava17}: a stock Java 17 with the agent installed from its -all.jar;
 *   <li>{@link #catalog}: the agent with the catalog's packs (the repository's packs/) installed as
 *       an installer puts them, the debian pack's root timer enabled, a stand-in for PhotonVision,
 *       and stand-ins for programs the packs' scripts read, off the PATH.
 * </ul>
 */
final class TestImages {
  private TestImages() {}

  /** The labels the agent's image carries in its os-release, as an image builder writes them. */
  static final Map<String, String> LABELS =
      Map.of("IMAGE_ID", "spotter-test", "IMAGE_VERSION", "7", "TEST_STANDIN_VERSION", "v-standin");

  /**
   * The agent's image: the base, the agent installed from its .deb, the stand-in pack with three it
   * mustn't trust (one nobody's, one its group may write, one whose program is nobody's), the
   * stand-in's unit, a failing unit, and its labels in os-release.
   */
  static String agent() {
    Map<String, Object> context = new LinkedHashMap<>();
    String installAgent = Images.installAgent(context);
    String installPacks = Images.installPacks(context, Map.of("standin", folder("packs/standin")));
    context.put("vision.service", resource("vision.service"));
    context.put("broken.service", resource("broken.service"));
    StringBuilder labels = new StringBuilder();
    LABELS.forEach((key, value) -> labels.append(key).append("=\"").append(value).append("\"\\n"));
    return Images.build(
        "agent",
        """
        FROM %s
        %s
        # The stand-in pack, put in place as an installer puts a pack.
        %s
        # Three packs it mustn't trust: nobody's, one its group may write, and one whose program
        # nobody may change but nobody.
        RUN cd /etc/frc-spotter/packs \\
         && mkdir mine shared program \\
         && printf 'pack: mine\\n' > mine/pack.yaml && chown nobody mine/pack.yaml \\
         && printf 'pack: shared\\n' > shared/pack.yaml && chmod 0664 shared/pack.yaml \\
         && printf 'pack: program\\ncollectors:\\n  - id: check\\n    run: [./check]\\n    every: 1s\\n    fields:\\n      ok: {type: boolean}\\n' > program/pack.yaml \\
         && printf '#!/bin/sh\\necho true\\n' > program/check \\
         && chmod 0755 program/check && chown nobody program/check
        # The image's labels, in os-release as an image builder writes them.
        RUN printf '%s' >> /usr/lib/os-release
        # The software the stand-in pack watches: a web server on 5800, as a vision program's page is.
        COPY vision.service broken.service /etc/systemd/system/
        RUN mkdir -p /srv/vision/api \\
         && printf '{"state": "up"}' > /srv/vision/api/status.json \\
         && systemctl enable vision.service broken.service
        RUN mkdir -p /data/frc-spotter
        VOLUME /data
        """
            .formatted(Images.base(), installAgent, installPacks, labels),
        context);
  }

  /** The catalog's packs, from the build. */
  static final Path CATALOG = Images.property("spotter.catalog");

  /**
   * The agent with the catalog's packs, as a board's installer puts them: each pack's folder in
   * /etc/frc-spotter/packs, root's; the debian pack's root timer copied to /etc/systemd/system and
   * enabled, as its README has an installer do; PhotonVision's service as a stand-in answering on
   * 5800 as PhotonVision does, with a settings file; and stand-ins for nvme-cli, chronyc,
   * timedatectl and journalctl in /opt/stand-ins, off the PATH, for the scripts' parsing.
   */
  static String catalog() {
    Map<String, Object> context = new LinkedHashMap<>();
    String installAgent = Images.installAgent(context);
    Map<String, Path> packs = new LinkedHashMap<>();
    for (String name : new String[] {"debian", "photonvision", "raspberry-pi"}) {
      packs.put(name, catalogPack(name));
    }
    String installPacks = Images.installPacks(context, packs);
    context.put("stand-ins", folder("catalog"));
    return Images.build(
        "catalog",
        """
        FROM %s
        %s
        # The catalog's packs, put in place as an installer puts packs; their READMEs and units
        # aren't programs.
        %s
        RUN find /etc/frc-spotter/packs \\( -name '*.md' -o -name '*.service' -o -name '*.timer' \\) \\
              -exec chmod 0644 {} +
        # The debian pack's root timer, as its README has an installer add it: root runs a copy
        # of its own, outside both pack folders.
        RUN cd /etc/frc-spotter/packs \\
         && install -D -o root -g root -m 0755 debian/drive-health \\
              /usr/local/lib/frc-spotter-debian/drive-health \\
         && install -o root -g root -m 0644 debian/frc-spotter-debian-drive.service \\
              debian/frc-spotter-debian-drive.timer /etc/systemd/system/ \\
         && systemctl enable frc-spotter-debian-drive.timer
        # A stand-in for PhotonVision, by its service's name, answering on 5800, with its settings.
        COPY stand-ins /opt/stand-ins
        RUN mv /opt/stand-ins/photonvision.service /etc/systemd/system/ \\
         && chmod 0644 /etc/systemd/system/photonvision.service && chmod 0755 /opt/stand-ins/* \\
         && systemctl enable photonvision.service \\
         && mkdir -p /opt/photonvision/photonvision_config \\
         && printf 'its settings' > /opt/photonvision/photonvision_config/photon.sqlite \\
         && chmod 0644 /opt/photonvision/photonvision_config/photon.sqlite
        RUN mkdir -p /data/frc-spotter
        VOLUME /data
        """
            .formatted(Images.base(), installAgent, installPacks),
        context);
  }

  /** A catalog pack's own files (not a build's output), in a folder of their own. */
  static Path catalogPack(String name) {
    try {
      Path from = CATALOG.resolve(name);
      Path to = java.nio.file.Files.createTempDirectory("spotter-catalog-").resolve(name);
      try (java.util.stream.Stream<Path> files = java.nio.file.Files.walk(from)) {
        for (Path file : files.filter(java.nio.file.Files::isRegularFile).toList()) {
          Path relative = from.relativize(file);
          if (relative.startsWith("build")) {
            continue;
          }
          Path target = to.resolve(relative);
          java.nio.file.Files.createDirectories(target.getParent());
          java.nio.file.Files.copy(file, target);
          target.toFile().setExecutable(java.nio.file.Files.isExecutable(file), false);
        }
      }
      return to;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** The agent alone: the base, the agent installed from its .deb, and no packs. */
  static String packLess() {
    Map<String, Object> context = new LinkedHashMap<>();
    String installAgent = Images.installAgent(context);
    return Images.build(
        "packless",
        """
        FROM %s
        %s
        RUN mkdir -p /data/frc-spotter
        VOLUME /data
        """
            .formatted(Images.base(), installAgent),
        context);
  }

  /**
   * The agent's image with its settings, {@code /etc/frc-spotter/agent.json}, as the board's
   * installer writes them: root's, readable by the agent.
   */
  static String agentWith(String agentJson) {
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("agent.json", agentJson);
    return Images.build(
        "agent-config",
        """
        FROM %s
        COPY agent.json /etc/frc-spotter/agent.json
        RUN chmod 0644 /etc/frc-spotter/agent.json
        """
            .formatted(agent()),
        context);
  }

  /**
   * The -all.jar's image: a stock Java 17 (Temurin's, on Ubuntu 24.04) under systemd, its java on
   * the path as a system Java's is, and nothing else of Java; the agent installed from the -all.jar
   * and its install script, and the stand-in's pack, whose software isn't there.
   */
  static String agentOnJava17() {
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("jar", Images.property("spotter.agentJar"));
    String installPacks = Images.installPacks(context, Map.of("standin", folder("packs/standin")));
    return Images.build(
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
         && test ! -e /usr/lib/frc-spotter/runtime
        %s
        RUN mkdir -p /data/frc-spotter
        VOLUME /data
        STOPSIGNAL SIGRTMIN+3
        ENTRYPOINT ["/sbin/init"]
        """
            .formatted(Images.JAVA_17, installPacks),
        context);
  }

  /** A resource of the harness's, as text. */
  static String resource(String name) {
    try (InputStream in =
        Objects.requireNonNull(
            TestImages.class.getResourceAsStream("/harness/" + name),
            "no resource harness/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** A folder of the harness's resources, as the build put it on disk. */
  static Path folder(String name) {
    URL url =
        Objects.requireNonNull(
            TestImages.class.getResource("/harness/" + name), "no resource harness/" + name);
    try {
      return Path.of(url.toURI());
    } catch (URISyntaxException e) {
      throw new IllegalStateException(e);
    }
  }
}
