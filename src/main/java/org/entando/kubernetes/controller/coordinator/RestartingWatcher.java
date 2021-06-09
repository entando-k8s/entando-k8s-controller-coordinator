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

import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.WatcherException;
import java.util.logging.Level;
import java.util.logging.Logger;

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
            Liveness.dead();
        }

    }
}
