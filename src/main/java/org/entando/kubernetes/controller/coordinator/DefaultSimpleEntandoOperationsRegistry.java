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
import org.entando.kubernetes.client.DefaultSimpleK8SClient;
import org.entando.kubernetes.model.DoneableEntandoCustomResource;
import org.entando.kubernetes.model.EntandoCustomResource;
import org.entando.kubernetes.model.EntandoResourceOperationsRegistry;

public class DefaultSimpleEntandoOperationsRegistry implements SimpleEntandoOperationsRegistry {

    private final EntandoResourceOperationsRegistry registry;
    private final DefaultSimpleK8SClient client;

    public DefaultSimpleEntandoOperationsRegistry(KubernetesClient client) {
        this.registry = new EntandoResourceOperationsRegistry(client);
        this.client = new DefaultSimpleK8SClient(client);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <R extends EntandoCustomResource, D extends DoneableEntandoCustomResource<R, D>> SimpleEntandoOperations<R, D> getOperations(
            Class<R> clzz) {
        return new DefaultSimpleEntandoOperations(client, registry.getOperations(clzz));
    }
}
