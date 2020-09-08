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

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;

import io.fabric8.kubernetes.client.Watcher.Action;
import io.fabric8.kubernetes.client.dsl.internal.CustomResourceOperationsImpl;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import org.entando.kubernetes.controller.coordinator.EntandoControllerCoordinator;
import org.entando.kubernetes.controller.coordinator.EntandoDeploymentPhaseWatcher;
import org.entando.kubernetes.model.DbmsVendor;
import org.entando.kubernetes.model.EntandoDeploymentPhase;
import org.entando.kubernetes.model.JeeServer;
import org.entando.kubernetes.model.app.DoneableEntandoApp;
import org.entando.kubernetes.model.app.EntandoApp;
import org.entando.kubernetes.model.app.EntandoAppBuilder;
import org.entando.kubernetes.model.app.EntandoAppList;
import org.entando.kubernetes.model.app.EntandoAppOperationFactory;
import org.junit.Rule;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.migrationsupport.rules.EnableRuleMigrationSupport;

@Tags({@Tag("in-process"),@Tag("unit")})
@EnableRuleMigrationSupport
public class DeploymentPhaseWatcherTest {

    @Rule
    public KubernetesServer server = new KubernetesServer(false, true);
    private EntandoControllerCoordinator coordinator;

    @Test
    public void testWatch() {
        //Given I have an EntandoApp
        CustomResourceOperationsImpl<EntandoApp, EntandoAppList, DoneableEntandoApp> operations = EntandoAppOperationFactory
                .produceAllEntandoApps(server.getClient());
        EntandoApp entandoApp = operations.create(new EntandoAppBuilder()
                .withNewMetadata().withName("test-app").withNamespace(server.getClient().getNamespace()).endMetadata()
                .withNewSpec().withStandardServerImage(JeeServer.WILDFLY).withDbms(DbmsVendor.POSTGRESQL).endSpec()
                .build());
        //And I will be watching the deploymentPhase on this app
        EntandoDeploymentPhaseWatcher<EntandoApp, EntandoAppList, DoneableEntandoApp> watcher = new EntandoDeploymentPhaseWatcher<>(
                operations);
        //And that it will take 500 milliseconds for processing to start on the entandoApp
        new Thread(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                throw new IllegalStateException("Thread interrupted");
            }
            operations.inNamespace(entandoApp.getMetadata().getNamespace()).withName(entandoApp.getMetadata().getName()).edit()
                    .withPhase(EntandoDeploymentPhase.STARTED)
                    .done();
            watcher.eventReceived(Action.MODIFIED, entandoApp);
        }).start();
        long start = System.currentTimeMillis();
        //When I wait for the EntandoApp to be processed.
        watcher.waitToBeProcessed(entandoApp);
        //Then it will continue within just over 500 milliseconds
        assertThat(System.currentTimeMillis(), is(greaterThan(start + 500)));
        assertThat(System.currentTimeMillis(), is(lessThan(start + 2000)));

    }
}
