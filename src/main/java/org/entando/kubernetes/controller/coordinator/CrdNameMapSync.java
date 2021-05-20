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
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.CustomResourceDefinition;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.entando.kubernetes.model.common.EntandoCustomResource;

class CrdNameMapSync implements Watcher<CustomResourceDefinition> {

    public static final String ENTANDO_CRD_NAMES_CONFIGMAP_NAME = "entando-crd-names";
    private static final Logger LOGGER = Logger.getLogger(CrdNameMapSync.class.getName());
    private ConfigMap crdNameMap;
    private final SimpleKubernetesClient client;

    CrdNameMapSync(SimpleKubernetesClient client, List<CustomResourceDefinition> customResourceDefinitions) {
        this.client = client;
        crdNameMap = client.findOrCreateControllerConfigMap(ENTANDO_CRD_NAMES_CONFIGMAP_NAME);
        customResourceDefinitions.forEach(this::syncName);
        this.crdNameMap = client.patchControllerConfigMap(crdNameMap);
        client.watchCustomResourceDefinitions(this);
    }

    @Override
    public void eventReceived(Action action, CustomResourceDefinition r) {
        if (CoordinatorUtils.isOfInterest(r)) {
            crdNameMap = client.findOrCreateControllerConfigMap(ENTANDO_CRD_NAMES_CONFIGMAP_NAME);
            syncName(r);
            this.crdNameMap = client.patchControllerConfigMap(crdNameMap);
        }
    }

    private void syncName(CustomResourceDefinition r) {
        String key = CoordinatorUtils.keyOf(r);
        crdNameMap.getData().put(key, r.getMetadata().getName());
    }

    @Override
    public void onClose(WatcherException e) {
        LOGGER.log(Level.SEVERE, e, () -> "EntandoControllerCoordinator closed. Can't reconnect. The container should restart now.");
        Liveness.dead();
    }

    public String getCrdName(EntandoCustomResource r) {
        final String key = CoordinatorUtils.keyOf(r);
        return crdNameMap.getData().get(key);
    }

    public boolean isOfInterest(OwnerReference ownerReference) {
        final String key = CoordinatorUtils.keyOf(ownerReference);
        return crdNameMap.getData().containsKey(key);
    }
}
