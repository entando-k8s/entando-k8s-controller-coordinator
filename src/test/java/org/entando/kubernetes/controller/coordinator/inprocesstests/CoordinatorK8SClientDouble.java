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

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import org.entando.kubernetes.controller.support.client.doubles.EntandoResourceClientDouble;
import org.entando.kubernetes.controller.support.client.doubles.NamespaceDouble;
import org.entando.kubernetes.controller.support.client.doubles.SimpleK8SClientDouble;
import org.entando.kubernetes.model.compositeapp.EntandoCompositeApp;
import org.entando.kubernetes.model.debundle.EntandoDeBundle;

public class CoordinatorK8SClientDouble extends SimpleK8SClientDouble {

    SimpleEntandoOperationsRegistryDouble registryDouble = new SimpleEntandoOperationsRegistryDouble(this);
    private EntandoResourceClientDouble entandoResourceClientDouble;

    @Override
    public Map<String, NamespaceDouble> getNamespaces() {
        return super.getNamespaces();
    }

    public SimpleEntandoOperationsRegistryDouble getOperationsRegistry() {
        return registryDouble;
    }

    @Override
    public EntandoResourceClientDouble entandoResources() {
        if (entandoResourceClientDouble == null) {
            entandoResourceClientDouble = new EntandoResourceClientDouble(getNamespaces()) {
                @Override
                protected NamespaceDouble getNamespace(String namespace) {
                    //TODO fix this in NamespaceDouble
                    return addMissingCustomResourceMaps(super.getNamespace(namespace));
                }
            };
        }
        return entandoResourceClientDouble;
    }

    @SuppressWarnings("unchecked")
    public static NamespaceDouble addMissingCustomResourceMaps(NamespaceDouble theNamespace) {
        final Map<Class<?>, Map<String, Object>> customResources;
        try {
            final Field field = NamespaceDouble.class.getDeclaredField("customResources");
            field.setAccessible(true);
            customResources = (Map<Class<?>, Map<String, Object>>) field.get(theNamespace);
        } catch (IllegalAccessException | NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
        customResources.computeIfAbsent(EntandoCompositeApp.class, k -> new HashMap<>());
        customResources.computeIfAbsent(EntandoDeBundle.class, k -> new HashMap<>());
        return theNamespace;
    }
}
