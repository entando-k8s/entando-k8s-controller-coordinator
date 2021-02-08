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
import org.entando.kubernetes.model.DoneableEntandoCustomResource;
import org.entando.kubernetes.model.EntandoCustomResource;
import org.entando.kubernetes.model.EntandoResourceOperationsRegistry;

public class DefaultSimpleEntandoOperationsFactory implements SimpleEntandoOperationsRegistry {

    private final EntandoResourceOperationsRegistry registry;

    public DefaultSimpleEntandoOperationsFactory(KubernetesClient client) {
        this.registry = new EntandoResourceOperationsRegistry(client);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <R extends EntandoCustomResource, D extends DoneableEntandoCustomResource<R, D>> SimpleEntandoOperations<R, D> getOperations(
            Class<R> clzz) {
        return new DefaultSimpleEntandoOperations(registry.getOperations(clzz));
    }
}
