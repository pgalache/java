/*
Copyright 2020 The Kubernetes Authors.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at
http://www.apache.org/licenses/LICENSE-2.0
Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package io.kubernetes.client.util;

import com.google.common.base.Strings;
import com.google.common.reflect.ClassPath;
import io.kubernetes.client.Discovery;
import io.kubernetes.client.apimachinery.GroupVersionKind;
import io.kubernetes.client.apimachinery.GroupVersionResource;
import io.kubernetes.client.common.KubernetesListObject;
import io.kubernetes.client.common.KubernetesObject;
import io.kubernetes.client.openapi.ApiException;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Model mapper loads pre-built kubernetes models from classpath and manages their mapping between
 * their apiVersion and kind.
 */
public class ModelMapper {

  private static final Logger logger = LoggerFactory.getLogger(ModelMapper.class);

  public static final Duration DEFAULT_DISCOVERY_REFRESH_INTERVAL = Duration.ofMinutes(30);

  // initially loaded from classpath, can be incorrect
  private static Map<GroupVersionKind, Class<?>> preBuiltClassesByGVK = new HashMap<>();

  // Model's api-group prefix to kubernetes api-group
  private static Map<String, String> preBuiltApiGroups = new HashMap<>();

  // Model's api-version midfix to kubernetes api-version
  private static List<String> preBuiltApiVersions = new ArrayList<>();

  // TODO(yue9944882): make the map bi-directional
  private static Map<GroupVersionKind, Class<?>> classesByGVK = new ConcurrentHashMap<>();

  // TODO(yue9944882): make the map bi-directional
  private static Map<GroupVersionResource, Class<?>> classesByGVR = new ConcurrentHashMap<>();

  private static Set<Discovery.APIResource> lastAPIDiscovery = new HashSet<>();

  private static volatile long nextDiscoveryRefreshTimeMillis =
      0; // zero means api-discovery never done

  static {
    try {
      initModelMap();
    } catch (Exception ex) {
      logger.error("Unexpected exception while loading classes: " + ex);
    }
  }

  /**
   * Registering concrete model classes by its apiGroupVersion (e.g. "apps/v1") and kind (e.g.
   * "Deployment").
   *
   * <p>Note that the the so-called apiGroupVersion equals to the "apiVersion" in the kubenretes
   * resource yamls.
   */
  @Deprecated
  public static void addModelMap(String apiGroupVersion, String kind, Class<?> clazz) {
    String[] parts = apiGroupVersion.split("/");
    if (parts.length == 1) { // legacy api group
      addModelMap("", apiGroupVersion, kind, clazz);
    }
    addModelMap(parts[0], parts[1], kind, clazz);
  }

  /**
   * Registering concrete model classes by its group, version and kind (e.g. "apps", "v1",
   * "Deployment")
   *
   * @param group the group
   * @param version the version
   * @param kind the kind
   * @param clazz the clazz
   */
  @Deprecated
  public static void addModelMap(String group, String version, String kind, Class<?> clazz) {
    preBuiltAddModelMap(group, version, kind, clazz);
  }

  /**
   * Registering concrete model classes by its group, version and kind (e.g. "apps", "v1",
   * "Deployment")
   *
   * @param group the group
   * @param version the version
   * @param kind the kind
   * @param resourceNamePlural the resource name plural
   * @param clazz the clazz
   */
  public static void addModelMap(
      String group, String version, String kind, String resourceNamePlural, Class<?> clazz) {
    classesByGVK.put(new GroupVersionKind(group, version, kind), clazz);
    classesByGVR.put(new GroupVersionResource(group, version, resourceNamePlural), clazz);
  }

