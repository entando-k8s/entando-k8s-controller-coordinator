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
import io.fabric8.kubernetes.api.model.GenericKubernetesResource;
import io.fabric8.kubernetes.api.model.GenericKubernetesResourceList;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.PodResource;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.entando.kubernetes.controller.spi.client.SerializedEntandoResource;
import org.entando.kubernetes.controller.spi.common.PodResult;
import org.entando.kubernetes.controller.spi.common.PodResult.State;
import org.entando.kubernetes.controller.support.client.impl.DefaultPodClient;

public class DefaultSimpleEntandoOperations extends DeathEventIssuerBase implements SimpleEntandoOperations {

    private static final Logger LOGGER = Logger.getLogger(DefaultSimpleEntandoOperations.class.getName());

    private final MixedOperation<GenericKubernetesResource, GenericKubernetesResourceList, Resource<GenericKubernetesResource>> operations;
    private final boolean anyNamespace;
    private final CustomResourceDefinitionContext definitionContext;

    public DefaultSimpleEntandoOperations(KubernetesClient client, CustomResourceDefinitionContext definitionContext,
            MixedOperation<GenericKubernetesResource, GenericKubernetesResourceList, Resource<GenericKubernetesResource>> operations, boolean anyNamespace) {
        super(client);
        this.definitionContext = definitionContext;
        this.operations = operations;
        this.anyNamespace = anyNamespace;
    }

    @Override
    public SimpleEntandoOperations inNamespace(String namespace) {
        return new DefaultSimpleEntandoOperations(client, definitionContext, (MixedOperation<GenericKubernetesResource, GenericKubernetesResourceList, Resource<GenericKubernetesResource>>) operations.inNamespace(namespace), false);
    }

    @Override
    public SimpleEntandoOperations inAnyNamespace() {
        return new DefaultSimpleEntandoOperations(client, getDefinitionContext(), (MixedOperation<GenericKubernetesResource, GenericKubernetesResourceList, Resource<GenericKubernetesResource>>) operations.inAnyNamespace(), true);
    }

    @Override
    public Watch watch(SerializedResourceWatcher observer) {
        Function<CustomResourceStringWatcher, Watch> restartingAction = customResourceWatcher -> {
            try {
                // In v6, use the asGenericWatcher() adapter to convert between types
                return operations.watch(customResourceWatcher.asGenericWatcher());
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, e,
                        () -> "EntandoResourceObserver registration failed. Can't recover. The container should restart now.");
                Liveness.dead();
                throw new IllegalStateException();
            }
        };
        return new CustomResourceStringWatcher(observer, definitionContext, restartingAction, this);
    }

    @Override
    public List<SerializedEntandoResource> list() {
        final GenericKubernetesResourceList resourceList = operations.list();
        return resourceList.getItems().stream().map(this::toResource).collect(Collectors.toList());
    }

    @Override
    public SerializedEntandoResource removeAnnotation(SerializedEntandoResource r, String name) {
        return editAnnotations(r, a -> a.remove(name));
    }

    @SuppressWarnings("unchecked")
    private SerializedEntandoResource editAnnotations(SerializedEntandoResource r, Consumer<Map<String, String>> editAction) {
        return ioSafe(() -> {
            GenericKubernetesResource resource = operations.inNamespace(r.getMetadata().getNamespace())
                    .withName(r.getMetadata().getName())
                    .get();

            Map<String, String> annotations = resource.getMetadata().getAnnotations();
            if (annotations == null) {
                annotations = new HashMap<>();
                resource.getMetadata().setAnnotations(annotations);
            }
            editAction.accept(annotations);

            GenericKubernetesResource updated = operations.inNamespace(r.getMetadata().getNamespace())
                    .withName(r.getMetadata().getName())
                    .patch(resource);
            return this.toResource(updated);
        });
    }

    private SerializedEntandoResource toResource(GenericKubernetesResource resource) {
        return ioSafe(() -> {
            ObjectMapper objectMapper = new ObjectMapper();
            return objectMapper.readValue(objectMapper.writeValueAsString(resource), SerializedEntandoResource.class);
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
    public void removeSuccessfullyCompletedPods(SerializedEntandoResource resource) throws TimeoutException {
        String namespace = client.getNamespace();
        FilterWatchListDeletable<Pod, PodList, PodResource> podResource = client.pods().inNamespace(namespace).withLabels(
                CoordinatorUtils.podLabelsFor(resource));
        interruptionSafe(() -> {
            waitForCompletionOfPods(podResource);
            removePodsAndWait(podResource);
            return null;
        });
    }

    private void removePodsAndWait(FilterWatchListDeletable<Pod, PodList, PodResource> podResource) throws InterruptedException {
        podResource.delete();
        // Use the DefaultPodClient utility method to wait for pods to be deleted
        // This avoids blocking calls on the event loop
        DefaultPodClient.waitUntilConditionOnList(
                podResource,
                java.util.List::isEmpty,
                ControllerCoordinatorConfig.getPodShutdownTimeoutSeconds(),
                TimeUnit.SECONDS);
    }

    private void waitForCompletionOfPods(FilterWatchListDeletable<Pod, PodList, PodResource> podResource) throws InterruptedException {
        try {
            // Use the DefaultPodClient utility method to wait for all pods to complete
            // This avoids blocking calls on the event loop
            DefaultPodClient.waitUntilConditionOnList(
                    podResource,
                    podList -> podList.stream().allMatch(pod -> PodResult.of(pod).getState() == State.COMPLETED),
                    ControllerCoordinatorConfig.getRemovalDelay(),
                    TimeUnit.SECONDS);
        } catch (KubernetesClientException e) {
            LOGGER.log(Level.WARNING, e, () -> format(
                    "Some pods remained active after the removal delay period. You can consider increasing the setting %s ",
                    ControllerCoordinatorProperty.ENTANDO_K8S_CONTROLLER_REMOVAL_DELAY.getJvmSystemProperty()));
        }
    }

}
