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
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorConfigBase;

public class ControllerCoordinatorConfig extends EntandoOperatorConfigBase {

    private ControllerCoordinatorConfig() {
    }

    public static boolean garbageCollectSuccessfullyCompletedPods() {
        return lookupProperty(ControllerCoordinatorProperty.ENTANDO_K8S_OPERATOR_GC_CONTROLLER_PODS).map(Boolean::valueOf)
                .orElse(false);
    }

    public static Optional<String> getOperatorServiceAccount() {
        return lookupProperty(ControllerCoordinatorProperty.ENTANDO_K8S_OPERATOR_SERVICEACCOUNT);
    }

    public static boolean isClusterScopedDeployment() {
        if (getOperatorDeploymentType() == OperatorDeploymentType.OLM) {
            return getNamespacesToObserve().isEmpty();
        } else {
            return getNamespacesToObserve().stream().anyMatch("*"::equals);
        }
    }

    public static OperatorDeploymentType getOperatorDeploymentType() {
        return lookupProperty(ControllerCoordinatorProperty.ENTANDO_K8S_OPERATOR_DEPLOYMENT_TYPE)
                .map(OperatorDeploymentType::resolve)
                .orElse(OperatorDeploymentType.HELM);
    }

    public static List<String> getNamespacesToObserve() {
        return lookupProperty(ControllerCoordinatorProperty.ENTANDO_NAMESPACES_TO_OBSERVE).map(s -> s.split(SEPERATOR_PATTERN))
                .map(Arrays::asList)
                .orElse(new ArrayList<>());
    }

    public static Integer getRemovalDelay() {
        return lookupProperty(ControllerCoordinatorProperty.ENTANDO_K8S_CONTROLLER_REMOVAL_DELAY)
                .map(Integer::parseInt)
                .orElse(30);
    }
}
