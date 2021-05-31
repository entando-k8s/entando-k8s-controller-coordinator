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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorSpiConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;

@Tags({@Tag("in-process"), @Tag("unit"), @Tag("pre-deployment")})
class UtilsTest {

    @Test
    void shouldDeleteLivenessFileWithoutFailing() {
        final File file = Paths.get("/tmp/EntandoControllerCoordinator.ready").toFile();
        Liveness.alive();
        assertTrue(file.exists());
        Liveness.dead();
        assertFalse(file.exists());
        Liveness.dead();
        assertFalse(file.exists());
    }

    @Test
    void testDeploymentType() {
        System.clearProperty(ControllerCoordinatorProperty.ENTANDO_K8S_OPERATOR_DEPLOYMENT_TYPE.getJvmSystemProperty());
        assertThat(ControllerCoordinatorConfig.getOperatorDeploymentType()).isEqualTo(OperatorDeploymentType.HELM);
        System.setProperty(ControllerCoordinatorProperty.ENTANDO_K8S_OPERATOR_DEPLOYMENT_TYPE.getJvmSystemProperty(),
                OperatorDeploymentType.OLM.getName());
        assertThat(ControllerCoordinatorConfig.getOperatorDeploymentType()).isEqualTo(OperatorDeploymentType.OLM);
        System.setProperty(ControllerCoordinatorProperty.ENTANDO_K8S_OPERATOR_DEPLOYMENT_TYPE.getJvmSystemProperty(),
                OperatorDeploymentType.HELM.getName());
        assertThat(ControllerCoordinatorConfig.getOperatorDeploymentType()).isEqualTo(OperatorDeploymentType.HELM);
        System.setProperty(ControllerCoordinatorProperty.ENTANDO_K8S_OPERATOR_DEPLOYMENT_TYPE.getJvmSystemProperty(), "invalid");
        assertThat(ControllerCoordinatorConfig.getOperatorDeploymentType()).isEqualTo(OperatorDeploymentType.HELM);
    }

    @Test
    void testIsClusterScope() {
        //Because it will use the current namespace
        System.clearProperty(ControllerCoordinatorProperty.ENTANDO_K8S_OPERATOR_DEPLOYMENT_TYPE.getJvmSystemProperty());
        System.clearProperty(ControllerCoordinatorProperty.ENTANDO_NAMESPACES_TO_OBSERVE.getJvmSystemProperty());
        assertThat(ControllerCoordinatorConfig.isClusterScopedDeployment()).isFalse();
        //OLM contract
        System.setProperty(ControllerCoordinatorProperty.ENTANDO_K8S_OPERATOR_DEPLOYMENT_TYPE.getJvmSystemProperty(),
                OperatorDeploymentType.OLM.getName());
        System.clearProperty(ControllerCoordinatorProperty.ENTANDO_NAMESPACES_TO_OBSERVE.getJvmSystemProperty());
        assertThat(ControllerCoordinatorConfig.isClusterScopedDeployment()).isTrue();
        //Using current namespace again
        System.setProperty(ControllerCoordinatorProperty.ENTANDO_K8S_OPERATOR_DEPLOYMENT_TYPE.getJvmSystemProperty(),
                OperatorDeploymentType.HELM.getName());
        System.clearProperty(ControllerCoordinatorProperty.ENTANDO_NAMESPACES_TO_OBSERVE.getJvmSystemProperty());
        assertThat(ControllerCoordinatorConfig.isClusterScopedDeployment()).isFalse();
        //The Helm deployment expects "*" for cluster scope
        System.setProperty(ControllerCoordinatorProperty.ENTANDO_K8S_OPERATOR_DEPLOYMENT_TYPE.getJvmSystemProperty(),
                OperatorDeploymentType.HELM.getName());
        System.setProperty(ControllerCoordinatorProperty.ENTANDO_NAMESPACES_TO_OBSERVE.getJvmSystemProperty(), "*");
        assertThat(ControllerCoordinatorConfig.isClusterScopedDeployment()).isTrue();
    }

    @Test
    void testIoVulnerability() {
        assertThatIllegalStateException().isThrownBy(() -> CoordinatorUtils.callIoVulnerable(() -> {
            throw new IOException();
        }));
    }

    @AfterEach
    void resetPropertiesTested() {
        System.clearProperty(ControllerCoordinatorProperty.ENTANDO_K8S_OPERATOR_DEPLOYMENT_TYPE.getJvmSystemProperty());
        System.clearProperty(EntandoOperatorSpiConfigProperty.ENTANDO_K8S_OPERATOR_COMPLIANCE_MODE.getJvmSystemProperty());
    }

}
