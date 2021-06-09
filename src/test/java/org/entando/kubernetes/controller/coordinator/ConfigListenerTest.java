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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.Watcher.Action;
import io.fabric8.kubernetes.client.WatcherException;
import java.io.File;
import java.nio.file.Paths;
import org.entando.kubernetes.controller.coordinator.common.SimpleKubernetesClientDouble;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorConfigBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;

@Tags({@Tag("in-process"), @Tag("unit"), @Tag("pre-deployment")})
class ConfigListenerTest {

    @AfterEach
    void resetConfigMap() {
        EntandoOperatorConfigBase.setConfigMap(null);
    }

    @Test
    void shoulReflectLatestConfig() {
        //Given the operator is alive and listening to K8S resource events
        final ConfigListener configListener = new ConfigListener(new SimpleKubernetesClientDouble());
        //When the entando-operator-config configmap is updated
        configListener.eventReceived(Action.MODIFIED, new ConfigMapBuilder()
                .addToData(ControllerCoordinatorProperty.ENTANDO_K8S_CONTROLLER_REMOVAL_DELAY.getJvmSystemProperty(), "400")
                .build());
        //Then the latest property value reflects
        assertThat(
                EntandoOperatorConfigBase.lookupProperty(ControllerCoordinatorProperty.ENTANDO_K8S_CONTROLLER_REMOVAL_DELAY).get(),
                is("400"));
    }

    @Test
    void shouldKillTheOperator() {
        //Given the operator is alive and listening to K8S resource events
        final File file = Paths.get("/tmp/EntandoControllerCoordinator.ready").toFile();
        Liveness.alive();
        assertTrue(file.exists());
        final ConfigListener configListener = new ConfigListener(new SimpleKubernetesClientDouble());
        //When the Operator loses the connection to the ConfigMap listener
        configListener.onClose(new WatcherException("Something went wrong"));
        //Then the operator has been killed
        assertFalse(file.exists());
    }
}
