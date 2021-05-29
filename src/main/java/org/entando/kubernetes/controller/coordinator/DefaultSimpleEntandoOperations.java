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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.internal.RawCustomResourceOperationsImpl;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
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

    public DefaultSimpleEntandoOperations(KubernetesClient client, RawCustomResourceOperationsImpl operations) {
        this.client = client;
        this.operations = operations;
    }

    @Override
    public SimpleEntandoOperations inNamespace(String namespace) {
        return new DefaultSimpleEntandoOperations(client, operations.inNamespace(namespace));
    }

    @Override
    public SimpleEntandoOperations inAnyNamespace() {
        return new DefaultSimpleEntandoOperations(client, operations.inAnyNamespace());
    }

    @Override
    public void watch(Watcher<SerializedEntandoResource> observer) {
        try {
            operations.watch(new Watcher<>() {
                @Override
                public void eventReceived(Action action, String s) {
                    try {
                        observer.eventReceived(action, new ObjectMapper().readValue(s, SerializedEntandoResource.class));
                    } catch (JsonProcessingException e) {
                        throw new IllegalStateException(e);
                    }
                }

                @Override
                public void onClose(WatcherException cause) {
                    if (cause.getMessage().contains("resourceVersion") && cause.getMessage().contains("too old")) {
                        LOGGER.log(Level.WARNING,
                                () -> "EntandoResourceObserver closed due to out of date resourceVersion. Reconnecting ... ");
                        DefaultSimpleEntandoOperations.this.watch(observer);
                    } else {
                        LOGGER.log(Level.SEVERE, cause,
                                () -> "EntandoResourceObserver closed. Can't reconnect. The container should restart now.");
                        Liveness.dead();
                    }

                }
            });
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, e,
                    () -> "EntandoResourceObserver registration failed. Can't recover. The container should restart now.");
            Liveness.dead();
        }
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
        try {
            final Map<String, Object> map = operations.get(r.getMetadata().getNamespace(), r.getMetadata().getName());
            final Map<String, Object> metadata = (Map<String, Object>) map.get("metadata");
            final Map<String, Object> annotations = (Map<String, Object>) metadata.get("annotations");
            editAction.accept(annotations);
            operations.edit(map);
            return this.toResource(map);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private SerializedEntandoResource toResource(Map<String, Object> map) {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            return objectMapper.readValue(objectMapper.writeValueAsString(map), SerializedEntandoResource.class);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
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
    public void removeSuccessfullyCompletedPods(SerializedEntandoResource resource) {
        String namespace = client.getNamespace();
        FilterWatchListDeletable<Pod, PodList> podResource = client.pods().inNamespace(namespace).withLabels(
                CoordinatorUtils.podLabelsFor(resource));
        try {
            waitForCompletionOfPods(podResource);
            removePodsAndWait(podResource);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
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
            LOGGER.log(Level.WARNING, () -> format(
                    "Some pods remained active after the removal delay period. You can consider increasing the setting %s ",
                    ControllerCoordinatorProperty.ENTANDO_K8S_CONTROLLER_REMOVAL_DELAY.getJvmSystemProperty()));
        }
    }

}