  /**
   * Gets the model classes by given apiGroupVersion (e.g. "apps/v1") and kind (e.g. "Deployment").
   *
   * @param apiGroupVersion the api version
   * @param kind the kind
   * @return the api type class
   */
  public static Class<?> getApiTypeClass(String apiGroupVersion, String kind) {
    String[] parts = apiGroupVersion.split("/");
    String apiGroup;
    String apiVersion;
    if (parts.length == 1) {
      // legacy group
      apiGroup = "";
      apiVersion = apiGroupVersion;
    } else {
      apiGroup = parts[0];
      apiVersion = parts[1];
    }
    return getApiTypeClass(apiGroup, apiVersion, kind);
  }

  /**
   * Gets the model classes by given group, version and kind (e.g. "apps", ""v1", "Deployment").
   *
   * @param group the group
   * @param version the version
   * @param kind the kind
   * @return the api type class
   */
  public static Class<?> getApiTypeClass(String group, String version, String kind) {
    Class<?> clazz = classesByGVK.get(new GroupVersionKind(group, version, kind));
    if (clazz != null) {
      return clazz;
    }
    return preBuiltGetApiTypeClass(group, version, kind);
  }

  /**
   * Gets the GVK by the given model class.
   *
   * @param clazz the clazz
   * @return the group version kind by class
   */
  public static GroupVersionKind getGroupVersionKindByClass(Class<?> clazz) {
    return classesByGVK.entrySet().stream()
        .filter(e -> clazz.equals(e.getValue()))
        .map(e -> e.getKey())
        .findFirst()
        .orElse(preBuiltGetGroupVersionKindByClass(clazz));
  }

  /**
   * Gets the GVK by the given model class.
   *
   * @param clazz the clazz
   * @return the group version kind by class
   */
  public static GroupVersionResource getGroupVersionResourceByClass(Class<?> clazz) {
    return classesByGVR.entrySet().stream()
        .filter(e -> clazz.equals(e.getValue()))
        .map(e -> e.getKey())
        .findFirst()
        .get();
  }

  public static Set<Discovery.APIResource> refresh(Discovery discovery) throws ApiException {
    return refresh(discovery, DEFAULT_DISCOVERY_REFRESH_INTERVAL);
  }

  /**
   * Refreshes the model mapping by syncing up w/the api discovery info from the kubernetes
   * apiserver.
   *
   * <p>Note: if model mappings can be incomplete if this method is never called.
   *
   * @param discovery the discovery
   * @return the api-discovery set
   * @throws ApiException the api exception
   */
  public static Set<Discovery.APIResource> refresh(Discovery discovery, Duration refreshInterval)
      throws ApiException {
    long nowMillis = System.currentTimeMillis();
    if (nowMillis < nextDiscoveryRefreshTimeMillis) {
      return lastAPIDiscovery;
    }

    Set<Discovery.APIResource> apiResources = discovery.findAll();

    for (Discovery.APIResource apiResource : apiResources) {
      for (String version : apiResource.getVersions()) {
        Class<?> clazz = getApiTypeClass(apiResource.getGroup(), version, apiResource.getKind());
        // no such classes registered in the ModelMapper, ignoring
        if (clazz == null) {
          continue;
        }
        // sync up w/ the latest api discovery
        addModelMap(
            apiResource.getGroup(),
            version,
            apiResource.getKind(),
            apiResource.getResourcePlural(),
            clazz);
      }
    }

    lastAPIDiscovery = apiResources;
    nextDiscoveryRefreshTimeMillis = refreshInterval.toMillis() + nowMillis;
    return lastAPIDiscovery;
  }

  public static boolean isApiDiscoveryRefreshed() {
    return nextDiscoveryRefreshTimeMillis != 0;
  }

  static void preBuiltAddModelMap(String group, String version, String kind, Class<?> clazz) {
    preBuiltClassesByGVK.put(new GroupVersionKind(group, version, kind), clazz);
  }

  public static Class<?> preBuiltGetApiTypeClass(String group, String version, String kind) {
    Class<?> clazz = preBuiltClassesByGVK.get(new GroupVersionKind(group, version, kind));
    if (clazz != null) {
      return clazz;
    }
    return preBuiltClassesByGVK.get(new GroupVersionKind("", version, kind));
  }

