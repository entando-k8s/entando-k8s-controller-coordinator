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

import io.fabric8.kubernetes.api.model.KubernetesResourceList;
import io.fabric8.kubernetes.client.dsl.MixedOperation;
import io.fabric8.kubernetes.client.dsl.Resource;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.entando.kubernetes.controller.support.client.SimpleK8SClient;
import org.entando.kubernetes.controller.support.common.KubeUtils;
import org.entando.kubernetes.model.EntandoCustomResource;

public class DefaultSimpleEntandoOperations<R extends EntandoCustomResource> implements SimpleEntandoOperations<R> {

    private final MixedOperation<R, KubernetesResourceList<R>, Resource<R>> operations;
    private final SimpleK8SClient<?> client;

    public DefaultSimpleEntandoOperations(SimpleK8SClient<?> client, MixedOperation<R, KubernetesResourceList<R>, Resource<R>> operation) {
        this.operations = operation;
        this.client = client;
    }

    @Override
    public SimpleEntandoOperations<R> inNamespace(String namespace) {
        return new DefaultSimpleEntandoOperations<>(client,
                (MixedOperation<R, KubernetesResourceList<R>, Resource<R>>) operations.inNamespace(namespace));
    }

    @Override
    public SimpleEntandoOperations<R> inAnyNamespace() {
        return new DefaultSimpleEntandoOperations<>(client,
                (MixedOperation<R, KubernetesResourceList<R>, Resource<R>>) operations.inAnyNamespace());
    }

    @Override
    public void watch(EntandoResourceObserver<R> rldEntandoResourceObserver) {
        operations.watch(rldEntandoResourceObserver);
    }

    @Override
    public List<R> list() {
        return operations.list().getItems();
    }

    @Override
    public R patch(R r, UnaryOperator<R> patch) {
        return operations.inNamespace(r.getMetadata().getNamespace()).withName(r.getMetadata().getName()).edit(patch);
    }

    @Override
    public void removeSuccessfullyCompletedPods(R resource) {
        this.client.pods().removeSuccessfullyCompletedPods(client.entandoResources().getNamespace(), Map.of(
                KubeUtils.ENTANDO_RESOURCE_KIND_LABEL_NAME, resource.getKind(),
                KubeUtils.ENTANDO_RESOURCE_NAMESPACE_LABEL_NAME, resource.getMetadata().getNamespace(),
                resource.getKind(), resource.getMetadata().getName()));
    }
}
