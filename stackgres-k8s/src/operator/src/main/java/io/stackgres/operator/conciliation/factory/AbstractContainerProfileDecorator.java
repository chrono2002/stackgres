/*
 * Copyright (C) 2019 OnGres, Inc.
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package io.stackgres.operator.conciliation.factory;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.EmptyDirVolumeSourceBuilder;
import io.fabric8.kubernetes.api.model.PodSpec;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceRequirements;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.stackgres.common.ClusterPath;
import io.stackgres.common.StackGresGroupKind;
import io.stackgres.common.StackGresVolume;
import io.stackgres.common.crd.sgcluster.StackGresClusterResources;
import io.stackgres.common.crd.sgprofile.StackGresProfile;
import io.stackgres.common.crd.sgprofile.StackGresProfileContainer;
import io.stackgres.common.crd.sgprofile.StackGresProfileHugePages;
import io.stackgres.common.crd.sgprofile.StackGresProfileRequests;
import io.stackgres.common.crd.sgprofile.StackGresProfileSpec;
import org.jooq.lambda.Seq;

public abstract class AbstractContainerProfileDecorator {

  protected abstract StackGresGroupKind getKind();

  protected void setProfileContainers(
      StackGresProfile profile,
      Optional<StackGresClusterResources> resources,
      Optional<PodSpec> podSpec) {
    podSpec
        .map(PodSpec::getContainers)
        .stream()
        .flatMap(List::stream)
        .forEach(container -> setProfileForContainer(
            profile, resources, podSpec, container));
    podSpec
        .map(PodSpec::getInitContainers)
        .stream()
        .flatMap(List::stream)
        .forEach(container -> setProfileForInitContainer(
            profile, resources, podSpec, container));
  }

  protected void setProfileForContainer(
      StackGresProfile profile,
      Optional<StackGresClusterResources> resources,
      Optional<PodSpec> podSpec,
      Container container) {
    boolean enableCpuAndMemoryLimits = resources
        .map(StackGresClusterResources::getEnableClusterLimitsRequirements)
        .orElse(false);
    var requestRequirements = resources
        .map(StackGresClusterResources::getContainers);
    var requests = Optional.of(profile.getSpec())
        .map(StackGresProfileSpec::getRequests)
        .map(StackGresProfileRequests::getContainers);
    Optional.of(profile.getSpec())
        .map(StackGresProfileSpec::getContainers)
        .map(Map::entrySet)
        .stream()
        .flatMap(Collection::stream)
        .filter(entry -> getKind().hasPrefix(entry.getKey()))
        .filter(entry -> Objects.equals(
            container.getName(),
            getKind().getName(entry.getKey())))
        .forEach(entry -> setContainerResources(
            podSpec,
            container,
            requestRequirements,
            requests,
            entry,
            enableCpuAndMemoryLimits));
  }

  protected void setProfileForInitContainer(
      StackGresProfile profile,
      Optional<StackGresClusterResources> resources,
      Optional<PodSpec> podSpec,
      Container container) {
    boolean enableCpuAndMemoryLimits = resources
        .map(StackGresClusterResources::getEnableClusterLimitsRequirements)
        .orElse(false);
    var containerRequestRequirements = resources
        .map(StackGresClusterResources::getInitContainers);
    var containerRequests = Optional.of(profile.getSpec())
        .map(StackGresProfileSpec::getRequests)
        .map(StackGresProfileRequests::getInitContainers);
    Optional.of(profile.getSpec())
        .map(StackGresProfileSpec::getInitContainers)
        .map(Map::entrySet)
        .stream()
        .flatMap(Collection::stream)
        .filter(entry -> getKind().hasPrefix(entry.getKey()))
        .filter(entry -> Objects.equals(
            container.getName(),
            getKind().getName(entry.getKey())))
        .forEach(entry -> setContainerResources(
            podSpec,
            container,
            containerRequestRequirements,
            containerRequests,
            entry,
            enableCpuAndMemoryLimits));
  }

  private void setContainerResources(
      Optional<PodSpec> podSpec,
      Container container,
      Optional<Map<String, ResourceRequirements>> requestRequirements,
      Optional<Map<String, StackGresProfileContainer>> profileRequests,
      Entry<String, StackGresProfileContainer> entry,
      boolean enableCpuAndMemoryLimits) {
    final Optional<ResourceRequirements> containerRequestRequirements =
        requestRequirements
        .stream()
        .map(Map::entrySet)
        .flatMap(Set::stream)
        .filter(containerRequest -> getKind().hasPrefix(containerRequest.getKey()))
        .filter(containerRequest -> Objects.equals(
            getKind().getName(containerRequest.getKey()), container.getName()))
        .findFirst()
        .map(Map.Entry::getValue);
    final ResourceRequirements containerResources =
        containerRequestRequirements
        .orElseGet(ResourceRequirements::new);
    Optional<StackGresProfileContainer> containerRequests = profileRequests
        .stream()
        .map(Map::entrySet)
        .flatMap(Set::stream)
        .filter(containerRequest -> getKind().hasPrefix(containerRequest.getKey()))
        .filter(containerRequest -> Objects.equals(
            getKind().getName(containerRequest.getKey()), container.getName()))
        .findFirst()
        .map(Map.Entry::getValue);
    final Quantity cpuLimit =
        Optional.of(entry)
        .map(Map.Entry::getValue)
        .map(StackGresProfileContainer::getCpu)
        .map(Quantity::new)
        .orElse(null);
    final Quantity memoryLimit =
        Optional.of(entry)
        .map(Map.Entry::getValue)
        .map(StackGresProfileContainer::getMemory)
        .map(Quantity::new)
        .orElse(null);
    final Quantity cpuRequest =
        containerRequests
        .map(StackGresProfileContainer::getCpu)
        .map(Quantity::new)
        .orElse(null);
    final Quantity memoryRequest =
        containerRequests
        .map(StackGresProfileContainer::getMemory)
        .map(Quantity::new)
        .orElse(null);
    final HashMap<String, Quantity> requests =
        Optional.of(containerResources)
        .map(ResourceRequirements::getRequests)
        .map(HashMap::new)
        .orElseGet(HashMap::new);
    if (cpuRequest != null
        && !requests.containsKey("cpu")) {
      requests.put("cpu", cpuRequest);
    }
    if (memoryRequest != null
        && !requests.containsKey("memory")) {
      requests.put("memory", memoryRequest);
    }

    if (enableCpuAndMemoryLimits) {
      final HashMap<String, Quantity> limits =
          Optional.of(containerResources)
          .map(ResourceRequirements::getLimits)
          .map(HashMap::new)
          .orElseGet(HashMap::new);
      if (cpuLimit != null
          && !limits.containsKey("cpu")) {
        limits.put("cpu", cpuLimit);
      }
      if (memoryLimit != null
          && !limits.containsKey("memory")) {
        limits.put("memory", memoryLimit);
      }
      Optional.of(entry.getValue())
          .map(StackGresProfileContainer::getHugePages)
          .map(StackGresProfileHugePages::getHugepages2Mi)
          .map(Quantity::new)
          .ifPresent(quantity -> setHugePages2Mi(
              podSpec, entry, container, requests, limits, quantity));
      Optional.of(entry.getValue())
          .map(StackGresProfileContainer::getHugePages)
          .map(StackGresProfileHugePages::getHugepages1Gi)
          .map(Quantity::new)
          .ifPresent(quantity -> setHugePages1Gi(
              podSpec, entry, container, requests, limits, quantity));
      containerResources.setLimits(Map.copyOf(limits));
    }

    containerResources.setRequests(Map.copyOf(requests));

    container.setResources(containerResources);
  }

  private void setHugePages2Mi(
      Optional<PodSpec> podSpec,
      Entry<String, StackGresProfileContainer> entry,
      Container container,
      final HashMap<String, Quantity> requests,
      final HashMap<String, Quantity> limits,
      Quantity quantity) {
    if (!requests.containsKey("hugepages-2Mi")) {
      requests.put("hugepages-2Mi", quantity);
    }
    if (!limits.containsKey("hugepages-2Mi")) {
      limits.put("hugepages-2Mi", quantity);
    }
    podSpec
        .ifPresent(spec -> spec.setVolumes(
            getVolumesWithHugePages2Mi(entry, spec)));
    container.setVolumeMounts(
        Seq.seq(Optional.ofNullable(container.getVolumeMounts())
            .stream())
        .flatMap(List::stream)
        .append(new VolumeMountBuilder()
            .withName(StackGresVolume.HUGEPAGES_2M.getName()
                + "-" + entry.getKey())
            .withMountPath(ClusterPath.HUGEPAGES_2M_PATH.path())
            .build())
        .toList());
  }

  private List<Volume> getVolumesWithHugePages2Mi(
      Entry<String, StackGresProfileContainer> entry,
      PodSpec spec) {
    return Seq.seq(Optional.ofNullable(spec.getVolumes())
        .stream()
        .flatMap(List::stream))
        .append(new VolumeBuilder()
            .withName(StackGresVolume.HUGEPAGES_2M.getName()
                + "-" + entry.getKey())
            .withEmptyDir(new EmptyDirVolumeSourceBuilder()
                .withMedium("HugePages-2Mi")
                .build())
            .build())
        .toList();
  }

  private void setHugePages1Gi(
      Optional<PodSpec> podSpec,
      Entry<String, StackGresProfileContainer> entry,
      Container container,
      final HashMap<String, Quantity> requests,
      final HashMap<String, Quantity> limits,
      Quantity quantity) {
    if (!requests.containsKey("hugepages-1Gi")) {
      requests.put("hugepages-1Gi", quantity);
    }
    if (!limits.containsKey("hugepages-1Gi")) {
      limits.put("hugepages-1Gi", quantity);
    }
    podSpec
        .ifPresent(spec -> spec.setVolumes(getVolumesWithHugePages1Gi(entry, spec)));
    container.setVolumeMounts(
        Seq.seq(Optional.ofNullable(container.getVolumeMounts())
            .stream())
        .flatMap(List::stream)
        .append(new VolumeMountBuilder()
            .withName(StackGresVolume.HUGEPAGES_1G.getName()
                + "-" + entry.getKey())
            .withMountPath(ClusterPath.HUGEPAGES_1G_PATH.path())
            .build())
        .toList());
  }

  private List<Volume> getVolumesWithHugePages1Gi(
      Entry<String, StackGresProfileContainer> entry,
      PodSpec spec) {
    return Seq.seq(Optional.ofNullable(spec.getVolumes())
        .stream()
        .flatMap(List::stream))
        .append(new VolumeBuilder()
            .withName(StackGresVolume.HUGEPAGES_1G.getName()
                + "-" + entry.getKey())
            .withEmptyDir(new EmptyDirVolumeSourceBuilder()
                .withMedium("HugePages-1Gi")
                .build())
            .build())
        .toList();
  }

}