  public static GroupVersionKind preBuiltGetGroupVersionKindByClass(Class<?> clazz) {
    return preBuiltClassesByGVK.entrySet().stream()
        .filter(e -> clazz.equals(e.getValue()))
        .map(e -> e.getKey())
        .findFirst()
        .get();
  }

  private static void initApiGroupMap() {
    preBuiltApiGroups.put("Admissionregistration", "admissionregistration.k8s.io");
    preBuiltApiGroups.put("Apiextensions", "apiextensions.k8s.io");
    preBuiltApiGroups.put("Apiregistration", "apiregistration.k8s.io");
    preBuiltApiGroups.put("Apps", "apps");
    preBuiltApiGroups.put("Authentication", "authentication.k8s.io");
    preBuiltApiGroups.put("Authorization", "authorization.k8s.io");
    preBuiltApiGroups.put("Autoscaling", "autoscaling");
    preBuiltApiGroups.put("Extensions", "extensions");
    preBuiltApiGroups.put("Batch", "batch");
    preBuiltApiGroups.put("Certificates", "certificates.k8s.io");
    preBuiltApiGroups.put("Networking", "networking.k8s.io");
    preBuiltApiGroups.put("Policy", "policy");
    preBuiltApiGroups.put("RbacAuthorization", "rbac.authorization.k8s.io");
    preBuiltApiGroups.put("Scheduling", "scheduling.k8s.io");
    preBuiltApiGroups.put("Settings", "settings.k8s.io");
    preBuiltApiGroups.put("Storage", "storage.k8s.io");
    preBuiltApiGroups.put("FlowControl", "flowcontrol.apiserver.k8s.io");
  }

  private static void initApiVersionList() {
    // Order important
    preBuiltApiVersions.add("V2beta1");
    preBuiltApiVersions.add("V2beta2");
    preBuiltApiVersions.add("V2alpha1");
    preBuiltApiVersions.add("V1beta2");
    preBuiltApiVersions.add("V1beta1");
    preBuiltApiVersions.add("V1alpha1");
    preBuiltApiVersions.add("V1");
  }

  private static void initModelMap() throws IOException {
    initApiGroupMap();
    initApiVersionList();

    ClassPath cp = ClassPath.from(Yaml.class.getClassLoader());
    Set<ClassPath.ClassInfo> allClasses =
        cp.getTopLevelClasses("io.kubernetes.client.openapi.models");

    for (ClassPath.ClassInfo classInfo : allClasses) {
      Class clazz = classInfo.load();
      if (!KubernetesObject.class.isAssignableFrom(clazz)
          && !KubernetesListObject.class.isAssignableFrom(clazz)) {
        continue;
      }

      Pair<String, String> groupAndOther = getApiGroup(clazz.getSimpleName());
      Pair<String, String> versionAndOther = getApiVersion(groupAndOther.getRight());

      String group = Strings.nullToEmpty(groupAndOther.getLeft());
      String version = versionAndOther.getLeft();
      String kind = versionAndOther.getRight();

      preBuiltClassesByGVK.put(new GroupVersionKind(group, version, kind), clazz);
    }
  }

  private static Pair<String, String> getApiGroup(String name) {
    return preBuiltApiGroups.entrySet().stream()
        .filter(e -> name.startsWith(e.getKey()))
        .map(e -> new MutablePair(e.getValue(), name.substring(e.getKey().length())))
        .findFirst()
        .orElse(new MutablePair(null, name));
  }

  private static Pair<String, String> getApiVersion(String name) {
    return preBuiltApiVersions.stream()
        .filter(v -> name.startsWith(v))
        .map(v -> new MutablePair(v.toLowerCase(), name.substring(v.length())))
        .findFirst()
        .orElse(new MutablePair(null, name));
  }
}