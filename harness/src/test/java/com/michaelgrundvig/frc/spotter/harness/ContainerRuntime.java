package com.michaelgrundvig.frc.spotter.harness;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Info;
import com.github.dockerjava.api.model.VersionComponent;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.DockerClientFactory;

/**
 * Whether the container tests can run here, and what they need to know of the runtime: a container
 * runtime that runs Linux containers (Docker on Linux, or Podman), or the tests skip, unless the
 * build says they must run ({@code spotter.requireContainers}, in CI on Linux), when they fail
 * instead. Rootless Podman runs a container's systemd as is; Docker needs privileged mode for it.
 */
final class ContainerRuntime implements ExecutionCondition {
  /** The label every container a test starts carries, so a crashed run's are found and removed. */
  static final String LABEL = "spotter.test";

  @Override
  public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
    String why = unavailable();
    if (why.isEmpty()) {
      return ConditionEvaluationResult.enabled("a container runtime runs Linux containers here");
    }
    if (Boolean.getBoolean("spotter.requireContainers")) {
      throw new IllegalStateException("Container tests must run in CI on Linux, and can't: " + why);
    }
    return ConditionEvaluationResult.disabled("No container runtime for Linux containers: " + why);
  }

  /** Why containers can't run here; empty when they can. */
  static String unavailable() {
    try {
      if (!DockerClientFactory.instance().isDockerAvailable()) {
        return "neither Docker nor Podman answers";
      }
      Info info = DockerClientFactory.instance().getInfo();
      String os = String.valueOf(info.getOsType()).toLowerCase(Locale.ROOT);
      return os.equals("linux") ? "" : "it runs " + os + " containers";
    } catch (RuntimeException e) {
      return String.valueOf(e.getMessage());
    }
  }

  /** Whether the runtime is Podman: it runs systemd in a container without privileged mode. */
  static boolean podman() {
    List<VersionComponent> components =
        DockerClientFactory.instance().client().versionCmd().exec().getComponents();
    return components != null
        && components.stream().anyMatch(c -> String.valueOf(c.getName()).contains("Podman"));
  }

  /**
   * Removes the containers a crashed run left (Podman runs without Testcontainers' cleanup
   * container), by their label: never anything else.
   */
  static void removeLeftovers() {
    DockerClient client = DockerClientFactory.instance().client();
    for (Container container :
        client.listContainersCmd().withShowAll(true).withLabelFilter(List.of(LABEL)).exec()) {
      client.removeContainerCmd(container.getId()).withForce(true).withRemoveVolumes(true).exec();
    }
  }
}
