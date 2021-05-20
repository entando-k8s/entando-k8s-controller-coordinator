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

package org.entando.kubernetes.controller.coordinator;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.CustomResourceDefinition;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import java.util.Collection;
import java.util.Map;
import org.entando.kubernetes.model.common.EntandoCustomResource;
import org.entando.kubernetes.model.common.EntandoDeploymentPhase;

public class DefaultSimpleKubernetesClient implements SimpleKubernetesClient {

    private final KubernetesClient client;

    public DefaultSimpleKubernetesClient(KubernetesClient client) {

        this.client = client;
    }

    @Override
    public void updatePhase(EntandoCustomResource resource, EntandoDeploymentPhase phase) {

    }

    @Override
    public String getControllerNamespace() {
        return client.getNamespace();
    }

    @Override
    public Secret loadControllerSecret(String s) {
        return client.secrets().inNamespace(getControllerNamespace()).withName(s).get();
    }

    @Override
    public void overwriteControllerSecret(Secret secret) {
        client.secrets().inNamespace(getControllerNamespace()).createOrReplace(secret);
    }

    @Override
    public ConfigMap loadDockerImageInfoConfigMap() {
        return null;
    }

    @Override
    public Pod startPod(Pod pod) {
        return null;
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
    public SimpleEntandoOperations getOperations(CustomResourceDefinitionContext context) {
        return new RawSimpleEntandoOperations(client, client.customResource(context));
    }
}
