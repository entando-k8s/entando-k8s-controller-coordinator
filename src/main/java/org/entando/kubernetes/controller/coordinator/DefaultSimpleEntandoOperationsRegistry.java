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

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.internal.CustomResourceOperationsImpl;
import org.entando.kubernetes.controller.support.client.impl.DefaultSimpleK8SClient;
import org.entando.kubernetes.model.common.EntandoCustomResource;

public class DefaultSimpleEntandoOperationsRegistry implements SimpleEntandoOperationsRegistry {

    private final DefaultSimpleK8SClient client;
    private final KubernetesClient kubernetesClient;

    public DefaultSimpleEntandoOperationsRegistry(KubernetesClient client) {
        this.client = new DefaultSimpleK8SClient(client);
        this.kubernetesClient = client;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <R extends EntandoCustomResource> SimpleEntandoOperations<R> getOperations(Class<R> clzz) {
        return new DefaultSimpleEntandoOperations(client, (CustomResourceOperationsImpl) kubernetesClient.customResources((Class)clzz));
    }
}
