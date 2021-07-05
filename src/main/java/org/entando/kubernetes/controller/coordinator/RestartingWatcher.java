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

import io.fabric8.kubernetes.api.model.EventBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorSpiConfig;
import org.entando.kubernetes.controller.spi.common.FormatUtils;

public interface RestartingWatcher<T> extends Watcher<T> {

    Runnable getRestartingAction();

    @Override
    default void onClose(WatcherException cause) {
        if (cause.getMessage().contains("resourceVersion") && cause.getMessage().contains("too old")) {
            Logger.getLogger(getClass().getName())
                    .log(Level.WARNING, () -> "EntandoResourceObserver closed due to out of date resourceVersion. Reconnecting ... ");
            getRestartingAction().run();
        } else {
            Logger.getLogger(getClass().getName())
                    .log(Level.SEVERE, cause, () -> "EntandoResourceObserver closed. Can't reconnect. The container should restart now.");
            //This code is temporary just to try to figure out why it is restarting so often
            final DefaultKubernetesClient client = new DefaultKubernetesClient();
            final Pod pod = client.pods().inNamespace(client.getNamespace()).withName(EntandoOperatorSpiConfig.getControllerPodName())
                    .get();
            final StringWriter message = new StringWriter();
            cause.printStackTrace(new PrintWriter(message));
            client.v1().events().inNamespace(client.getNamespace()).create(new EventBuilder()
                    .withNewMetadata()
                    .withNamespace(client.getNamespace())
                    .withName(EntandoOperatorSpiConfig.getControllerPodName() + "-restart")
                    .addToLabels("entando-operator-restarted", "true")
                    .endMetadata()
                    .withCount(1)
                    .withFirstTimestamp(FormatUtils.format(LocalDateTime.now()))
                    .withLastTimestamp(FormatUtils.format(LocalDateTime.now()))
                    .withNewInvolvedObject()
                    .withApiVersion("")
                    .withKind("Pod")
                    .withNamespace(client.getNamespace())
                    .withName(EntandoOperatorSpiConfig.getControllerPodName())
                    .withUid(pod.getMetadata().getUid())
                    .withResourceVersion(pod.getMetadata().getResourceVersion())
                    .withFieldPath("status")
                    .endInvolvedObject()
                    .withMessage(message.toString())
                    .build());
            Liveness.dead();
        }

    }
}
