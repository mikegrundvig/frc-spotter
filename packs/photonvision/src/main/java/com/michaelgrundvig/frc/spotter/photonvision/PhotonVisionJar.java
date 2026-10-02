package com.michaelgrundvig.frc.spotter.photonvision;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * PhotonVision's version, read from its jar: the constant {@code PhotonVersion.versionString} that
 * PhotonVision itself reports, and that PhotonLib on the robot must match. The pinned build has no
 * page that says its version over HTTP, and the jar it runs from is on the read-only root, so the
 * jar says what's running. Read from the class file's constants, without loading PhotonVision.
 */
final class PhotonVisionJar {
  /** The class PhotonVision keeps its version in. */
  static final String VERSION_CLASS = "org/photonvision/PhotonVersion.class";

  /** The constant that holds it. */
  static final String VERSION_FIELD = "versionString";

  /** The largest class file read: PhotonVersion is about 1 KB. */
  static final int MAX_CLASS_BYTES = 64 * 1024;

  private PhotonVisionJar() {}

  /** The version a PhotonVision jar carries. */
  static String version(Path jar) throws IOException {
    try (ZipFile zip = new ZipFile(jar.toFile())) {
      ZipEntry entry = zip.getEntry(VERSION_CLASS);
      if (entry == null) {
        throw new IOException(jar + " has no " + VERSION_CLASS + ": is it PhotonVision's jar?");
      }
      byte[] bytes;
      try (InputStream in = zip.getInputStream(entry)) {
        bytes = in.readNBytes(MAX_CLASS_BYTES + 1);
      }
      if (bytes.length > MAX_CLASS_BYTES) {
        throw new IOException(VERSION_CLASS + " is larger than a version's class");
      }
      return constant(bytes, VERSION_FIELD);
    }
  }

  /**
   * A static string constant's value, from a class file: the field's ConstantValue attribute, which
   * names a string in the constant pool.
   */
  static String constant(byte[] classFile, String field) throws IOException {
    DataInputStream in = new DataInputStream(new ByteArrayInputStream(classFile));
    if (in.readInt() != 0xCAFEBABE) {
      throw new IOException("not a class file");
    }
    in.readUnsignedShort(); // minor version
    in.readUnsignedShort(); // major version
    int count = in.readUnsignedShort();
    String[] utf8 = new String[count];
    int[] strings = new int[count];
    for (int i = 1; i < count; i++) {
      int tag = in.readUnsignedByte();
      switch (tag) {
        case 1 -> utf8[i] = in.readUTF();
        case 8 -> strings[i] = in.readUnsignedShort();
        case 7, 16, 19, 20 -> in.skipNBytes(2);
        case 15 -> in.skipNBytes(3);
        case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipNBytes(4);
        case 5, 6 -> {
          in.skipNBytes(8);
          i++; // a long or double takes two entries
        }
        default -> throw new IOException("unknown constant pool tag " + tag);
      }
    }
    in.readUnsignedShort(); // access flags
    in.readUnsignedShort(); // this class
    in.readUnsignedShort(); // super class
    in.skipNBytes(2L * in.readUnsignedShort()); // interfaces
    int fields = in.readUnsignedShort();
    for (int f = 0; f < fields; f++) {
      in.readUnsignedShort(); // access flags
      String name = utf8[in.readUnsignedShort()];
      in.readUnsignedShort(); // descriptor
      int attributes = in.readUnsignedShort();
      for (int a = 0; a < attributes; a++) {
        String attribute = utf8[in.readUnsignedShort()];
        int length = in.readInt();
        if (field.equals(name) && "ConstantValue".equals(attribute) && length == 2) {
          int index = in.readUnsignedShort();
          String value = index > 0 && index < count ? utf8[strings[index]] : null;
          if (value == null) {
            throw new IOException(field + " isn't a string constant");
          }
          return value;
        }
        in.skipNBytes(length);
      }
    }
    throw new IOException("no constant " + field);
  }
}
