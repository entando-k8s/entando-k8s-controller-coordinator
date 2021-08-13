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

package org.entando.kubernetes.controller.coordinator.common;

import static java.util.Optional.ofNullable;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.entando.kubernetes.controller.coordinator.ControllerCoordinatorConfig;
import org.entando.kubernetes.controller.coordinator.CoordinatorUtils;
import org.entando.kubernetes.controller.coordinator.CustomResourceStringWatcher;
import org.entando.kubernetes.controller.coordinator.SerializedResourceWatcher;
import org.entando.kubernetes.controller.coordinator.SimpleEntandoOperations;
import org.entando.kubernetes.controller.spi.client.SerializedEntandoResource;
import org.entando.kubernetes.controller.spi.common.PodResult;
import org.entando.kubernetes.controller.spi.common.PodResult.State;
import org.entando.kubernetes.controller.support.client.doubles.AbstractK8SClientDouble;
import org.entando.kubernetes.controller.support.client.doubles.ClusterDouble;
import org.entando.kubernetes.controller.support.client.doubles.NamespaceDouble;

public class SimpleEntandoOperationsDouble extends AbstractK8SClientDouble implements SimpleEntandoOperations {

    private final CustomResourceDefinitionContext definitionContext;
    String namespace;

    public SimpleEntandoOperationsDouble(ConcurrentHashMap<String, NamespaceDouble> namespaces,
            CustomResourceDefinitionContext definitionContext, ClusterDouble cluster) {
        super(namespaces, cluster);
        this.definitionContext = definitionContext;

    }

    @Override
    public SimpleEntandoOperations inNamespace(String namespace) {
        this.namespace = namespace;
        return this;
    }

    @Override
    public CustomResourceDefinitionContext getDefinitionContext() {
        return definitionContext;
    }

    @Override
    public SimpleEntandoOperations inAnyNamespace() {
        this.namespace = null;
        return this;
    }

    @Override
    public Watch watch(SerializedResourceWatcher watcher) {
        final CustomResourceStringWatcher stringWatcher = new CustomResourceStringWatcher(watcher,
                getDefinitionContext(),
                customResourceWatcher -> {
                    final Watcher<HasMetadata> watcherDelegate = new Watcher<>() {
                        @Override
                        public void eventReceived(Action action, HasMetadata hasMetadata) {
                            try {
                                customResourceWatcher.eventReceived(action, new ObjectMapper().writeValueAsString(hasMetadata));
                            } catch (JsonProcessingException e) {
                                throw new IllegalStateException(e);
                            }
                        }

                        @Override
                        public void onClose(WatcherException e) {
                            customResourceWatcher.onClose(e);
                        }
                    };
                    if (ofNullable(this.namespace).isPresent()) {
                        getCluster().getResourceProcessor().watch(watcherDelegate, definitionContext, this.namespace);
                    } else {
                        getCluster().getResourceProcessor().watch(watcherDelegate, definitionContext);
                    }
                    return () -> {
                    };
                }, this);
        return stringWatcher;
    }

    @Override
    public List<SerializedEntandoResource> list() {
        if (namespace == null) {
            return getNamespaces().values().stream()
                    .flatMap(namespaceDouble -> namespaceDouble.getCustomResources(definitionContext.getKind()).values().stream())
                    .map(SerializedEntandoResource.class::cast)
                    .collect(Collectors.toList());
        } else {
            return getNamespace(namespace).getCustomResources(definitionContext.getKind()).values().stream()
                    .map(SerializedEntandoResource.class::cast)
                    .collect(Collectors.toList());
        }
    }

    @Override
    public SerializedEntandoResource removeAnnotation(SerializedEntandoResource r, String name) {
        r.getMetadata().getAnnotations().remove(name);
        getCluster().getResourceProcessor().processResource(getNamespace(r).getCustomResources(r.getKind()), r);
        return r;
    }

    @Override
    public SerializedEntandoResource putAnnotation(SerializedEntandoResource r, String name, String value) {
        if (r.getMetadata().getAnnotations() == null) {
            r.getMetadata().setAnnotations(new HashMap<>());
        }
        r.getMetadata().getAnnotations().put(name, value);
        getCluster().getResourceProcessor().processResource(getNamespace(r).getCustomResources(r.getKind()), r);
        return r;
    }

    @Override
    public void removeSuccessfullyCompletedPods(SerializedEntandoResource resource) {
        final Map<String, String> labels = CoordinatorUtils.podLabelsFor(resource);
        final Map<String, Pod> pods = getNamespace(getControllerNamespace()).getPods();
        await().atMost(ControllerCoordinatorConfig.getRemovalDelay(), TimeUnit.SECONDS).until(() ->
                pods.values().stream()
                        .allMatch(
                                pod -> CoordinatorTestUtils.matchesLabels(labels, pod) && PodResult.of(pod).getState() == State.COMPLETED));
        pods.values().stream()
                .filter(pod -> completedSuccessfully(labels, pod))
                .forEach(pod -> {
                    getCluster().getResourceProcessor().processResource(pods, pod);
                    pods.remove(pod.getMetadata().getName());
                });

    }

    private boolean completedSuccessfully(Map<String, String> labels, Pod pod) {
        return CoordinatorTestUtils.matchesLabels(labels, pod) && PodResult.of(pod).getState() == State.COMPLETED
                && !PodResult.of(pod).hasFailed();
    }

    @Override
    public String getControllerNamespace() {
        return CONTROLLER_NAMESPACE;
    }

    @Override
    public void issueOperatorDeathEvent(Event event) {
        event.getMetadata().setNamespace(getControllerNamespace());
        getNamespace(event).putEvent(event);
    }
}
