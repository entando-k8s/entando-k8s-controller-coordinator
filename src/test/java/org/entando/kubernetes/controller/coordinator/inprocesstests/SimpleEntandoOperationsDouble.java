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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.entando.kubernetes.controller.coordinator.EntandoResourceObserver;
import org.entando.kubernetes.controller.coordinator.SimpleEntandoOperations;
import org.entando.kubernetes.controller.support.client.doubles.AbstractK8SClientDouble;
import org.entando.kubernetes.controller.support.client.doubles.NamespaceDouble;
import org.entando.kubernetes.model.common.EntandoCustomResource;

public class SimpleEntandoOperationsDouble<R extends EntandoCustomResource> extends AbstractK8SClientDouble implements
        SimpleEntandoOperations<R> {

    final Class<R> resourceClass;
    String namespace;

    @SuppressWarnings({"rawtypes", "unchecked"})
    public SimpleEntandoOperationsDouble(ConcurrentHashMap<String, NamespaceDouble> namespaces, Class<R> resourceClass) {
        super(namespaces);
        this.resourceClass = resourceClass;
    }

    @Override
    public SimpleEntandoOperations<R> inNamespace(String namespace) {
        this.namespace = namespace;
        return this;
    }

    @Override
    public SimpleEntandoOperations<R> inAnyNamespace() {
        this.namespace = null;
        return this;
    }

    @Override
    public void watch(EntandoResourceObserver<R> rldEntandoResourceObserver) {

    }

    @Override
    public List<R> list() {
        return new ArrayList<>(getNamespace(namespace).getCustomResources(resourceClass).values());
    }

    @Override
    public R removeAnnotation(R r, String name) {
        r.getMetadata().getAnnotations().remove(name);
        return r;
    }

    @Override
    public R putAnnotation(R r, String name, String value) {
        r.getMetadata().getAnnotations().put(name, value);
        return r;
    }

    @Override
    public void removeSuccessfullyCompletedPods(R resource) {

    }
}
