/*
 * Copyright 2017 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.netflix.spinnaker.clouddriver.kubernetes.op.handler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.netflix.spectator.api.Registry;
import com.netflix.spinnaker.clouddriver.kubernetes.artifact.ArtifactReplacer;
import com.netflix.spinnaker.clouddriver.kubernetes.artifact.ArtifactReplacer.ReplaceResult;
import com.netflix.spinnaker.clouddriver.kubernetes.artifact.Replacer;
import com.netflix.spinnaker.clouddriver.kubernetes.caching.Keys.InfrastructureCacheKey;
import com.netflix.spinnaker.clouddriver.kubernetes.caching.agent.KubernetesCachingAgent;
import com.netflix.spinnaker.clouddriver.kubernetes.caching.agent.KubernetesCachingAgentFactory;
import com.netflix.spinnaker.clouddriver.kubernetes.caching.view.provider.KubernetesManifestProvider;
import com.netflix.spinnaker.clouddriver.kubernetes.description.SpinnakerKind;
import com.netflix.spinnaker.clouddriver.kubernetes.description.manifest.KubernetesKind;
import com.netflix.spinnaker.clouddriver.kubernetes.description.manifest.KubernetesManifest;
import com.netflix.spinnaker.clouddriver.kubernetes.model.Manifest.Status;
import com.netflix.spinnaker.clouddriver.kubernetes.model.Manifest.Warning;
import com.netflix.spinnaker.clouddriver.kubernetes.security.KubernetesNamedAccountCredentials;
import com.netflix.spinnaker.kork.artifacts.model.Artifact;
import java.util.*;
import javax.annotation.Nonnull;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;

public abstract class KubernetesHandler implements CanDeploy, CanDelete, CanPatch {
  protected static final ObjectMapper objectMapper = new ObjectMapper();

  private final ArtifactReplacer artifactReplacer;

  @Value("${kubernetes.artifact-binding.docker-image:match-name-and-tag}")
  protected String dockerImageBinding;

  protected KubernetesHandler() {
    this.artifactReplacer = new ArtifactReplacer(artifactReplacers());
  }

  public abstract int deployPriority();

  @Override
  @Nonnull
  public abstract KubernetesKind kind();

  public abstract boolean versioned();

  @Nonnull
  public abstract SpinnakerKind spinnakerKind();

  public abstract Status status(KubernetesManifest manifest);

  public List<Warning> listWarnings(KubernetesManifest manifest) {
    return new ArrayList<>();
  }

  protected List<String> sensitiveKeys() {
    return new ArrayList<>();
  }

  @Nonnull
  protected ImmutableList<Replacer> artifactReplacers() {
    return ImmutableList.of();
  }

  public ReplaceResult replaceArtifacts(
      KubernetesManifest manifest, List<Artifact> artifacts, @Nonnull String account) {
    return artifactReplacer.replaceAll(
        this.dockerImageBinding, manifest, artifacts, manifest.getNamespace(), account);
  }

  public ReplaceResult replaceArtifacts(
      KubernetesManifest manifest,
      List<Artifact> artifacts,
      @Nonnull String namespace,
      @Nonnull String account) {
    return artifactReplacer.replaceAll(
        this.dockerImageBinding, manifest, artifacts, namespace, account);
  }

  protected abstract KubernetesCachingAgentFactory cachingAgentFactory();

  public ImmutableSet<Artifact> listArtifacts(KubernetesManifest manifest) {
    return artifactReplacer.findAll(manifest);
  }

  public KubernetesCachingAgent buildCachingAgent(
      KubernetesNamedAccountCredentials namedAccountCredentials,
      ObjectMapper objectMapper,
      Registry registry,
      int agentIndex,
      int agentCount,
      Long agentInterval) {
    return cachingAgentFactory()
        .buildCachingAgent(
            namedAccountCredentials, objectMapper, registry, agentIndex, agentCount, agentInterval);
  }

  // used for stripping sensitive values
  public void removeSensitiveKeys(KubernetesManifest manifest) {
    List<String> sensitiveKeys = sensitiveKeys();
    sensitiveKeys.forEach(manifest::remove);
  }

  public Map<String, Object> hydrateSearchResult(InfrastructureCacheKey key) {
    Map<String, Object> result =
        objectMapper.convertValue(key, new TypeReference<Map<String, Object>>() {});
    result.put("region", key.getNamespace());
    result.put(
        "name", KubernetesManifest.getFullResourceName(key.getKubernetesKind(), key.getName()));
    return result;
  }

  public void addRelationships(
      Map<KubernetesKind, List<KubernetesManifest>> allResources,
      Map<KubernetesManifest, List<KubernetesManifest>> relationshipMap) {}

  // lower "value" is deployed before higher "value"
  public enum DeployPriority {
    LOWEST_PRIORITY(1000),
    WORKLOAD_ATTACHMENT_PRIORITY(110),
    WORKLOAD_CONTROLLER_PRIORITY(100),
    WORKLOAD_PRIORITY(100),
    WORKLOAD_MODIFIER_PRIORITY(90),
    PDB_PRIORITY(90),
    API_SERVICE_PRIORITY(80),
    NETWORK_RESOURCE_PRIORITY(70),
    MOUNTABLE_DATA_PRIORITY(50),
    MOUNTABLE_DATA_BACKING_RESOURCE_PRIORITY(40),
    SERVICE_ACCOUNT_PRIORITY(40),
    STORAGE_CLASS_PRIORITY(40),
    ADMISSION_PRIORITY(40),
    RESOURCE_DEFINITION_PRIORITY(30),
    ROLE_BINDING_PRIORITY(30),
    ROLE_PRIORITY(20),
    NAMESPACE_PRIORITY(0);

    @Getter private final int value;

    DeployPriority(int value) {
      this.value = value;
    }

    public static DeployPriority fromString(String val) {
      if (val == null) {
        return null;
      }

      return Arrays.stream(values())
          .filter(v -> v.toString().equalsIgnoreCase(val))
          .findFirst()
          .orElseThrow(() -> new IllegalArgumentException("No such priority '" + val + "'"));
    }
  }

  public Comparator<KubernetesManifest> comparatorFor(KubernetesManifestProvider.Sort sort) {
    switch (sort) {
      case AGE:
        return ageComparator();
      case SIZE:
        return sizeComparator();
      default:
        throw new IllegalArgumentException("No comparator for " + sort + " found");
    }
  }

  // can be overridden by each handler
  protected Comparator<KubernetesManifest> ageComparator() {
    return Comparator.comparing(KubernetesManifest::getCreationTimestamp);
  }

  // can be overridden by each handler
  protected Comparator<KubernetesManifest> sizeComparator() {
    return Comparator.comparing(m -> m.getReplicas() == null ? -1 : m.getReplicas());
  }
}
