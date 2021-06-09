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

import static org.entando.kubernetes.controller.spi.common.ExceptionUtils.ioSafe;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import java.util.function.Function;
import org.entando.kubernetes.controller.spi.client.SerializedEntandoResource;

public class CustomResourceStringWatcher implements RestartingWatcher<String>, Watch {

    private final SerializedResourceWatcher observer;
    private final CustomResourceDefinitionContext definitionContext;
    private final Function<CustomResourceStringWatcher, Watch> restartingFunction;
    private Watch watch;

    public Watch getWatch() {
        return watch;
    }

    public CustomResourceStringWatcher(SerializedResourceWatcher observer,
            CustomResourceDefinitionContext definitionContext,
            Function<CustomResourceStringWatcher, Watch> restartingFunction) {
        this.observer = observer;
        this.definitionContext = definitionContext;
        this.restartingFunction = restartingFunction;
        getRestartingAction().run();
    }

    @Override
    public Runnable getRestartingAction() {
        return () -> this.watch = restartingFunction.apply(this);
    }

    @Override
    public void eventReceived(Action action, String s) {
        ioSafe(() -> {
            final SerializedEntandoResource r = new ObjectMapper().readValue(s, SerializedEntandoResource.class);
            r.setDefinition(definitionContext);
            observer.eventReceived(action, r);
            return null;
        });
    }

    @Override
    public void close() {
        getWatch().close();
    }

}
