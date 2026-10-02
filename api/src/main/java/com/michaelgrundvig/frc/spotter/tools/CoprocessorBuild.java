package com.michaelgrundvig.frc.spotter.tools;

import com.michaelgrundvig.frc.spotter.json.Json;
import com.michaelgrundvig.frc.spotter.probes.ProbeSet;
import com.michaelgrundvig.frc.spotter.table.AgentConfig;
import com.michaelgrundvig.frc.spotter.table.CompiledTable;
import com.michaelgrundvig.frc.spotter.table.Computer;
import com.michaelgrundvig.frc.spotter.table.Pack;
import com.michaelgrundvig.frc.spotter.table.Packs;
import com.michaelgrundvig.frc.spotter.table.Table;
import com.michaelgrundvig.frc.spotter.table.TableException;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The build's coprocessor tasks, run by a robot's build with this project's code, so the build
 * checks exactly what the robot and the agents do:
 *
 * <ul>
 *   <li>{@code table <repository> <out.json>}: compiles the coprocessor table for the robot
 *       program, with each computer's probes ({@link CompiledTable});
 *   <li>{@code probes <repository> <out-dir>}: compiles each computer's probe definitions from the
 *       table and the packs it names ({@link Packs}), as its agent will, and writes them to {@code
 *       <out-dir>/<computer>.json}, printing each one's hash;
 *   <li>{@code agent-configs <repository> <out-dir>}: writes each computer's agent configuration
 *       ({@link AgentConfig}) to {@code <out-dir>/<computer>.json}, for its image;
 *   <li>{@code pack <pack.yaml> <out.json>}: checks a pack and writes its {@code pack.json}, as its
 *       folder carries it on a computer.
 * </ul>
 *
 * <p>A failure prints what's wrong and exits with status 1.
 */
public final class CoprocessorBuild {
  private CoprocessorBuild() {}

  /** Runs one task; see the class. */
  public static void main(String[] args) throws IOException {
    int status = run(args, System.out, System.err);
    if (status != 0) {
      System.exit(status);
    }
  }

  /** Runs one task, printing to {@code out} and {@code err}; the exit status. */
  static int run(String[] args, PrintStream out, PrintStream err) throws IOException {
    try {
      if (args.length == 3 && args[0].equals("table")) {
        Path target = Path.of(args[2]);
        Files.createDirectories(target.toAbsolutePath().getParent());
        CompiledTable table = compile(Path.of(args[1]));
        Files.writeString(target, Json.pretty(table.toJson()), StandardCharsets.UTF_8);
        return 0;
      }
      if (args.length == 3 && args[0].equals("probes")) {
        Path root = Path.of(args[1]);
        Path target = Path.of(args[2]);
        Files.createDirectories(target);
        Map<String, ProbeSet> sets = probeSets(root, readTable(root));
        for (Map.Entry<String, ProbeSet> set : sets.entrySet()) {
          Files.writeString(
              target.resolve(set.getKey() + ".json"),
              set.getValue().text(),
              StandardCharsets.UTF_8);
          out.println(set.getValue().hash() + "  " + set.getKey() + ".json");
        }
        return 0;
      }
      if (args.length == 3 && args[0].equals("agent-configs")) {
        Path root = Path.of(args[1]);
        Path target = Path.of(args[2]);
        Files.createDirectories(target);
        Table table = readTable(root);
        for (Computer computer : table.computers()) {
          Files.writeString(
              target.resolve(computer.name() + ".json"),
              AgentConfig.of(table, computer).text(),
              StandardCharsets.UTF_8);
        }
        return 0;
      }
      if (args.length == 3 && args[0].equals("pack")) {
        Path file = Path.of(args[1]);
        Pack pack;
        try {
          pack = Pack.parseYaml(Files.readString(file, StandardCharsets.UTF_8), file.toString());
        } catch (TableException e) {
          throw new IllegalArgumentException(e.getMessage(), e);
        }
        Path target = Path.of(args[2]);
        Files.createDirectories(target.toAbsolutePath().getParent());
        Files.writeString(target, Json.pretty(pack.toJson()), StandardCharsets.UTF_8);
        return 0;
      }
      err.println(
          "Usage: table <repository> <out.json> | probes <repository> <out-dir>"
              + " | agent-configs <repository> <out-dir> | pack <pack.yaml> <out.json>");
      return 2;
    } catch (IllegalArgumentException e) {
      err.println(e.getMessage());
      return 1;
    }
  }

  /** The compiled table for the repository at {@code root}. */
  static CompiledTable compile(Path root) throws IOException {
    Table table = readTable(root);
    return new CompiledTable(table, "", "", Map.of(), probeSets(root, table));
  }

  /** The table in the repository, checked; a problem is an {@link IllegalArgumentException}. */
  static Table readTable(Path root) throws IOException {
    try {
      return Table.readYaml(root.resolve(Table.PATH));
    } catch (TableException e) {
      throw new IllegalArgumentException(e.getMessage(), e);
    }
  }

  /**
   * Each computer's probe definitions: the built-in pack's, the packs it names, and its own from
   * the table; a problem is an {@link IllegalArgumentException} listing them all.
   */
  static Map<String, ProbeSet> probeSets(Path root, Table table) throws IOException {
    Map<String, ProbeSet> sets = new LinkedHashMap<>();
    Map<String, Pack> packs = new LinkedHashMap<>();
    try {
      for (Computer computer : table.computers()) {
        List<Pack> named = new ArrayList<>();
        for (String name : prepend(Pack.BUILTIN, computer.packs())) {
          Pack pack = packs.get(name);
          if (pack == null) {
            pack = Pack.read(root, name);
            packs.put(name, pack);
          }
          named.add(pack);
        }
        sets.put(computer.name(), Packs.compile(AgentConfig.of(table, computer), named));
      }
    } catch (TableException e) {
      throw new IllegalArgumentException(e.getMessage(), e);
    }
    return sets;
  }

  private static List<String> prepend(String first, List<String> rest) {
    List<String> all = new ArrayList<>();
    all.add(first);
    all.addAll(rest);
    return all;
  }
}
