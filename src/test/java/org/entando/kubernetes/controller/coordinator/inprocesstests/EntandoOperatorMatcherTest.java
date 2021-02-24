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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.ObjectMeta;
import java.util.Collections;
import java.util.HashMap;
import org.entando.kubernetes.controller.coordinator.EntandoOperatorMatcher;
import org.entando.kubernetes.controller.support.common.EntandoOperatorConfigProperty;
import org.entando.kubernetes.model.EntandoCustomResource;
import org.entando.kubernetes.model.EntandoCustomResourceStatus;
import org.entando.kubernetes.model.app.EntandoApp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Tags;
import org.junit.jupiter.api.Test;

@Tags({@Tag("in-process"), @Tag("unit"), @Tag("pre-deployment")})
class EntandoOperatorMatcherTest {

    @BeforeEach
    @AfterEach
    public void removeJvmSystemProperties() {
        System.getProperties().remove(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_API_VERSION_RANGE.getJvmSystemProperty());
        System.getProperties().remove(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_ID.getJvmSystemProperty());
    }

    @Test
    void testOperatorIdVariableMissing() {
        EntandoApp entandoApp = new EntandoApp();
        assertTrue(EntandoOperatorMatcher.matchesThisOperator(entandoApp));
        entandoApp.getMetadata().setAnnotations(new HashMap<>());
        entandoApp.getMetadata().getAnnotations().put(EntandoOperatorMatcher.OPERATOR_ID_ANNOTATION, "myid");
        assertFalse(EntandoOperatorMatcher.matchesThisOperator(entandoApp));
    }

    @Test
    void testOperatorIdAnnotationMissing() {
        EntandoApp entandoApp = new EntandoApp();
        assertTrue(EntandoOperatorMatcher.matchesThisOperator(entandoApp));
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_ID.getJvmSystemProperty(), "myid");
        assertFalse(EntandoOperatorMatcher.matchesThisOperator(entandoApp));
    }

    @Test
    void testOperatorIdActiveAndMatching() {
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_ID.getJvmSystemProperty(), "myid");
        EntandoApp entandoApp = new EntandoApp();
        entandoApp.getMetadata().setAnnotations(Collections.singletonMap(EntandoOperatorMatcher.OPERATOR_ID_ANNOTATION, "myid"));
        assertTrue(EntandoOperatorMatcher.matchesThisOperator(entandoApp));
    }

    @Test
    void testOperatorActiveAndNotMatching() {
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_ID.getJvmSystemProperty(), "myid");
        EntandoApp entandoApp = new EntandoApp();
        entandoApp.getMetadata().setAnnotations(Collections.singletonMap(EntandoOperatorMatcher.OPERATOR_ID_ANNOTATION, "someid"));
        assertFalse(EntandoOperatorMatcher.matchesThisOperator(entandoApp));
    }

    @Test
    void testVersionRangeInactive() {
        EntandoApp entandoApp = new EntandoApp();
        entandoApp.setApiVersion("entando.org/v1");
        assertTrue(EntandoOperatorMatcher.matchesThisOperator(entandoApp));
    }

    @Test
    void testVersionRangeActive() {
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_API_VERSION_RANGE.getJvmSystemProperty(), "1-2");
        EntandoCustomResource entandoApp = new DummyCustomResource();
        entandoApp.getMetadata().setAnnotations(new HashMap<>());
        entandoApp.setApiVersion("entando.org/v1.0.0");
        assertTrue(EntandoOperatorMatcher.matchesThisOperator(entandoApp));
        entandoApp.setApiVersion("entando.org/v2.0.0");
        assertTrue(EntandoOperatorMatcher.matchesThisOperator(entandoApp));
        entandoApp.setApiVersion("entando.org/v2.0.1");
        assertFalse(EntandoOperatorMatcher.matchesThisOperator(entandoApp));
    }

    @Test
    void testNullVersionDefaultsToOne() {
        System.setProperty(EntandoOperatorConfigProperty.ENTANDO_K8S_OPERATOR_API_VERSION_RANGE.getJvmSystemProperty(), "1");
        EntandoApp entandoApp = new EntandoApp();
        entandoApp.setApiVersion(null);
        assertTrue(EntandoOperatorMatcher.matchesThisOperator(entandoApp));
    }

    private static class DummyCustomResource implements EntandoCustomResource {

        private ObjectMeta metadata=new ObjectMeta();

        @Override
        public EntandoCustomResourceStatus getStatus() {
            return null;
        }

        @Override
        public void setStatus(EntandoCustomResourceStatus entandoCustomResourceStatus) {

        }

        @Override
        public String getDefinitionName() {
            return null;
        }

        private String apiVersion;

        @Override
        public ObjectMeta getMetadata() {
            return this.metadata;
        }

        @Override
        public void setMetadata(ObjectMeta metadata) {
            this.metadata=metadata;
        }

        @Override
        public String getApiVersion() {
            return apiVersion;
        }

        @Override
        public void setApiVersion(String version) {
            this.apiVersion=version;
        }
    }
}
