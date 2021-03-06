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

import org.entando.kubernetes.controller.spi.common.ConfigProperty;

public enum EntandoControllerCoordinatorProperty implements ConfigProperty {
    ENTANDO_K8S_PROBE_FOLDER("entando.k8s.probe.folder"),
    ENTANDO_K8S_OPERATOR_VERSION("entando.k8s.operator.version"),
    ENTANDO_K8S_OPERATOR_VERSION_TO_REPLACE("entando.k8s.operator.version.to.replace"),
    ENTANDO_K8S_CONTROLLER_REMOVAL_DELAY("entando.k8s.controller.removal.delay");

    private final String jvmSystemProperty;

    EntandoControllerCoordinatorProperty(String s) {
        this.jvmSystemProperty = s;
    }

    @Override
    public String getJvmSystemProperty() {
        return jvmSystemProperty;
    }
}
