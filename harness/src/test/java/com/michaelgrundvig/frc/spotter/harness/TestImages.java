package com.michaelgrundvig.frc.spotter.harness;

import com.michaelgrundvig.frc.spotter.json.Json;
import com.michaelgrundvig.frc.spotter.table.Pack;
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
 *       and the test packs, a stand-in for the software it watches, and a fixture tree of USB
 *       devices;
 *   <li>{@link #agentOnJava17}: a stock Java 17 with the agent installed from its -all.jar, and the
 *       example pack with a Java helper.
 * </ul>
 */
final class TestImages {
  private TestImages() {}

  /**
   * The agent's image: the base, the agent installed from its .deb, the test packs (a stand-in for
   * the software it watches, and one probe of each kind), the stand-in's unit, a failing unit, a
   * fixture tree of USB devices at {@code /srv/fixture}, and the stamp.
   */
  static String agent() {
    Map<String, Object> context = new LinkedHashMap<>();
    String installAgent = Images.installAgent(context);
    context.put("standin.json", packJson("standin"));
    context.put("kinds.json", packJson("kinds"));
    context.put("61-standin.rules", resource("61-standin.rules"));
    context.put("vision.service", resource("vision.service"));
    context.put("broken.service", resource("broken.service"));
    context.put("fixture-agent.json", resource("fixture-agent.json"));
    context.put(
        "stamp.json", Images.stamp("vision-front", 11, Map.of("standinVersion", "v-standin")));
    return Images.build(
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
            .formatted(Images.base(), installAgent),
        context);
  }

  /**
   * The -all.jar's image: a stock Java 17 (Temurin's, on Ubuntu 24.04) under systemd, its java on
   * the path as a system Java's is, and nothing else of Java; the agent installed from the -all.jar
   * and its install script, and the example pack with a Java helper from its own.
   */
  static String agentOnJava17() {
    Map<String, Object> context = new LinkedHashMap<>();
    context.put("jar", Images.property("spotter.agentJar"));
    context.put("pack", Images.property("spotter.javaHelperPack"));
    context.put("stamp.json", Images.stamp("vision-front", 21, Map.of()));
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
         && test ! -e /usr/lib/frc-coprocessor-agent/runtime
        COPY pack /tmp/pack
        RUN sh /tmp/pack/install.sh && rm -rf /tmp/pack
        COPY stamp.json /etc/coprocessor/stamp.json
        RUN mkdir -p /data/frc-coprocessor
        VOLUME /data
        STOPSIGNAL SIGRTMIN+3
        ENTRYPOINT ["/sbin/init"]
        """
            .formatted(Images.JAVA_17),
        context);
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
            TestImages.class.getResourceAsStream("/harness/" + name),
            "no resource harness/" + name)) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
