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

import io.fabric8.kubernetes.api.builder.Function;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.entando.kubernetes.controller.coordinator.EntandoResourceObserver;
import org.entando.kubernetes.controller.coordinator.SimpleEntandoOperations;
import org.entando.kubernetes.controller.inprocesstest.k8sclientdouble.AbstractK8SClientDouble;
import org.entando.kubernetes.controller.inprocesstest.k8sclientdouble.NamespaceDouble;
import org.entando.kubernetes.model.DoneableEntandoCustomResource;
import org.entando.kubernetes.model.EntandoCustomResource;

public class SimpleEntandoOperationsDouble<R extends EntandoCustomResource, D extends DoneableEntandoCustomResource<R, D>>
        extends AbstractK8SClientDouble
        implements SimpleEntandoOperations<R, D> {

    final Class<R> resourceClass;
    final Class<D> doneableClass;
    String namespace;

    @SuppressWarnings({"rawtypes", "unchecked"})
    public SimpleEntandoOperationsDouble(Map<String, NamespaceDouble> namespaces, Class<R> resourceClass, Class doneableClass) {
        super(namespaces);
        this.resourceClass = resourceClass;
        this.doneableClass = doneableClass;
    }

    @Override
    public SimpleEntandoOperations<R, D> inNamespace(String namespace) {
        this.namespace = namespace;
        return this;
    }

    @Override
    public SimpleEntandoOperations<R, D> inAnyNamespace() {
        this.namespace = null;
        return this;
    }

    @Override
    public void watch(EntandoResourceObserver<R, D> rldEntandoResourceObserver) {

    }

    @Override
    @SuppressWarnings("unchecked")
    public List<R> list() {
        return new ArrayList<>(
                CoordinatorK8SClientDouble.addMissingCustomResourceMaps(getNamespace(namespace)).getCustomResources(resourceClass)
                        .values());
    }

    @Override
    public D edit(R r) {
        Function<R, R> function = updated -> getNamespace(r).getCustomResources(resourceClass)
                .put(updated.getMetadata().getName(), updated);
        try {
            return doneableClass.getConstructor(resourceClass, Function.class).newInstance(r, function);
        } catch (InstantiationException | NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException(e);
        }
    }
}
