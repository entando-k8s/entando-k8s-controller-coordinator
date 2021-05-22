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

import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.entando.kubernetes.controller.coordinator.SimpleEntandoOperations;
import org.entando.kubernetes.controller.spi.client.SerializedEntandoResource;
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
    public SimpleEntandoOperations inAnyNamespace() {
        this.namespace = null;
        return this;
    }

    @Override
    public void watch(Watcher<SerializedEntandoResource> rldEntandoResourceObserver) {

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

        return r;
    }

    @Override
    public SerializedEntandoResource putAnnotation(SerializedEntandoResource r, String name, String value) {
        r.getMetadata().getAnnotations().put(name, value);
        return r;
    }

    @Override
    public void removeSuccessfullyCompletedPods(SerializedEntandoResource resource) {

    }

    @Override
    public String getControllerNamespace() {
        return namespace;
    }
}
