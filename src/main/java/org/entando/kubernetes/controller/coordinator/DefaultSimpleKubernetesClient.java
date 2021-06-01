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

import static java.lang.String.format;
import static java.util.Optional.ofNullable;
import static org.entando.kubernetes.controller.coordinator.CoordinatorUtils.callIoVulnerable;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.Event;
import io.fabric8.kubernetes.api.model.EventBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodList;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.apiextensions.v1beta1.CustomResourceDefinition;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.dsl.FilterWatchListDeletable;
import io.fabric8.kubernetes.client.dsl.base.CustomResourceDefinitionContext;
import io.fabric8.kubernetes.client.dsl.internal.RawCustomResourceOperationsImpl;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import org.entando.kubernetes.controller.spi.client.SerializedEntandoResource;
import org.entando.kubernetes.controller.spi.common.LabelNames;
import org.entando.kubernetes.controller.spi.common.NameUtils;
import org.entando.kubernetes.controller.spi.common.ResourceUtils;
import org.entando.kubernetes.model.common.EntandoCustomResource;
import org.entando.kubernetes.model.common.EntandoDeploymentPhase;

public class DefaultSimpleKubernetesClient implements SimpleKubernetesClient {

    //There will only be once instance per thread for the duration of the Pod's life
    @SuppressWarnings("java:S5164")
    private static final ThreadLocal<DateTimeFormatter> dateTimeFormatter = ThreadLocal
            .withInitial(() -> DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss'Z'"));

    Map<String, CustomResourceDefinitionContext> definitionContextMap = new ConcurrentHashMap<>();
    private final KubernetesClient client;

    public DefaultSimpleKubernetesClient(KubernetesClient client) {

        this.client = client;
    }

    @Override
    public SerializedEntandoResource updatePhase(SerializedEntandoResource customResource, EntandoDeploymentPhase phase) {
        return performStatusUpdate(customResource,
                t -> t.getStatus().updateDeploymentPhase(phase, t.getMetadata().getGeneration()),
                e -> e.withType("Normal")
                        .withReason("PhaseUpdated")
                        .withMessage(format("The deployment of %s  %s/%s was updated  to %s",
                                customResource.getKind(),
                                customResource.getMetadata().getNamespace(),
                                customResource.getMetadata().getName(),
                                phase.name()))
                        .withAction("PHASE_CHANGE")
        );
    }

    private SerializedEntandoResource performStatusUpdate(SerializedEntandoResource customResource,
            Consumer<SerializedEntandoResource> consumer, UnaryOperator<EventBuilder> eventPopulator) {
        final EventBuilder doneableEvent = new EventBuilder()
                .withNewMetadata()
                .withNamespace(customResource.getMetadata().getNamespace())
                .withName(customResource.getMetadata().getName() + "-" + NameUtils.randomNumeric(8))
                .withOwnerReferences(ResourceUtils.buildOwnerReference(customResource))
                .withLabels(ResourceUtils.labelsFromResource(customResource))
                .endMetadata()
                .withCount(1)
                .withFirstTimestamp(dateTimeFormatter.get().format(LocalDateTime.now()))
                .withLastTimestamp(dateTimeFormatter.get().format(LocalDateTime.now()))
                .withNewSource(NameUtils.controllerNameOf(customResource), null)
                .withNewInvolvedObject()
                .withKind(customResource.getKind())
                .withNamespace(customResource.getMetadata().getNamespace())
                .withName(customResource.getMetadata().getName())
                .withUid(customResource.getMetadata().getUid())
                .withResourceVersion(customResource.getMetadata().getResourceVersion())
                .withApiVersion(customResource.getApiVersion())
                .withFieldPath("status")
                .endInvolvedObject();
        client.v1().events().inNamespace(customResource.getMetadata().getNamespace()).create(eventPopulator.apply(doneableEvent).build());
        return callIoVulnerable(() -> {
            SerializedEntandoResource ser = customResource;
            CustomResourceDefinitionContext definition = Optional.ofNullable(ser.getDefinition()).orElse(
                    resolveDefinitionContext(ser));
            ser.setDefinition(definition);
            RawCustomResourceOperationsImpl resource = client.customResource(definition)
                    .inNamespace(customResource.getMetadata().getNamespace())
                    .withName(customResource.getMetadata().getName());
            final ObjectMapper objectMapper = new ObjectMapper();
            ser = objectMapper.readValue(objectMapper.writeValueAsString(resource.get()), SerializedEntandoResource.class);
            ser.setDefinition(definition);
            consumer.accept(ser);
            final Map<String, Object> map = resource.updateStatus(objectMapper.writeValueAsString(ser));
            return objectMapper.readValue(objectMapper.writeValueAsString(map), SerializedEntandoResource.class);
        });
    }

    private CustomResourceDefinitionContext resolveDefinitionContext(SerializedEntandoResource resource) {
        return definitionContextMap.computeIfAbsent(CoordinatorUtils.keyOf(resource), key ->
                CustomResourceDefinitionContext.fromCrd(client.apiextensions().v1beta1().customResourceDefinitions()
                        .withName(ofNullable(
                                ofNullable(findOrCreateControllerConfigMap(CoordinatorUtils.ENTANDO_CRD_NAMES_CONFIGMAP_NAME).getData())
                                        .orElse(Collections.emptyMap()).get(key))
                                .orElseThrow(IllegalStateException::new)).get()));
    }

    @Override
    public String getControllerNamespace() {
        return client.getNamespace();
    }

    @Override
    public Secret loadControllerSecret(String s) {
        return client.secrets().inNamespace(getControllerNamespace()).withName(s).get();
    }

    @Override
    public Secret overwriteControllerSecret(Secret secret) {
        return client.secrets().inNamespace(getControllerNamespace()).createOrReplace(secret);
    }

    @Override
    public Pod startPod(Pod pod) {
        return client.pods().inNamespace(getControllerNamespace()).create(pod);
    }

    @Override
    public void removePodsAndWait(String namespace, Map<String, String> labels) {
        FilterWatchListDeletable<Pod, PodList> podResource = client.pods().inNamespace(namespace).withLabels(labels);
        podResource.delete();
        try {
            podResource.waitUntilCondition(pod -> podResource.list().getItems().isEmpty(),
                    ControllerCoordinatorConfig.getPodShutdownTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Override
    public ConfigMap findOrCreateControllerConfigMap(String name) {
        return Objects.requireNonNullElseGet(this.client.configMaps().inNamespace(getControllerNamespace()).withName(name).get(), () ->
                this.client.configMaps().inNamespace(getControllerNamespace())
                        .create(new ConfigMapBuilder()
                                .withNewMetadata()
                                .withNamespace(getControllerNamespace())
                                .withName(name)
                                .endMetadata()
                                .build()));
    }

    @Override
    public ConfigMap patchControllerConfigMap(ConfigMap configMap) {
        return client.configMaps().inNamespace(getControllerNamespace()).withName(configMap.getMetadata().getName()).patch(configMap);
    }

    @Override
    public void watchControllerConfigMap(String name, Watcher<ConfigMap> configMapWatcher) {
        this.client.configMaps().inNamespace(getControllerNamespace()).withName(name).watch(configMapWatcher);
    }

    @Override
    public Watch watchCustomResourceDefinitions(Watcher<CustomResourceDefinition> customResourceDefinitionWatcher) {
        return this.client.apiextensions().v1beta1().customResourceDefinitions().withLabel(LabelNames.CRD_OF_INTEREST.getName())
                .watch(customResourceDefinitionWatcher);
    }

    @Override
    public Collection<CustomResourceDefinition> loadCustomResourceDefinitionsOfInterest() {
        return client.apiextensions().v1beta1().customResourceDefinitions().withLabel(LabelNames.CRD_OF_INTEREST.getName())
                .list()
                .getItems();
    }

    @Override
    public SimpleEntandoOperations getOperations(CustomResourceDefinitionContext context) {
        return new DefaultSimpleEntandoOperations(client, context, client.customResource(context), true);
    }

    public List<Event> listEventsFor(EntandoCustomResource resource) {
        return client.v1().events().inAnyNamespace().withLabels(ResourceUtils.labelsFromResource(resource)).list().getItems();
    }
}
