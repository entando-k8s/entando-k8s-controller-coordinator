/*
 *
 * Copyright 2015-Present Entando Inc. (http://www.entando.com) All rights reserved.
 *
 * This library is free software; you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 2.1 of the License, or (at your option)
 * any later version.
 *
 *  This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 *
 */

package org.entando.kubernetes.controller.coordinator.inprocesstests;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.CustomResourceDefinition;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.entando.kubernetes.controller.coordinator.CoordinatorUtils;
import org.entando.kubernetes.controller.coordinator.SimpleEntandoOperations;
import org.entando.kubernetes.controller.coordinator.SimpleKubernetesClient;
import org.entando.kubernetes.controller.spi.client.SerializedEntandoResource;
import org.entando.kubernetes.controller.support.client.doubles.AbstractK8SClientDouble;
import org.entando.kubernetes.controller.support.client.doubles.ClusterDouble;
import org.entando.kubernetes.controller.support.client.doubles.NamespaceDouble;
import org.entando.kubernetes.model.common.EntandoCustomResource;
import org.entando.kubernetes.model.common.EntandoDeploymentPhase;

public class SimpleKubernetesClientDouble extends AbstractK8SClientDouble implements SimpleKubernetesClient {

    public SimpleKubernetesClientDouble() {
        super(new ConcurrentHashMap<>(), new ClusterDouble());
    }

    @Override
    public SerializedEntandoResource updatePhase(SerializedEntandoResource resource, EntandoDeploymentPhase phase) {
        final NamespaceDouble namespace = getNamespace(resource);
        final Map<String, SerializedEntandoResource> customResources = namespace.getCustomResources(resource.getKind());
        final SerializedEntandoResource existingCustomResource = customResources.get(resource.getMetadata().getName());
        existingCustomResource.getStatus().updateDeploymentPhase(phase, resource.getMetadata().getGeneration());
        return getCluster().getResourceProcessor().processResource(customResources, existingCustomResource);
    }

    @Override
    public String getControllerNamespace() {
        return CONTROLLER_NAMESPACE;
    }

    @Override
    public Secret loadControllerSecret(String s) {
        return getNamespace(CONTROLLER_NAMESPACE).getSecret(s);
    }

    @Override
    public void overwriteControllerSecret(Secret secret) {
        getNamespace(CONTROLLER_NAMESPACE).putSecret(secret);
    }

    @Override
    public ConfigMap loadDockerImageInfoConfigMap() {
        return getNamespace(CONTROLLER_NAMESPACE).getConfigMap("entando-docker-image-info");
    }

    @Override
    public Pod startPod(Pod pod) {
        return getCluster().getResourceProcessor().processResource(getNamespace(pod).getPods(), pod);
    }

    @Override
    public void removePodsAndWait(String namespace, Map<String, String> labels) {

    }

    @Override
    public ConfigMap findOrCreateControllerConfigMap(String name) {
        final ConfigMap configMap = getNamespace(CONTROLLER_NAMESPACE).getConfigMap(name);
        if (configMap == null) {
            return getCluster().getResourceProcessor().processResource(
                    getNamespace(CONTROLLER_NAMESPACE).getConfigMaps(),
                    new ConfigMapBuilder()
                            .withNewMetadata()
                            .withNamespace(CONTROLLER_NAMESPACE)
                            .withName(name)
                            .endMetadata().build());
        }
        return configMap;
    }

    @Override
    public ConfigMap patchControllerConfigMap(ConfigMap configMap) {
        return getCluster().getResourceProcessor().processResource(getNamespace(CONTROLLER_NAMESPACE).getConfigMaps(), configMap);
    }

    @Override
    public void watchControllerConfigMap(String s, Watcher<ConfigMap> configMapWatcher) {

    }

    @Override
    public void watchCustomResourceDefinitions(Watcher<CustomResourceDefinition> customResourceDefinitionWatcher) {
        this.getCluster().getResourceProcessor().watch(customResourceDefinitionWatcher);
    }

    @Override
    public Collection<CustomResourceDefinition> loadCustomResourceDefinitionsOfInterest() {
        return getCluster().getCustomResourceDefinitions().values().stream().filter(CoordinatorUtils::isOfInterest)
                .collect(Collectors.toList());
    }

    @Override
    public SimpleEntandoOperations getOperations(CustomResourceDefinitionContext context) {
        return new SimpleEntandoOperationsDouble(getNamespaces(), context, getCluster());
    }

    public <T extends EntandoCustomResource> void createOrPatchEntandoResource(T resource) {
        getNamespace(resource).getCustomResources((Class<T>) resource.getClass()).put(resource.getMetadata().getName(), resource);
    }

    public Pod loadPod(String namespace, Map<String, String> labels) {
        return getNamespace(namespace).getPods().values().stream().filter(p -> labels.entrySet().stream()
                .allMatch(entry -> entry.getValue().equals(p.getMetadata().getLabels().get(entry.getKey())))).findFirst().orElse(null);
    }

    public <T extends EntandoCustomResource> T load(Class<T> resourceClass, String namespace, String name) {
        return getNamespace(namespace).getCustomResources(resourceClass).get(name);
    }

}
