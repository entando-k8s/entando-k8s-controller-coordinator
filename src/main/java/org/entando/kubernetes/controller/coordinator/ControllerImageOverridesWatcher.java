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
import io.fabric8.kubernetes.api.model.Event;

public class ControllerImageOverridesWatcher implements RestartingWatcher<ConfigMap> {

    private final SimpleKubernetesClient client;
    private ConfigMap controllerImageOverrides;

    public ControllerImageOverridesWatcher(SimpleKubernetesClient client) {
        this.client = client;
        this.controllerImageOverrides = client.findOrCreateControllerConfigMap(CoordinatorUtils.CONTROLLER_IMAGE_OVERRIDES_CONFIGMAP);
        getRestartingAction().run();
    }

    @Override
    public void eventReceived(Action action, ConfigMap configMap) {
        this.controllerImageOverrides = configMap;
    }

    public ConfigMap getControllerImageOverrides() {
        return controllerImageOverrides;
    }

    @Override
    public Runnable getRestartingAction() {
        return () -> client.watchControllerConfigMap(CoordinatorUtils.CONTROLLER_IMAGE_OVERRIDES_CONFIGMAP, this);
    }

    @Override
    public void issueOperatorDeathEvent(Event event) {
        client.issueOperatorDeathEvent(event);
    }
}
