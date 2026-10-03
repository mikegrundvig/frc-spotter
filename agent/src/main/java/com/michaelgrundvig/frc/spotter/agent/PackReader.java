package com.michaelgrundvig.frc.spotter.agent;

import com.michaelgrundvig.frc.spotter.protocol.Spotter;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.lowlevel.Compose;
import org.snakeyaml.engine.v2.exceptions.MarkedYamlEngineException;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;
import org.snakeyaml.engine.v2.nodes.MappingNode;
import org.snakeyaml.engine.v2.nodes.Node;
import org.snakeyaml.engine.v2.nodes.NodeTuple;
import org.snakeyaml.engine.v2.nodes.ScalarNode;
import org.snakeyaml.engine.v2.nodes.SequenceNode;
import org.snakeyaml.engine.v2.nodes.Tag;
import org.snakeyaml.engine.v2.schema.CoreSchema;

/**
 * Reads a pack's {@code pack.yaml} (YAML 1.2's core schema, with snakeyaml-engine) into a {@link
 * Pack}, every problem with its file and line. Everything is checked as it's read, so a mistake
 * shows at its line, and a key nobody reads is a problem, so a misspelling can't pass silently.
 */
final class PackReader {
  /** What a pack's name may be: its folder's. */
  static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,63}");

  /** What a collector's, field's, log's or action's name may be. */
  static final Pattern ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

  /** A duration: a number and its unit, {@code 250ms}, {@code 2s}, {@code 10m}, {@code 1h}. */
  static final Pattern DURATION = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)(ms|s|m|h)");

  /** A number, as a limit or a comparison writes one. */
  static final Pattern NUMBER =
      Pattern.compile("[-+]?([0-9]+(\\.[0-9]*)?|\\.[0-9]+)([eE][-+]?[0-9]+)?");

  /** The shortest a collector's interval may be, and the longest. */
  static final Duration MIN_EVERY = Duration.ofMillis(100);

  static final Duration MAX_EVERY = Duration.ofDays(1);

  /** The longest a collector's timeout may be. */
  static final Duration MAX_COLLECTOR_TIMEOUT = Duration.ofHours(1);

  static final Set<String> PACK_KEYS = Set.of("pack", "version", "collectors", "logs", "actions");
  static final Set<String> COLLECTOR_KEYS =
      Set.of("id", "run", "http", "file", "every", "timeout", "fields");
  static final Set<String> LOG_KEYS = Set.of("id", "label", "run", "http", "map");
  static final Set<String> MAP_KEYS = Set.of("time", "level", "source", "message", "cursor");
  static final Set<String> ACTION_KEYS =
      Set.of(
          "id",
          "label",
          "description",
          "run",
          "http",
          "input",
          "confirm",
          "timeout",
          "whileEnabled",
          "response");
  static final Set<String> FIELD_KEYS = Set.of("label", "type", "unit", "warn", "fail", "name");
  static final Set<String> LIMIT_KEYS = Set.of("above", "below", "equals", "notEquals", "missing");

  /** The types of field, by the names a pack writes. */
  static final Map<String, Spotter.FieldType> TYPES =
      Map.of(
          "number", Spotter.FieldType.FIELD_TYPE_NUMBER,
          "text", Spotter.FieldType.FIELD_TYPE_TEXT,
          "boolean", Spotter.FieldType.FIELD_TYPE_BOOLEAN,
          "status", Spotter.FieldType.FIELD_TYPE_STATUS,
          "json", Spotter.FieldType.FIELD_TYPE_JSON,
          "file", Spotter.FieldType.FIELD_TYPE_FILE);

  /** The types a value may have: {@code json} and {@code file} occur only in responses. */
  static final Set<String> VALUE_TYPES = Set.of("number", "text", "boolean", "status");

  /** The inputs an action may take. */
  static final Map<String, Spotter.Input> INPUTS =
      Map.of(
          "none", Spotter.Input.INPUT_NONE,
          "text", Spotter.Input.INPUT_TEXT,
          "file", Spotter.Input.INPUT_FILE);

  private final String source;
  private final List<String> problems = new ArrayList<>();

  private PackReader(String source) {
    this.source = source;
  }

  /**
   * Reads and checks a pack.
   *
   * @param yaml its {@code pack.yaml}
   * @param folder its folder on the coprocessor, absolute: its last part is its name
   * @param pushed whether the robot pushed it
   * @throws PackException listing every problem, each with its file and line
   */
  static Pack read(String yaml, String folder, boolean pushed) {
    String source = folder + "/" + Pack.FILE;
    PackReader reader = new PackReader(source);
    Optional<Node> root;
    try {
      LoadSettings settings = settings(source);
      // Loaded first, so snakeyaml-engine refuses what YAML 1.2 does, a duplicate key among them
      // (which only loading checks); then composed, for its nodes' lines and text as written.
      new Load(settings).loadFromString(yaml);
      root = new Compose(settings).composeString(yaml);
    } catch (MarkedYamlEngineException e) {
      throw new PackException(
          List.of(
              source
                  + e.getProblemMark().map(m -> ":" + (m.getLine() + 1)).orElse("")
                  + ": "
                  + oneLine(e.getProblem())));
    } catch (YamlEngineException e) {
      throw new PackException(List.of(source + ": " + oneLine(e.getMessage())));
    }
    if (root.isEmpty() || !(root.get() instanceof MappingNode)) {
      throw new PackException(List.of(source + ": expected pack:, and its collectors:"));
    }
    Pack pack = reader.pack((MappingNode) root.get(), folder, pushed);
    if (!reader.problems.isEmpty()) {
      throw new PackException(reader.problems);
    }
    return pack;
  }

  private static LoadSettings settings(String source) {
    return LoadSettings.builder()
        .setLabel(source)
        .setSchema(new CoreSchema())
        .setAllowDuplicateKeys(false)
        .setMaxAliasesForCollections(16)
        .setCodePointLimit(Host.MAX_FILE)
        .build();
  }

  private Pack pack(MappingNode node, String folder, boolean pushed) {
    Map<String, NodeTuple> keys = mapping(node, PACK_KEYS, "a pack");
    String folderName = folder.substring(folder.lastIndexOf('/') + 1);
    String name = folderName;
    NodeTuple named = keys.get("pack");
    if (named == null) {
      problem(node, "pack: (its name) is missing");
    } else {
      String given = text(named.getValueNode(), "pack").orElse("");
      if (!NAME.matcher(given).matches()) {
        problem(named, "pack " + given + " isn't a pack's name: lowercase letters, digits, and -");
      } else if (given.equals(Pack.CORE)) {
        problem(named, "pack core is the agent's own: its built-in actions are core's");
      } else if (!given.equals(folderName)) {
        problem(
            named,
            "pack "
                + given
                + " is in a folder named "
                + folderName
                + ": a pack's folder is its name");
      }
      name = given;
    }
    String version = optional(keys, "version").flatMap(n -> text(n, "version")).orElse("");
    // Names are unique where they're declared: collectors', logs' and actions' in the pack, fields'
    // in their collector or response (a mapping's keys, which loading checks).
    List<Pack.Collector> collectors = new ArrayList<>();
    for (Node item : sequence(keys.get("collectors"), "collectors")) {
      Pack.Collector collector = collector(item);
      if (collector != null) {
        if (collectors.stream().anyMatch(c -> c.id().equals(collector.id()))) {
          problem(item, "collector " + collector.id() + " is declared twice");
        }
        collectors.add(collector);
      }
    }
    List<Pack.Log> logs = new ArrayList<>();
    for (Node item : sequence(keys.get("logs"), "logs")) {
      Pack.Log log = log(item);
      if (log != null) {
        if (logs.stream().anyMatch(l -> l.id().equals(log.id()))) {
          problem(item, "log " + log.id() + " is declared twice");
        }
        logs.add(log);
      }
    }
    List<Pack.Action> actions = new ArrayList<>();
    for (Node item : sequence(keys.get("actions"), "actions")) {
      Pack.Action action = action(item);
      if (action != null) {
        if (actions.stream().anyMatch(a -> a.id().equals(action.id()))) {
          problem(item, "action " + action.id() + " is declared twice");
        }
        actions.add(action);
      }
    }
    return new Pack(name, version, folder, pushed, collectors, logs, actions);
  }

  // ---- collectors ----

  private Pack.@Nullable Collector collector(Node node) {
    if (!(node instanceof MappingNode)) {
      problem(node, "each collector is a mapping: - id: ...");
      return null;
    }
    int before = problems.size();
    Map<String, NodeTuple> keys = mapping((MappingNode) node, COLLECTOR_KEYS, "a collector");
    String id = id(keys, node, "collector");
    Command command = command(keys, node, true);
    Duration every = Duration.ZERO;
    NodeTuple everyKey = keys.get("every");
    if (everyKey == null) {
      problem(node, "collector " + id + " needs every: how often it runs, such as 2s");
    } else {
      every = duration(everyKey, MIN_EVERY, MAX_EVERY);
    }
    Duration timeout =
        optional(keys, "timeout").isPresent()
            ? duration(keys.get("timeout"), Duration.ofMillis(1), MAX_COLLECTOR_TIMEOUT)
            : Pack.Collector.TIMEOUT;
    List<Field> fields = new ArrayList<>();
    NodeTuple declared = keys.get("fields");
    if (declared == null || !(declared.getValueNode() instanceof MappingNode)) {
      problem(
          declared != null ? declared.getValueNode() : node,
          "collector " + id + " needs fields:, the values it fills");
    } else {
      Map<String, Fill.Part> parts = command == null ? Map.of() : Fill.parts(Fill.kind(command));
      for (NodeTuple each : entries((MappingNode) declared.getValueNode())) {
        String name = keyText(each);
        Fill.Part part = parts.get(name);
        // A named part is the command's, as an action's: a value's types are text and number.
        Field field =
            part == null
                ? field(each, VALUE_TYPES, "a value's type")
                : field(each, Set.of(part == Fill.Part.CODE ? "number" : "text"), name + "'s type");
        if (field != null) {
          fields.add(field);
        }
      }
      if (fields.isEmpty() && problems.size() == before) {
        problem(declared, "collector " + id + " needs at least one field");
      }
    }
    if (problems.size() > before || command == null) {
      return null;
    }
    return new Pack.Collector(id, command, every, timeout, fields);
  }

  // ---- logs ----

  private Pack.@Nullable Log log(Node node) {
    if (!(node instanceof MappingNode)) {
      problem(node, "each log is a mapping: - id: ...");
      return null;
    }
    int before = problems.size();
    Map<String, NodeTuple> keys = mapping((MappingNode) node, LOG_KEYS, "a log");
    String id = id(keys, node, "log");
    String label = optional(keys, "label").flatMap(n -> text(n, "label")).orElse("");
    if (keys.containsKey("http")) {
      problem(
          keys.get("http"),
          "a log runs a command (run:), which pages by the SPOTTER_* environment variables");
    }
    Command.Run run = null;
    NodeTuple runKey = keys.get("run");
    if (runKey == null) {
      problem(node, "log " + id + " needs run: the command that prints its entries");
    } else {
      run = run(runKey);
    }
    Pack.LogMap map = Pack.LogMap.DEFAULT;
    NodeTuple mapKey = keys.get("map");
    if (mapKey != null) {
      map = logMap(mapKey);
    }
    if (problems.size() > before || run == null) {
      return null;
    }
    return new Pack.Log(id, label, run, map);
  }

  private Pack.LogMap logMap(NodeTuple key) {
    if (!(key.getValueNode() instanceof MappingNode)) {
      problem(key, "map is a mapping: {message: MESSAGE, ...}");
      return Pack.LogMap.DEFAULT;
    }
    Map<String, NodeTuple> keys =
        mapping((MappingNode) key.getValueNode(), MAP_KEYS, "a log's map");
    Pack.LogMap d = Pack.LogMap.DEFAULT;
    String time = d.time();
    String unit = "";
    NodeTuple timeKey = keys.get("time");
    if (timeKey != null && timeKey.getValueNode() instanceof MappingNode) {
      Map<String, NodeTuple> parts =
          mapping((MappingNode) timeKey.getValueNode(), Set.of("from", "unit"), "a log's time");
      time = optional(parts, "from").flatMap(n -> text(n, "from")).orElse(time);
      unit = optional(parts, "unit").flatMap(n -> text(n, "unit")).orElse("");
      if (!unit.isEmpty() && !Set.of("ns", "us", "ms", "s").contains(unit)) {
        problem(parts.get("unit"), "a time's unit is ns, us, ms or s, not " + unit);
      }
    } else if (timeKey != null) {
      time = text(timeKey.getValueNode(), "time").orElse(time);
    }
    return new Pack.LogMap(
        time,
        unit,
        optional(keys, "level").flatMap(n -> text(n, "level")).orElse(d.level()),
        optional(keys, "source").flatMap(n -> text(n, "source")).orElse(d.source()),
        optional(keys, "message").flatMap(n -> text(n, "message")).orElse(d.message()),
        optional(keys, "cursor").flatMap(n -> text(n, "cursor")).orElse(d.cursor()));
  }

  // ---- actions ----

  private Pack.@Nullable Action action(Node node) {
    if (!(node instanceof MappingNode)) {
      problem(node, "each action is a mapping: - id: ...");
      return null;
    }
    int before = problems.size();
    Map<String, NodeTuple> keys = mapping((MappingNode) node, ACTION_KEYS, "an action");
    String id = id(keys, node, "action");
    Command command = command(keys, node, false);
    Spotter.Input input = Spotter.Input.INPUT_NONE;
    NodeTuple inputKey = keys.get("input");
    if (inputKey != null) {
      String given = text(inputKey.getValueNode(), "input").orElse("");
      Spotter.Input named = INPUTS.get(given);
      if (named == null) {
        problem(inputKey, "input is none, text or file, not " + given);
      } else {
        input = named;
      }
    }
    if (command instanceof Command.Http) {
      Command.Http http = (Command.Http) command;
      if (http.method().equals("GET") && input != Spotter.Input.INPUT_NONE) {
        problem(node, "action " + id + " sends a GET, which takes no input");
      }
    }
    Duration timeout =
        keys.containsKey("timeout")
            ? duration(keys.get("timeout"), Duration.ofMillis(1), Pack.Action.MAX_TIMEOUT)
            : Pack.Action.TIMEOUT;
    boolean whileEnabled = false;
    NodeTuple enabled = keys.get("whileEnabled");
    if (enabled != null) {
      whileEnabled = bool(enabled.getValueNode(), "whileEnabled");
    }
    List<Field> response = command == null ? List.of() : response(keys.get("response"), command);
    if (problems.size() > before || command == null) {
      return null;
    }
    return new Pack.Action(
        id,
        optional(keys, "label").flatMap(n -> text(n, "label")).orElse(""),
        optional(keys, "description").flatMap(n -> text(n, "description")).orElse(""),
        command,
        input,
        optional(keys, "confirm").flatMap(n -> text(n, "confirm")).orElse(""),
        timeout,
        whileEnabled,
        response);
  }

  /**
   * An action's response: the fields every response has ({@code outcome}, {@code outcomeMessage},
   * and its kind's code and whole output), then the pack's, each a key of a JSON output. A pack may
   * declare its kind's own fields, to give their limits, or the whole output's type.
   */
  private List<Field> response(@Nullable NodeTuple declared, Command command) {
    boolean run = command instanceof Command.Run;
    String code = run ? Fill.EXIT : Fill.STATUS;
    String whole = run ? Fill.OUTPUT : Fill.BODY;
    Map<String, Field> builtIn = new LinkedHashMap<>();
    for (Field field : builtInResponse(run)) {
      builtIn.put(field.name(), field);
    }
    List<Field> own = new ArrayList<>();
    if (declared != null) {
      if (!(declared.getValueNode() instanceof MappingNode)) {
        problem(declared, "response is a mapping of fields: name: {type: ...}");
      } else {
        for (NodeTuple each : entries((MappingNode) declared.getValueNode())) {
          String name = keyText(each);
          Set<String> types;
          String what;
          if (name.equals(Fill.OUTCOME) || name.equals(Fill.OUTCOME_MESSAGE)) {
            problem(each, name + " is every response's own, and can't be declared");
            continue;
          } else if (name.equals(code)) {
            types = Set.of("number");
            what = name + "'s type";
          } else if (name.equals(whole)) {
            types = Set.of("text", "json", "file");
            what = name + "'s type, the whole output's";
          } else {
            types = Set.of("number", "text", "boolean", "status", "json");
            what = "a response field's type";
          }
          Field field = field(each, types, what);
          if (field == null) {
            continue;
          }
          if (builtIn.containsKey(name)) {
            builtIn.put(name, field);
          } else {
            own.add(field);
          }
        }
      }
    }
    List<Field> all = new ArrayList<>(builtIn.values());
    all.addAll(own);
    return all;
  }

  /**
   * The fields every response of a kind has, as declared unless its pack says more: {@code outcome}
   * and {@code outcomeMessage}, then a {@code run} action's {@code exit} and {@code output}, or an
   * {@code http} action's {@code status} and {@code body}, as text.
   */
  static List<Field> builtInResponse(boolean run) {
    return List.of(
        Field.of(Fill.OUTCOME, Spotter.FieldType.FIELD_TYPE_TEXT),
        Field.of(Fill.OUTCOME_MESSAGE, Spotter.FieldType.FIELD_TYPE_TEXT),
        Field.of(run ? Fill.EXIT : Fill.STATUS, Spotter.FieldType.FIELD_TYPE_NUMBER),
        Field.of(run ? Fill.OUTPUT : Fill.BODY, Spotter.FieldType.FIELD_TYPE_TEXT));
  }

  // ---- commands ----

  /**
   * The one command a collector, log or action names: {@code run}, {@code http} or {@code file}.
   */
  private @Nullable Command command(Map<String, NodeTuple> keys, Node node, boolean collector) {
    List<String> kinds = new ArrayList<>();
    for (String kind : List.of("run", "http", "file")) {
      if (keys.containsKey(kind)) {
        kinds.add(kind);
      }
    }
    if (kinds.size() != 1) {
      problem(
          node,
          (collector ? "a collector" : "an action")
              + " names one of "
              + (collector ? "run:, http: or file:" : "run: or http:")
              + (kinds.isEmpty() ? "" : ", not " + String.join(" and ", kinds)));
      return null;
    }
    NodeTuple key = keys.get(kinds.get(0));
    if (key == null) {
      return null;
    }
    switch (kinds.get(0)) {
      case "run":
        return run(key);
      case "http":
        return http(key, collector);
      default:
        if (!collector) {
          problem(key, "only a collector reads a file");
          return null;
        }
        String path = text(key.getValueNode(), "file").orElse("");
        if (!path.startsWith("/")) {
          problem(key, "file is an absolute path, not " + path);
          return null;
        }
        return new Command.Read(path);
    }
  }

  private Command.@Nullable Run run(NodeTuple key) {
    if (!(key.getValueNode() instanceof SequenceNode)) {
      problem(key, "run is a list: [program, its arguments...]");
      return null;
    }
    List<String> argv = new ArrayList<>();
    for (Node item : ((SequenceNode) key.getValueNode()).getValue()) {
      Optional<String> each = text(item, "run");
      if (each.isEmpty()) {
        return null;
      }
      argv.add(each.get());
    }
    if (argv.isEmpty() || argv.get(0).isEmpty()) {
      problem(key, "run names a program first");
      return null;
    }
    return new Command.Run(argv);
  }

  private Command.@Nullable Http http(NodeTuple key, boolean collector) {
    if (!(key.getValueNode() instanceof MappingNode)) {
      problem(key, "http is a mapping: {get: \"http://localhost:5800/...\"}");
      return null;
    }
    Set<String> known = collector ? Set.of("get", "post") : Set.of("get", "post", "form");
    Map<String, NodeTuple> keys = mapping((MappingNode) key.getValueNode(), known, "http");
    boolean get = keys.containsKey("get");
    if (get == keys.containsKey("post")) {
      problem(key, "http names one of get: or post:, with its URL");
      return null;
    }
    NodeTuple at = keys.get(get ? "get" : "post");
    String url = at == null ? "" : text(at.getValueNode(), "a URL").orElse("");
    if (!local(url)) {
      problem(
          at != null ? at : key,
          "http asks the board itself only: http://localhost..., not " + url);
      return null;
    }
    String form = optional(keys, "form").flatMap(n -> text(n, "form")).orElse("");
    if (get && !form.isEmpty()) {
      problem(keys.get("form"), "a GET sends no form");
    }
    return new Command.Http(get ? "GET" : "POST", url, form);
  }

  /** Whether a URL is the board's own: {@code http} to localhost. */
  static boolean local(String url) {
    try {
      URI uri = new URI(url);
      String host = uri.getHost();
      return "http".equals(uri.getScheme())
          && host != null
          && Set.of("localhost", "127.0.0.1", "[::1]").contains(host.toLowerCase(Locale.ROOT))
          && uri.getUserInfo() == null;
    } catch (URISyntaxException e) {
      return false;
    }
  }

  // ---- fields and limits ----

  private @Nullable Field field(NodeTuple entry, Set<String> types, String what) {
    String name = keyText(entry);
    if (!ID.matcher(name).matches()) {
      problem(entry, "field \"" + name + "\" isn't a name: letters, digits, and . _ -");
      return null;
    }
    if (!(entry.getValueNode() instanceof MappingNode)) {
      problem(entry, "field " + name + " is a mapping: {type: ..., label: ...}");
      return null;
    }
    Map<String, NodeTuple> keys =
        mapping((MappingNode) entry.getValueNode(), FIELD_KEYS, "a field");
    int before = problems.size();
    NodeTuple typeKey = keys.get("type");
    Spotter.FieldType type = Spotter.FieldType.FIELD_TYPE_TEXT;
    String typeName = "";
    if (typeKey == null) {
      problem(entry, "field " + name + " needs a type: " + String.join(", ", new TreeSet<>(types)));
    } else {
      typeName = text(typeKey.getValueNode(), "type").orElse("");
      Spotter.FieldType named = TYPES.get(typeName);
      if (named == null || !types.contains(typeName)) {
        problem(
            typeKey, what + " is " + String.join(", ", new TreeSet<>(types)) + ", not " + typeName);
      } else {
        type = named;
      }
    }
    String fileName = optional(keys, "name").flatMap(n -> text(n, "name")).orElse("");
    if (keys.containsKey("name") && !typeName.equals("file")) {
      problem(keys.get("name"), "name is the file a file field downloads as");
    }
    Spotter.Limit warn = limit(keys.get("warn"), type);
    Spotter.Limit fail = limit(keys.get("fail"), type);
    if (problems.size() > before) {
      return null;
    }
    return new Field(
        name,
        optional(keys, "label").flatMap(n -> text(n, "label")).orElse(""),
        type,
        optional(keys, "unit").flatMap(n -> text(n, "unit")).orElse(""),
        warn,
        fail,
        fileName);
  }

  private Spotter.Limit limit(@Nullable NodeTuple key, Spotter.FieldType type) {
    Spotter.Limit limit = Spotter.Limit.newInstance();
    if (key == null) {
      return limit;
    }
    if (!(key.getValueNode() instanceof MappingNode)) {
      problem(key, "a limit is a mapping: {below: 5, missing: true}");
      return limit;
    }
    Map<String, NodeTuple> keys = mapping((MappingNode) key.getValueNode(), LIMIT_KEYS, "a limit");
    boolean number = type == Spotter.FieldType.FIELD_TYPE_NUMBER;
    for (String bound : List.of("above", "below")) {
      NodeTuple at = keys.get(bound);
      if (at == null) {
        continue;
      }
      if (!number) {
        problem(at, bound + " is for numbers");
        continue;
      }
      Double value = number(at.getValueNode(), bound);
      if (value != null) {
        if (bound.equals("above")) {
          limit.setAbove(value);
        } else {
          limit.setBelow(value);
        }
      }
    }
    NodeTuple equals = keys.get("equals");
    if (equals != null) {
      Spotter.Scalar scalar = scalar(equals.getValueNode(), type, "equals");
      if (scalar != null) {
        limit.setEquals(scalar);
      }
    }
    NodeTuple notEquals = keys.get("notEquals");
    if (notEquals != null) {
      Spotter.Scalar scalar = scalar(notEquals.getValueNode(), type, "notEquals");
      if (scalar != null) {
        limit.setNotEquals(scalar);
      }
    }
    NodeTuple missing = keys.get("missing");
    if (missing != null) {
      limit.setMissing(bool(missing.getValueNode(), "missing"));
    }
    return limit;
  }

  /** A comparison's value, as its field's type has it: a number, a flag, or text. */
  private Spotter.@Nullable Scalar scalar(Node node, Spotter.FieldType type, String what) {
    if (type == Spotter.FieldType.FIELD_TYPE_NUMBER) {
      Double value = number(node, what);
      return value == null ? null : Spotter.Scalar.newInstance().setNumber(value);
    }
    if (type == Spotter.FieldType.FIELD_TYPE_BOOLEAN) {
      return Spotter.Scalar.newInstance().setFlag(bool(node, what));
    }
    return text(node, what).map(text -> Spotter.Scalar.newInstance().setText(text)).orElse(null);
  }

  // ---- scalars ----

  private String id(Map<String, NodeTuple> keys, Node node, String what) {
    NodeTuple key = keys.get("id");
    if (key == null) {
      problem(node, "a " + what + " needs an id");
      return "";
    }
    String id = text(key.getValueNode(), "id").orElse("");
    if (!ID.matcher(id).matches()) {
      problem(key, what + " id \"" + id + "\" isn't a name: letters, digits, and . _ -");
    }
    return id;
  }

  private Duration duration(@Nullable NodeTuple key, Duration min, Duration max) {
    if (key == null) {
      return min;
    }
    String text = text(key.getValueNode(), "a duration").orElse("");
    Optional<Duration> read = duration(text);
    if (read.isEmpty()) {
      problem(key, "a duration is a number and its unit (ms, s, m, h), such as 2s, not " + text);
      return min;
    }
    Duration duration = read.get();
    if (duration.compareTo(min) < 0 || duration.compareTo(max) > 0) {
      problem(key, text + " is out of range: " + show(min) + " to " + show(max));
      return min;
    }
    return duration;
  }

  /** A duration as a pack (or a request) writes one: {@code 250ms}, {@code 2s}, {@code 10m}. */
  static Optional<Duration> duration(String text) {
    Matcher matcher = DURATION.matcher(text);
    if (!matcher.matches()) {
      return Optional.empty();
    }
    double amount = Double.parseDouble(matcher.group(1));
    double millis;
    switch (matcher.group(2)) {
      case "ms":
        millis = amount;
        break;
      case "s":
        millis = amount * 1000;
        break;
      case "m":
        millis = amount * 60_000;
        break;
      default:
        millis = amount * 3_600_000;
        break;
    }
    return Optional.of(Duration.ofMillis(Math.round(millis)));
  }

  /** A duration as a pack writes one. */
  static String show(Duration duration) {
    long millis = duration.toMillis();
    if (millis % 3_600_000 == 0) {
      return millis / 3_600_000 + "h";
    }
    if (millis % 60_000 == 0) {
      return millis / 60_000 + "m";
    }
    if (millis % 1000 == 0) {
      return millis / 1000 + "s";
    }
    return millis + "ms";
  }

  private @Nullable Double number(Node node, String what) {
    Optional<String> text = text(node, what);
    if (text.isEmpty()) {
      return null;
    }
    if (!(node instanceof ScalarNode)
        || !((ScalarNode) node).isPlain()
        || !NUMBER.matcher(text.get()).matches()) {
      problem(node, what + " is a number, not " + text.get());
      return null;
    }
    return Double.parseDouble(text.get());
  }

  private boolean bool(Node node, String what) {
    if (node instanceof ScalarNode && node.getTag().equals(Tag.BOOL)) {
      return Boolean.parseBoolean(((ScalarNode) node).getValue().toLowerCase(Locale.ROOT));
    }
    problem(node, what + " is true or false");
    return false;
  }

  /** A scalar's text, as written (unquoted); a problem when the node isn't one, or is null. */
  private Optional<String> text(Node node, String what) {
    if (!(node instanceof ScalarNode) || node.getTag().equals(Tag.NULL)) {
      problem(node, what + " needs a single value");
      return Optional.empty();
    }
    return Optional.of(((ScalarNode) node).getValue());
  }

  // ---- mappings and sequences ----

  /** A mapping's entries by key, each key known, none twice. */
  private Map<String, NodeTuple> mapping(MappingNode node, Set<String> known, String what) {
    Map<String, NodeTuple> keys = new LinkedHashMap<>();
    for (NodeTuple entry : entries(node)) {
      String key = keyText(entry);
      if (!known.contains(key)) {
        problem(
            entry,
            "unknown key \""
                + key
                + "\" in "
                + what
                + "; known: "
                + String.join(", ", new TreeSet<>(known)));
      } else {
        keys.put(key, entry);
      }
    }
    return keys;
  }

  /**
   * A mapping's entries, in its order; a problem for a key that isn't text. A key twice is refused
   * as the pack is loaded.
   */
  private List<NodeTuple> entries(MappingNode node) {
    List<NodeTuple> entries = new ArrayList<>();
    for (NodeTuple entry : node.getValue()) {
      if (!(entry.getKeyNode() instanceof ScalarNode)) {
        problem(entry.getKeyNode(), "a key is a single value");
        continue;
      }
      entries.add(entry);
    }
    return entries;
  }

  private static String keyText(NodeTuple entry) {
    return entry.getKeyNode() instanceof ScalarNode
        ? ((ScalarNode) entry.getKeyNode()).getValue()
        : "";
  }

  private List<Node> sequence(@Nullable NodeTuple key, String what) {
    if (key == null) {
      return List.of();
    }
    Node value = key.getValueNode();
    if (value instanceof ScalarNode && value.getTag().equals(Tag.NULL)) {
      return List.of();
    }
    if (!(value instanceof SequenceNode)) {
      problem(key, what + " is a list: - id: ...");
      return List.of();
    }
    return ((SequenceNode) value).getValue();
  }

  private static Optional<Node> optional(Map<String, NodeTuple> keys, String key) {
    NodeTuple entry = keys.get(key);
    return entry == null ? Optional.empty() : Optional.of(entry.getValueNode());
  }

  // ---- problems ----

  private void problem(@Nullable NodeTuple entry, String message) {
    problem(entry == null ? null : entry.getKeyNode(), message);
  }

  private void problem(@Nullable Node node, String message) {
    int line = node == null ? 0 : node.getStartMark().map(m -> m.getLine() + 1).orElse(0);
    problems.add(source + (line > 0 ? ":" + line : "") + ": " + message);
  }

  private static String oneLine(@Nullable String message) {
    return String.valueOf(message).replaceAll("\\s+", " ").strip();
  }
}
