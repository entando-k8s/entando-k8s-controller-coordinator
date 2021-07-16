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

import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.EventBuilder;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorSpiConfig;
import org.entando.kubernetes.controller.spi.common.FormatUtils;
import org.entando.kubernetes.controller.spi.common.NameUtils;

public interface RestartingWatcher<T> extends Watcher<T> {

    Runnable getRestartingAction();

    void issueOperatorDeathEvent(Event event);

    @Override
    default void onClose(WatcherException cause) {
        final StringWriter stringWriter = new StringWriter();
        cause.printStackTrace(new PrintWriter(stringWriter));
        String message = stringWriter.toString();
        if (message.contains("too old")) {
            Logger.getLogger(getClass().getName())
                    .log(Level.WARNING, () -> "EntandoResourceObserver closed due to out of date resourceVersion. Reconnecting ... ");
            getRestartingAction().run();
        } else {
            Logger.getLogger(getClass().getName())
                    .log(Level.SEVERE, cause, () -> "EntandoResourceObserver closed. Can't reconnect. The container should restart now.");
            Event event = new EventBuilder()
                    .withNewMetadata()
                    .withName(EntandoOperatorSpiConfig.getControllerPodName() + "-restart-" + NameUtils.randomNumeric(4))
                    .addToLabels("entando-operator-restarted", "true")
                    .endMetadata()
                    .withCount(1)
                    .withFirstTimestamp(FormatUtils.format(LocalDateTime.now()))
                    .withLastTimestamp(FormatUtils.format(LocalDateTime.now()))
                    .withMessage(message)
                    .build();
            issueOperatorDeathEvent(event);
            Liveness.dead();
        }

    }
}
