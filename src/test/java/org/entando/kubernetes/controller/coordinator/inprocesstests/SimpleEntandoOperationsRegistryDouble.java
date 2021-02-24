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

import org.entando.kubernetes.controller.coordinator.SimpleEntandoOperations;
import org.entando.kubernetes.controller.coordinator.SimpleEntandoOperationsRegistry;
import org.entando.kubernetes.model.EntandoCustomResource;

public class SimpleEntandoOperationsRegistryDouble implements SimpleEntandoOperationsRegistry {

    private final CoordinatorK8SClientDouble coordinatorK8SClientDouble;

    public SimpleEntandoOperationsRegistryDouble(CoordinatorK8SClientDouble coordinatorK8SClientDouble) {
        this.coordinatorK8SClientDouble = coordinatorK8SClientDouble;
    }

    @Override
    public <R extends EntandoCustomResource> SimpleEntandoOperations<R> getOperations(
            Class<R> clzz) {
        return new SimpleEntandoOperationsDouble<>(coordinatorK8SClientDouble.getNamespaces(), clzz);
    }
}
