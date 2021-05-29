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

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

public class LogDelegator {

    private final Logger logger;
    private static final List<String> LOG_ENTRIES = new ArrayList<>();

    public LogDelegator(Class<?> forClass) {
        this.logger = Logger.getLogger(forClass.getName());
    }

    public static List<String> getLogEntries() {
        return LOG_ENTRIES;
    }

    public void log(Level level, Supplier<String> supplier) {
        if (ControllerCoordinatorConfig.storeLogEntries()) {
            getLogEntries().add(supplier.get());
        }
        logger.log(level, supplier);
    }

    public void log(Level level, Throwable throwable, Supplier<String> supplier) {
        logger.log(level, throwable, supplier);
        getLogEntries().add(supplier.get());
    }

}
