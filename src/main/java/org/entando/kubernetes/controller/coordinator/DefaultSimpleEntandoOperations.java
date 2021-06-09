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

import static java.lang.String.format;
import static org.entando.kubernetes.controller.spi.common.ExceptionUtils.interruptionSafe;
import static org.entando.kubernetes.controller.spi.common.ExceptionUtils.ioSafe;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.ListOptions;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.fabric8.kubernetes.client.dsl.internal.RawCustomResourceOperationsImpl;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.entando.kubernetes.controller.spi.client.SerializedEntandoResource;
import org.entando.kubernetes.controller.spi.common.PodResult;
import org.entando.kubernetes.controller.spi.common.PodResult.State;

public class DefaultSimpleEntandoOperations implements SimpleEntandoOperations {

    private static final Logger LOGGER = Logger.getLogger(DefaultSimpleEntandoOperations.class.getName());

    private final KubernetesClient client;
    private final RawCustomResourceOperationsImpl operations;
    private final boolean anyNamespace;
    private final CustomResourceDefinitionContext definitionContext;

    public DefaultSimpleEntandoOperations(KubernetesClient client, CustomResourceDefinitionContext definitionContext,
            RawCustomResourceOperationsImpl operations, boolean anyNamespace) {
        this.client = client;
        this.definitionContext = definitionContext;
        this.operations = operations;
        this.anyNamespace = anyNamespace;
    }

    @Override
    public SimpleEntandoOperations inNamespace(String namespace) {
        return new DefaultSimpleEntandoOperations(client, definitionContext, operations.inNamespace(namespace), false);
    }

    @Override
    public SimpleEntandoOperations inAnyNamespace() {
        return new DefaultSimpleEntandoOperations(client, getDefinitionContext(), operations.inAnyNamespace(), true);
    }

    @Override
    public Watch watch(SerializedResourceWatcher observer) {
        Function<CustomResourceStringWatcher, Watch> restartingAction = customResourceWatcher -> {
            try {
                if (anyNamespace) {
                    return operations.watch((Map<String, String>) null, null, customResourceWatcher);
                } else {
                    return operations
                            .watch(operations.getNamespace(), null, null, (ListOptions) null, customResourceWatcher);
                }
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, e,
                        () -> "EntandoResourceObserver registration failed. Can't recover. The container should restart now.");
                Liveness.dead();
                throw new IllegalStateException();
            }
        };
        return new CustomResourceStringWatcher(observer, definitionContext, restartingAction);
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<SerializedEntandoResource> list() {
        final List<Map<String, Object>> items = (List<Map<String, Object>>) operations.list().get("items");
        return items.stream().map(this::toResource).collect(Collectors.toList());
    }

    @Override
    public SerializedEntandoResource removeAnnotation(SerializedEntandoResource r, String name) {
        return editAnnotations(r, a -> a.remove(name));
    }

    @SuppressWarnings("unchecked")
    private SerializedEntandoResource editAnnotations(SerializedEntandoResource r, Consumer<Map<String, Object>> editAction) {
        return ioSafe(() -> {
            final Map<String, Object> map = operations.get(r.getMetadata().getNamespace(), r.getMetadata().getName());
            final Map<String, Object> metadata = (Map<String, Object>) map.get("metadata");
            final Map<String, Object> annotations = (Map<String, Object>) metadata
                    .computeIfAbsent("annotations", key -> new HashMap<String, String>());
            editAction.accept(annotations);
            operations.inNamespace(r.getMetadata().getNamespace()).withName(r.getMetadata().getName()).edit(map);
            return this.toResource(map);
        });
    }

    private SerializedEntandoResource toResource(Map<String, Object> map) {
        return ioSafe(() -> {
            ObjectMapper objectMapper = new ObjectMapper();
            return objectMapper.readValue(objectMapper.writeValueAsString(map), SerializedEntandoResource.class);
        });
    }

    @Override
    public SerializedEntandoResource putAnnotation(SerializedEntandoResource r, String name, String value) {
        return editAnnotations(r, a -> a.put(name, value));
    }

    @Override
    public String getControllerNamespace() {
        return client.getNamespace();
    }

    @Override
    public CustomResourceDefinitionContext getDefinitionContext() {
        return this.definitionContext;
    }

    @Override
    public void removeSuccessfullyCompletedPods(SerializedEntandoResource resource) {
        String namespace = client.getNamespace();
        FilterWatchListDeletable<Pod, PodList> podResource = client.pods().inNamespace(namespace).withLabels(
                CoordinatorUtils.podLabelsFor(resource));
        interruptionSafe(() -> {
            waitForCompletionOfPods(podResource);
            removePodsAndWait(podResource);
            return null;
        });
    }

    private void removePodsAndWait(FilterWatchListDeletable<Pod, PodList> podResource) throws InterruptedException {
        podResource.delete();
        podResource.waitUntilCondition(ignore -> podResource.list().getItems().isEmpty(),
                ControllerCoordinatorConfig.getPodShutdownTimeoutSeconds(), TimeUnit.SECONDS);
    }

    private void waitForCompletionOfPods(FilterWatchListDeletable<Pod, PodList> podResource) throws InterruptedException {
        try {
            podResource.waitUntilCondition(
                    ignored -> podResource.list().getItems().stream().allMatch(pod -> PodResult.of(pod).getState() == State.COMPLETED),
                    ControllerCoordinatorConfig.getRemovalDelay(), TimeUnit.SECONDS);
        } catch (KubernetesClientException e) {
            LOGGER.log(Level.WARNING, e, () -> format(
                    "Some pods remained active after the removal delay period. You can consider increasing the setting %s ",
                    ControllerCoordinatorProperty.ENTANDO_K8S_CONTROLLER_REMOVAL_DELAY.getJvmSystemProperty()));
        }
    }

}
