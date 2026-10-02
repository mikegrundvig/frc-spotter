package com.michaelgrundvig.frc.spotter.harness;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The images Spotter's own container tests run, on the harness's {@link Images}:
 *
 * <ul>
 *   <li>{@link #agent}: Debian 13 under systemd with no Java, the agent installed from its .deb,
 *       the test packs, a stand-in for the software it watches, its image labeled in os-release,
 *       and a fixture tree of USB devices;
 *   <li>{@link #agentOnJava17}: a stock Java 17 with the agent installed from its -all.jar.
 * </ul>
 */
final class TestImages {
  private TestImages() {}

  /** The labels the agent's image carries in its os-release, as an image builder writes them. */
  static final Map<String, String> LABELS =
      Map.of("IMAGE_ID", "spotter-test", "IMAGE_VERSION", "7", "TEST_STANDIN_VERSION", "v-standin");

  /**
   * The agent's image: the base, the agent installed from its .deb, the test packs (a stand-in for
   * the software it watches, and one probe of each kind) with two that it mustn't trust (one
   * nobody's, one its group may write), the stand-in's unit, a failing unit, its labels in
   * os-release, and a fixture tree of USB devices at {@code /srv/fixture} with a pack of their
   * ports.
   */
  static String agent() {
    Map<String, Object> context = new LinkedHashMap<>();
    String installAgent = Images.installAgent(context);
    Map<String, String> packs = new LinkedHashMap<>();
    packs.put("standin", resource("standin.yaml"));
    packs.put("kinds", resource("kinds.yaml"));
    String installPacks = Images.installPacks(context, packs);
    context.put("vision.service", resource("vision.service"));
    context.put("broken.service", resource("broken.service"));
    context.put("usb.yaml", resource("usb.yaml"));
    StringBuilder labels = new StringBuilder();
    LABELS.forEach((key, value) -> labels.append(key).append("=\"").append(value).append("\"\\n"));
    return Images.build(
        "agent",
        """
        FROM %s
        %s
        # The test packs, copied in as a team copies a pack.
        %s
        # Two packs it mustn't trust: nobody's, and one its group may write.
        RUN printf 'pack: mine\\n' > /etc/frc-spotter/packs/mine.yaml \\
         && chown nobody /etc/frc-spotter/packs/mine.yaml \\
         && printf 'pack: shared\\n' > /etc/frc-spotter/packs/shared.yaml \\
         && chmod 0664 /etc/frc-spotter/packs/shared.yaml
        # The image's labels, in os-release as an image builder writes them.
        RUN printf '%s' >> /usr/lib/os-release
        # The software the stand-in pack watches: a web server on 5800, as a vision program's page is.
        COPY vision.service broken.service /etc/systemd/system/
        RUN mkdir -p /srv/vision/api \\
         && printf '{"status": "up"}' > /srv/vision/api/status.json \\
         && printf '{"version": "v-standin"}' > /srv/vision/api/version.json \\
         && printf 'PK stand-in backup' > /srv/vision/backup.zip \\
         && systemctl enable vision.service broken.service
        # A fixture tree of USB devices, with real links, for an agent run with --root: front-left
        # on a USB 3 port, front-right not plugged in; and a pack of their ports.
        COPY usb.yaml /srv/fixture/etc/frc-spotter/packs/usb.yaml
        RUN set -e; f=/srv/fixture; usb=$f/sys/devices/platform/xhci-hcd.0.auto/usb7/7-1 \\
         && chmod 0644 $f/etc/frc-spotter/packs/usb.yaml \\
         && mkdir -p $f/dev/v4l/by-path $f/sys/class/video4linux/video0 "$usb/7-1:1.0" \\
         && : > $f/dev/video0 \\
         && ln -s ../../video0 "$f/dev/v4l/by-path/platform-xhci-hcd.0.auto-usb-0:1:1.0-video-index0" \\
         && ln -s "../../../devices/platform/xhci-hcd.0.auto/usb7/7-1/7-1:1.0" $f/sys/class/video4linux/video0/device \\
         && echo 5000 > $usb/speed && echo 0c45 > $usb/idVendor && echo 6366 > $usb/idProduct \\
         && echo 'Arducam OV9281 USB Camera' > $usb/product
        RUN mkdir -p /data/frc-spotter
        VOLUME /data
        """
            .formatted(Images.base(), installAgent, installPacks, labels),
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
    String installPacks = Images.installPacks(context, Map.of("standin", resource("standin.yaml")));
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
}
