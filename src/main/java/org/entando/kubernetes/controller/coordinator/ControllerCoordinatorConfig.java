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

import java.util.Optional;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorConfigBase;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorSpiConfigProperty;

public class ControllerCoordinatorConfig extends EntandoOperatorConfigBase {

    private ControllerCoordinatorConfig() {
    }

    public static Optional<String> getOperatorServiceAccount() {
        return lookupProperty(ControllerCoordinatorProperty.ENTANDO_K8S_OPERATOR_SERVICEACCOUNT);
    }

    public static long getPodShutdownTimeoutSeconds() {
        return lookupProperty(EntandoOperatorSpiConfigProperty.ENTANDO_POD_SHUTDOWN_TIMEOUT_SECONDS).map(Long::valueOf).orElse(120L);
    }

    public static Integer getRemovalDelay() {
        return lookupProperty(ControllerCoordinatorProperty.ENTANDO_K8S_CONTROLLER_REMOVAL_DELAY)
                .map(Integer::parseInt)
                .orElse(30);
    }

}
