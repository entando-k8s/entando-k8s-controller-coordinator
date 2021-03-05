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

import java.util.HashMap;
import java.util.Map;
import org.entando.kubernetes.controller.coordinator.SimpleEntandoOperations;
import org.entando.kubernetes.controller.coordinator.SimpleEntandoOperationsRegistry;
import org.entando.kubernetes.controller.support.client.doubles.SimpleK8SClientDouble;
import org.entando.kubernetes.model.DoneableEntandoCustomResource;
import org.entando.kubernetes.model.EntandoCustomResource;
import org.entando.kubernetes.model.keycloakserver.DoneableEntandoKeycloakServer;
import org.entando.kubernetes.model.keycloakserver.EntandoKeycloakServer;

public class SimpleEntandoOperationsRegistryDouble implements SimpleEntandoOperationsRegistry {

    private final SimpleK8SClientDouble coordinatorK8SClientDouble;
    private Map<Class<? extends EntandoCustomResource>, Class<? extends DoneableEntandoCustomResource<?, ?>>> doneableMap = new HashMap<>();

    {
        doneableMap.put(EntandoKeycloakServer.class, DoneableEntandoKeycloakServer.class);
    }

    public SimpleEntandoOperationsRegistryDouble(SimpleK8SClientDouble coordinatorK8SClientDouble) {
        this.coordinatorK8SClientDouble = coordinatorK8SClientDouble;
    }

    @Override
    public <R extends EntandoCustomResource, D extends DoneableEntandoCustomResource<R, D>> SimpleEntandoOperations<R, D> getOperations(
            Class<R> clzz) {
        return new SimpleEntandoOperationsDouble<>(coordinatorK8SClientDouble.getNamespaces(), clzz, doneableMap.get(clzz));
    }
}
