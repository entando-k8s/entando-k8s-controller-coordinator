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
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorConfigBase;

public class ConfigListener implements Watcher<ConfigMap> {

    private static final Logger LOGGER = Logger.getLogger(ConfigListener.class.getName());

    @Override
    public void eventReceived(Action action, ConfigMap resource) {
        EntandoOperatorConfigBase.setConfigMap(resource);
    }

    @Override
    public void onClose(WatcherException cause) {
        LOGGER.log(Level.SEVERE, cause, () -> "ConfigListener closed. The container should restart now.");
        Liveness.dead();
    }
}
