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

import com.google.common.base.Strings;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watcher.Action;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import io.quarkus.runtime.StartupEvent;
import java.io.Serializable;
import org.apache.commons.lang3.RandomStringUtils;
import org.entando.kubernetes.controller.coordinator.AbstractControllerCoordinatorTest;
import org.entando.kubernetes.controller.coordinator.EntandoControllerCoordinator;
import org.entando.kubernetes.model.EntandoBaseCustomResource;
import org.junit.Rule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.migrationsupport.rules.EnableRuleMigrationSupport;

@Tags({@Tag("in-process"), @Tag("component"), @Tag("pre-deployment")})
@EnableRuleMigrationSupport
class ControllerCoordinatorMockedTest extends AbstractControllerCoordinatorTest {

    @Rule
    public KubernetesServer server = new KubernetesServer(false, true);
    private EntandoControllerCoordinator coordinator;

    @BeforeEach
    public void prepareCoordinator() {
        this.coordinator = new EntandoControllerCoordinator(getClient());
        coordinator.onStartup(new StartupEvent());
    }

    @Override
    protected KubernetesClient getClient() {
        return server.getClient().inNamespace(NAMESPACE);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected <S extends Serializable, R extends EntandoBaseCustomResource<S>> void afterCreate(R resource) {
        if (resource.getMetadata().getUid() == null) {
            resource.getMetadata().setUid(RandomStringUtils.randomAlphanumeric(8));
        }
        if (Strings.isNullOrEmpty(resource.getMetadata().getResourceVersion())) {
            resource.getMetadata().setResourceVersion(Integer.toString(1));
        }
        coordinator.getObserver((Class<R>) resource.getClass()).get(0).eventReceived(Action.ADDED, resource);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected <S extends Serializable, R extends EntandoBaseCustomResource<S>> void afterModified(R resource) {
        if (resource.getMetadata().getUid() == null) {
            resource.getMetadata().setUid(RandomStringUtils.randomAlphanumeric(8));
        }
        coordinator.getObserver((Class<R>) resource.getClass()).get(0).eventReceived(Action.MODIFIED, resource);
    }

}
