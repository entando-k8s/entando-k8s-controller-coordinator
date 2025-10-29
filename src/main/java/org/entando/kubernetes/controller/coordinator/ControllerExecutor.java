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
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarSourceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.client.Watcher.Action;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.entando.kubernetes.controller.spi.client.SerializedEntandoResource;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorSpiConfigProperty;
import org.entando.kubernetes.controller.spi.common.NameUtils;
import org.entando.kubernetes.controller.support.common.EntandoImageResolver;

public class ControllerExecutor {

    private static final Logger LOGGER = Logger.getLogger(ControllerExecutor.class.getName());
    private final SimpleKubernetesClient client;
    private EntandoImageResolver imageResolver;
    private final String controllerNamespace;
    private final String imageName;

    public ControllerExecutor(String controllerNamespace, SimpleKubernetesClient client, String imageName) {
        this.controllerNamespace = controllerNamespace;
        this.client = client;
        this.imageName = imageName;
    }

    public Pod startControllerFor(Action action, SerializedEntandoResource resource) throws TimeoutException {
        LOGGER.log(Level.INFO, () -> String.format("startControllerFor called for %s/%s",
                resource.getMetadata().getNamespace(), resource.getMetadata().getName()));
        ConfigMap dockerImageInfoConfigMap = client.loadDockerImageInfoConfigMap();
        LOGGER.log(Level.INFO, () -> String.format("Loaded ConfigMap: %s with %d entries",
                dockerImageInfoConfigMap.getMetadata().getName(),
                dockerImageInfoConfigMap.getData() != null ? dockerImageInfoConfigMap.getData().size() : 0));
        this.imageResolver = new EntandoImageResolver(dockerImageInfoConfigMap, resource);
        LOGGER.log(Level.INFO, () -> String.format("Removing obsolete controller pods for %s/%s",
                resource.getMetadata().getNamespace(), resource.getMetadata().getName()));
        removeObsoleteControllerPods(resource);
        LOGGER.log(Level.INFO, () -> String.format("Building controller pod for %s/%s with input image name: %s",
                resource.getMetadata().getNamespace(), resource.getMetadata().getName(), imageName));
        Pod pod = buildControllerPod(action, resource);
        LOGGER.log(Level.INFO, () -> String.format("Starting pod: %s in namespace: %s with image: %s",
                pod.getMetadata().getName(), pod.getMetadata().getNamespace(),
                pod.getSpec().getContainers().get(0).getImage()));
        Pod result = client.startPod(pod);
        LOGGER.log(Level.INFO, () -> String.format("Pod started successfully: %s", result.getMetadata().getName()));
        return result;
    }

    private void removeObsoleteControllerPods(SerializedEntandoResource resource) throws TimeoutException {
        //We need to make sure they all terminate so that we don't have racing conditions between 2 controllers
        // processing the same resource
        Map<String, String> labels = CoordinatorUtils.podLabelsFor(resource);
        LOGGER.log(Level.INFO, () -> String.format("Looking for obsolete pods with labels: %s in namespace: %s",
                labels, controllerNamespace));
        this.client.removePodsAndWait(controllerNamespace, labels);
        LOGGER.log(Level.INFO, () -> String.format("Finished removing obsolete controller pods for %s/%s",
                resource.getMetadata().getNamespace(), resource.getMetadata().getName()));
    }

    private Pod buildControllerPod(Action action, SerializedEntandoResource resource) {
        String resolvedImageUri = this.imageResolver.determineImageUri(imageName);
        LOGGER.log(Level.INFO, () -> String.format("Image resolved from '%s' to '%s'", imageName, resolvedImageUri));
        return new PodBuilder().withNewMetadata()
                .withName(resource.getMetadata().getName() + "-deployer-" + NameUtils.randomNumeric(4).toLowerCase())
                .withNamespace(this.controllerNamespace)
                //Uncomment this line to reactivate ENG-2274
                //                .addToOwnerReferences(ResourceUtils.buildOwnerReference(resource))
                .addToLabels(CoordinatorUtils.podLabelsFor(resource))
                .endMetadata()
                .withNewSpec()
                .withRestartPolicy("Never")
                .withServiceAccountName(determineServiceAccountName())
                .addNewContainer()
                .withName("deployer")
                .withImage(resolvedImageUri)
                .withImagePullPolicy("IfNotPresent")
                .withEnv(buildEnvVars(action, resource))
                .endContainer()
                .endSpec()
                .build();
    }

    private String determineServiceAccountName() {
        return ControllerCoordinatorConfig.getOperatorServiceAccount().orElse("default");
    }

    private void addTo(Map<String, EnvVar> result, EnvVar envVar) {
        result.put(envVar.getName(), envVar);
    }

    private List<EnvVar> buildEnvVars(Action action, SerializedEntandoResource resource) {
        Map<String, EnvVar> result = new HashMap<>();
        System.getProperties().entrySet().stream()
                .filter(this::matchesKnownSystemProperty).forEach(objectObjectEntry -> addTo(result,
                        new EnvVar(objectObjectEntry.getKey().toString().toUpperCase(Locale.ROOT).replace(".", "_").replace("-", "_"),
                                objectObjectEntry.getValue().toString(), null)));
        System.getenv().entrySet().stream()
                .filter(this::matchesKnownEnvironmentVariable)
                .forEach(objectObjectEntry -> addTo(result, new EnvVar(objectObjectEntry.getKey(),
                        objectObjectEntry.getValue(), null)));
        //Make sure we overwrite previously set resource info
        addTo(result, new EnvVar("ENTANDO_RESOURCE_ACTION", action.name(), null));
        addTo(result, new EnvVar(EntandoOperatorSpiConfigProperty.ENTANDO_RESOURCE_NAMESPACE.name(), resource.getMetadata().getNamespace(),
                null));
        addTo(result, new EnvVar(EntandoOperatorSpiConfigProperty.ENTANDO_RESOURCE_NAME.name(), resource.getMetadata().getName(), null));
        addTo(result, new EnvVar(EntandoOperatorSpiConfigProperty.ENTANDO_RESOURCE_KIND.name(), resource.getKind(), null));
        addTo(result, new EnvVar(EntandoOperatorSpiConfigProperty.ENTANDO_CONTROLLER_POD_NAME.name(), null, new EnvVarSourceBuilder()
                .withNewFieldRef()
                .withFieldPath("metadata.name")
                .endFieldRef()
                .build()));
        return new ArrayList<>(result.values());
    }

    private boolean matchesKnownEnvironmentVariable(Map.Entry<String, String> objectObjectEntry) {
        return objectObjectEntry.getKey().startsWith("RELATED_IMAGE") || objectObjectEntry.getKey().startsWith("ENTANDO_");
    }

    private boolean matchesKnownSystemProperty(Map.Entry<Object, Object> objectObjectEntry) {
        String propertyName = objectObjectEntry.getKey().toString().toLowerCase(Locale.ROOT).replace("_", ".");
        return propertyName.startsWith("related.image") || propertyName.startsWith("entando.");
    }

}
