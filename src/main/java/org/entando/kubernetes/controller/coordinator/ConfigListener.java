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

import static java.lang.String.format;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Event;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorConfigBase;

public class ConfigListener implements RestartingWatcher<ConfigMap> {

    private static final Logger LOGGER = Logger.getLogger(ConfigListener.class.getName());
    private final SimpleKubernetesClient client;

    public ConfigListener(SimpleKubernetesClient client) {
        this.client = client;
        getRestartingAction().run();
    }

    @Override
    public void eventReceived(Action action, ConfigMap resource) {
        LOGGER.info(() -> format("ConfigMap %s/%s action received: %s:%n%s",
                resource.getMetadata().getNamespace(),
                resource.getMetadata().getName(),
                action.name(),
                resource.getData().entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).collect(Collectors.joining("\n"))));
        if (action == Action.DELETED) {
            EntandoOperatorConfigBase.setConfigMap(null);
        } else {
            EntandoOperatorConfigBase.setConfigMap(resource);
        }
    }

    @Override
    public Runnable getRestartingAction() {
        return () -> client.watchControllerConfigMap(CoordinatorUtils.ENTANDO_OPERATOR_CONFIG, this);
    }

    @Override

    public void issueOperatorDeathEvent(Event event) {
        client.issueOperatorDeathEvent(event);
    }

}
