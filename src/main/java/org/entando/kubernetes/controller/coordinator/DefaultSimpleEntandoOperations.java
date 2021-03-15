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

import io.fabric8.kubernetes.client.CustomResourceList;
import io.fabric8.kubernetes.client.dsl.internal.CustomResourceOperationsImpl;
import java.util.List;
import java.util.Map;
import org.entando.kubernetes.controller.support.client.SimpleK8SClient;
import org.entando.kubernetes.controller.support.common.KubeUtils;
import org.entando.kubernetes.model.DoneableEntandoCustomResource;
import org.entando.kubernetes.model.EntandoCustomResource;

public class DefaultSimpleEntandoOperations<
        R extends EntandoCustomResource,
        D extends DoneableEntandoCustomResource<R, D>
        > implements SimpleEntandoOperations<R, D> {

    private final SimpleK8SClient<?> client;
    CustomResourceOperationsImpl<R, CustomResourceList<R>, D> operations;

    public DefaultSimpleEntandoOperations(SimpleK8SClient<?> client, CustomResourceOperationsImpl<R, CustomResourceList<R>, D> operations) {
        this.client = client;
        this.operations = operations;
    }

    @Override
    public SimpleEntandoOperations<R, D> inNamespace(String namespace) {
        return new DefaultSimpleEntandoOperations<>(client,
                (CustomResourceOperationsImpl<R, CustomResourceList<R>, D>) operations.inNamespace(namespace));
    }

    @Override
    public SimpleEntandoOperations<R, D> inAnyNamespace() {
        return new DefaultSimpleEntandoOperations<>(client,
                (CustomResourceOperationsImpl<R, CustomResourceList<R>, D>) operations.inAnyNamespace());
    }

    @Override
    public void watch(EntandoResourceObserver<R, D> rldEntandoResourceObserver) {
        operations.watch(rldEntandoResourceObserver);
    }

    @Override
    public List<R> list() {
        return operations.list().getItems();
    }

    @Override
    public D edit(R r) {
        return operations.inNamespace(r.getMetadata().getNamespace()).withName(r.getMetadata().getName()).edit();
    }

    @Override
    public void removeSuccessfullyCompletedPods(R resource) {
        this.client.pods().removeSuccessfullyCompletedPods(client.entandoResources().getNamespace(), Map.of(
                KubeUtils.ENTANDO_RESOURCE_KIND_LABEL_NAME, resource.getKind(),
                KubeUtils.ENTANDO_RESOURCE_NAMESPACE_LABEL_NAME, resource.getMetadata().getNamespace(),
                resource.getKind(), resource.getMetadata().getName()));
    }
}
