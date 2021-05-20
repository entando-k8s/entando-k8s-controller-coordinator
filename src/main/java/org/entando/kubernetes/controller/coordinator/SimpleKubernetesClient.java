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
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import java.util.Collection;
import java.util.Map;
import org.entando.kubernetes.model.common.EntandoCustomResource;
import org.entando.kubernetes.model.common.EntandoDeploymentPhase;

public interface SimpleKubernetesClient {

    void updatePhase(EntandoCustomResource resource, EntandoDeploymentPhase phase);

    String getControllerNamespace();

    Secret loadControllerSecret(String s);

    void overwriteControllerSecret(Secret secret);

    ConfigMap loadDockerImageInfoConfigMap();

    Pod startPod(Pod pod);

    void removePodsAndWait(String namespace, Map<String, String> labels);

    ConfigMap findOrCreateControllerConfigMap(String name);

    ConfigMap patchControllerConfigMap(ConfigMap configMap);

    void watchControllerConfigMap(String s, Watcher<ConfigMap> configMapWatcher);

    void watchCustomResourceDefinitions(Watcher<CustomResourceDefinition> customResourceDefinitionWatcher);

    Collection<CustomResourceDefinition> loadCustomResourceDefinitionsOfInterest();

    <T extends EntandoCustomResource> SimpleEntandoOperations getOperations(CustomResourceDefinitionContext context);
}
