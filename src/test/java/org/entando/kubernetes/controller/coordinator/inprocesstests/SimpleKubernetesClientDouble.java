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
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.CustomResourceDefinition;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.entando.kubernetes.controller.coordinator.SimpleEntandoOperations;
import org.entando.kubernetes.controller.coordinator.SimpleKubernetesClient;
import org.entando.kubernetes.controller.support.client.doubles.AbstractK8SClientDouble;
import org.entando.kubernetes.model.common.EntandoCustomResource;
import org.entando.kubernetes.model.common.EntandoDeploymentPhase;

public class SimpleKubernetesClientDouble extends AbstractK8SClientDouble implements SimpleKubernetesClient {

    public SimpleKubernetesClientDouble() {
        super(new ConcurrentHashMap<>());
    }

    @Override
    public void updatePhase(EntandoCustomResource resource, EntandoDeploymentPhase phase) {
        getNamespace(resource).getCustomResources(resource.getKind()).get(resource.getMetadata().getName()).getStatus()
                .updateDeploymentPhase(phase, resource.getMetadata().getGeneration());
    }

    @Override
    public String getControllerNamespace() {
        return null;
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
        getNamespace(pod).putPod(pod);
        return pod;
    }

    @Override
    public void removePodsAndWait(String namespace, Map<String, String> labels) {

    }

    @Override
    public ConfigMap findOrCreateControllerConfigMap(String name) {
        return null;
    }

    @Override
    public ConfigMap patchControllerConfigMap(ConfigMap configMap) {
        return null;
    }

    @Override
    public void watchControllerConfigMap(String s, Watcher<ConfigMap> configMapWatcher) {

    }

    @Override
    public void watchCustomResourceDefinitions(Watcher<CustomResourceDefinition> customResourceDefinitionWatcher) {

    }

    @Override
    public Collection<CustomResourceDefinition> loadCustomResourceDefinitionsOfInterest() {
        return null;
    }

    @Override
    public <T extends EntandoCustomResource> SimpleEntandoOperations getOperations(CustomResourceDefinitionContext context) {
        return new SimpleEntandoOperationsDouble(getNamespaces(), context);
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
