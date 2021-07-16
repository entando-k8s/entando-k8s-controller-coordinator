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

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.CustomResourceDefinition;
import io.fabric8.kubernetes.client.WatcherException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

class CrdNameMapSync implements RestartingWatcher<CustomResourceDefinition> {

    private static final Logger LOGGER = Logger.getLogger(CrdNameMapSync.class.getName());
    private ConfigMap crdNameMap;
    private final SimpleKubernetesClient client;

    CrdNameMapSync(SimpleKubernetesClient client, List<CustomResourceDefinition> customResourceDefinitions) {
        this.client = client;
        crdNameMap = client.findOrCreateControllerConfigMap(CoordinatorUtils.ENTANDO_CRD_NAMES_CONFIGMAP_NAME);
        customResourceDefinitions.forEach(this::syncName);
        this.crdNameMap = client.patchControllerConfigMap(crdNameMap);
        getRestartingAction().run();
    }

    @Override
    public Runnable getRestartingAction() {
        return () -> this.client.watchCustomResourceDefinitions(this);
    }

    @Override
    public void issueOperatorDeathEvent(Event event) {
        client.issueOperatorDeathEvent(event);
    }

    @Override
    public void eventReceived(Action action, CustomResourceDefinition r) {
        if (CoordinatorUtils.isOfInterest(r)) {
            crdNameMap = client.findOrCreateControllerConfigMap(CoordinatorUtils.ENTANDO_CRD_NAMES_CONFIGMAP_NAME);
            syncName(r);
            this.crdNameMap = client.patchControllerConfigMap(crdNameMap);
        }
    }

    private void syncName(CustomResourceDefinition r) {
        String key = CoordinatorUtils.keyOf(r);
        crdNameMap = new ConfigMapBuilder(crdNameMap).addToData(key, r.getMetadata().getName()).build();
    }

    public boolean isOfInterest(OwnerReference ownerReference) {
        final String key = CoordinatorUtils.keyOf(ownerReference);
        return CoordinatorUtils.resolveValue(crdNameMap, key).isPresent();
    }
}
