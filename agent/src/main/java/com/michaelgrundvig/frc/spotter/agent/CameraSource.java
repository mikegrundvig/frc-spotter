package com.michaelgrundvig.frc.spotter.agent;

import com.michaelgrundvig.frc.spotter.api.Cameras;
import com.michaelgrundvig.frc.spotter.api.ExpectedCamera;
import com.michaelgrundvig.frc.spotter.api.UsbCamera;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The cameras plugged in, by the stable path PhotonVision matches them by ({@code
 * /dev/v4l/by-path/...-video-index0}), each with its USB port and link speed; and the computer's
 * cameras, each against where its settings say it should be.
 */
final class CameraSource {
  static final String BY_PATH = "/dev/v4l/by-path";
  static final String VIDEO = "/sys/class/video4linux";
  static final int MAX_CAMERAS = 16;

  private final Host host;

  CameraSource(Host host) {
    this.host = host;
  }

  /**
   * The cameras, against those expected.
   *
   * @param expected the computer's cameras (its role), in order
   * @param usbPaths where PhotonVision's settings expect each camera, by camera name
   */
  Cameras read(List<String> expected, Map<String, String> usbPaths) throws IOException {
    List<UsbCamera> present = present();
    List<ExpectedCamera> cameras = new ArrayList<>();
    for (String name : expected) {
      String path = usbPaths.getOrDefault(name, "");
      boolean there =
          !path.isEmpty() && present.stream().anyMatch(camera -> camera.path().equals(path));
      cameras.add(new ExpectedCamera(name, path, there));
    }
    return new Cameras(cameras, present);
  }

  /** Every USB camera plugged in: each video device's first node by its stable path. */
  List<UsbCamera> present() throws IOException {
    List<UsbCamera> cameras = new ArrayList<>();
    for (String name : host.list(BY_PATH)) {
      if (!name.endsWith("-video-index0") || cameras.size() >= MAX_CAMERAS) {
        continue;
      }
      cameras.add(camera(BY_PATH + "/" + name));
    }
    return cameras;
  }

  /**
   * A camera by its stable path, with what its USB device says; just its path when that can't be
   * followed (unplugged as it was read, or not a USB device), since it's plugged in all the same.
   */
  private UsbCamera camera(String path) {
    try {
      String node = host.resolve(path);
      String video = node.substring(node.lastIndexOf('/') + 1);
      // The video device's USB interface (7-1:1.0), whose parent is the USB device (7-1).
      String usbInterface = host.resolve(VIDEO + "/" + video + "/device");
      String device = usbInterface.substring(0, usbInterface.lastIndexOf('/'));
      return new UsbCamera(
          path,
          device.substring(device.lastIndexOf('/') + 1),
          host.line(device + "/speed").map(CameraSource::speed).orElse(0.0),
          host.line(device + "/idVendor").orElse(""),
          host.line(device + "/idProduct").orElse(""),
          host.line(device + "/product").orElse(""));
    } catch (IOException e) {
      return new UsbCamera(path, "", 0, "", "", "");
    }
  }

  private static double speed(String mbps) {
    try {
      return Double.parseDouble(mbps);
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
