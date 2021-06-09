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
import static org.entando.kubernetes.controller.spi.common.ExceptionUtils.interruptionSafe;

import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher.Action;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.entando.kubernetes.controller.spi.client.SerializedEntandoResource;
import org.entando.kubernetes.controller.spi.common.EntandoOperatorConfigBase;
import org.entando.kubernetes.model.common.EntandoDeploymentPhase;

public class EntandoResourceObserver implements SerializedResourceWatcher {

    private static final Logger LOGGER = Logger.getLogger(EntandoResourceObserver.class.getName());

    private final Map<String, Deque<String>> cache = new ConcurrentHashMap<>();
    private final Map<String, SerializedEntandoResource> resourcesBeingUpgraded = new ConcurrentHashMap<>();
    private final BiConsumer<Action, SerializedEntandoResource> callback;
    private final SimpleEntandoOperations operations;
    //TODO make the pool size configurable. Need to think that through though. We would require different settings for cluster vs
    // namespace scoped deployments
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(5);
    private final CrdNameMapSync crdNameMapSync;
    private final Long generation;
    private final List<Watch> watchers = new ArrayList<>();

    public EntandoResourceObserver(SimpleEntandoOperations operations,
            BiConsumer<Action, SerializedEntandoResource> callback,
            CrdNameMapSync crdNameMapSync,
            Long generation) {
        this.callback = callback;
        this.operations = operations;
        this.crdNameMapSync = crdNameMapSync;
        this.generation = generation;
        processOperationInScope(operations, simpleEntandoOperations -> simpleEntandoOperations.list()
                .forEach(entandoCustomResource -> eventReceived(Action.MODIFIED, entandoCustomResource)));
        processOperationInScope(operations, simpleEntandoOperations -> watchers.add(simpleEntandoOperations.watch(this)));
        LOGGER.log(Level.INFO, () -> format("Listening to CRD '%s'", operations.getDefinitionContext().getName()));
    }

    private static void processOperationInScope(SimpleEntandoOperations operations, Consumer<SimpleEntandoOperations> consumer) {
        if (ControllerCoordinatorConfig.isClusterScopedDeployment()) {
            consumer.accept(operations.inAnyNamespace());
        } else {
            List<String> namespaces = ControllerCoordinatorConfig.getNamespacesToObserve();
            if (namespaces.isEmpty()) {
                namespaces.add(operations.getControllerNamespace());
            }
            for (String namespace : namespaces) {
                consumer.accept(operations.inNamespace(namespace));
            }
        }
    }

    private boolean requiresUpgrade(SerializedEntandoResource resource) {
        if (!isBeingUpgraded(resource) && wasProcessedByVersionBeingReplaced(resource)) {
            resourcesBeingUpgraded.put(resource.getMetadata().getUid(), resource);
            logResource(Level.FINE, "%s %s/%s needs to be processed as part of the upgrade to the version " + EntandoOperatorConfigBase
                    .lookupProperty(ControllerCoordinatorProperty.ENTANDO_K8S_OPERATOR_VERSION).orElse("latest"), resource);
            return true;
        }
        return false;
    }

    private boolean wasProcessedByVersionBeingReplaced(SerializedEntandoResource resource) {
        return EntandoOperatorConfigBase
                .lookupProperty(ControllerCoordinatorProperty.ENTANDO_K8S_OPERATOR_VERSION_TO_REPLACE)
                .flatMap(versionToReplace ->
                        CoordinatorUtils.resolveAnnotation(resource, AnnotationNames.PROCESSED_BY_OPERATOR_VERSION)
                                .map(versionToReplace::equals))
                .orElse(false);
    }

    private boolean isBeingUpgraded(SerializedEntandoResource resource) {
        return resourcesBeingUpgraded.containsKey(resource.getMetadata().getUid());
    }

    @Override
    public void eventReceived(Action action, SerializedEntandoResource resource) {
        try {
            if (performCriteriaProcessing(resource)) {
                performCallback(action, resource);
            } else if (resource.getStatus().getPhase() == EntandoDeploymentPhase.SUCCESSFUL) {
                logResource(Level.INFO, "%s %s/%s was processed successfully", resource);
                markAsUpgraded(resource);
                if (needsToRemoveSuccessfullyCompletedPods(resource)) {
                    removeSuccessfullyCompletedPods(resource);
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, e, () -> format("Could not process the %s %s/%s", resource.getKind(),
                    resource.getMetadata().getNamespace(), resource.getMetadata().getName()));
        }
    }

    private void markAsUpgraded(SerializedEntandoResource resource) {
        final Optional<String> currentOperatorVersion = EntandoOperatorConfigBase
                .lookupProperty(ControllerCoordinatorProperty.ENTANDO_K8S_OPERATOR_VERSION);
        currentOperatorVersion.ifPresent(s -> {
                    final Optional<String> processedByVersion = CoordinatorUtils
                            .resolveAnnotation(resource, AnnotationNames.PROCESSED_BY_OPERATOR_VERSION);
                    if (!processedByVersion.map(s::equals).orElse(false)) {
                        operations.putAnnotation(resource, AnnotationNames.PROCESSED_BY_OPERATOR_VERSION.getName(), s);
                    }
                }
        );
        resourcesBeingUpgraded.remove(resource.getMetadata().getUid());
    }

    private void removeSuccessfullyCompletedPods(SerializedEntandoResource resource) {
        scheduler.execute(() -> operations.removeSuccessfullyCompletedPods(resource));
    }

    private boolean needsToRemoveSuccessfullyCompletedPods(SerializedEntandoResource resource) {
        return ControllerCoordinatorConfig.garbageCollectSuccessfullyCompletedPods()
                && Optional.ofNullable(resource.getMetadata().getGeneration())
                .map(aLong -> aLong.equals(resource.getMetadata().getGeneration()))
                .orElse(false)
                && resource.getStatus().getPhase() == EntandoDeploymentPhase.SUCCESSFUL;
    }

    private boolean performCriteriaProcessing(SerializedEntandoResource resource) {
        return requiresUpgrade(resource)
                || (hasNewResourceVersion(resource)
                && isNotOwnedByResourceOfInterest(resource)
                && EntandoOperatorMatcher.matchesThisOperator(resource)
                && processGenerationIncrement(resource));
    }

    private boolean isNotOwnedByResourceOfInterest(SerializedEntandoResource resource) {
        final boolean topLevel = resource.getMetadata().getOwnerReferences().stream()
                .noneMatch(crdNameMapSync::isOfInterest);
        if (topLevel) {
            logResource(Level.FINE, "%s %s/%s is a top level resource", resource);
        } else {
            logResource(Level.FINE, "%s %s/%s is ignored because it is not a top level resource", resource);
        }
        return topLevel;
    }

    protected void performCallback(Action action, SerializedEntandoResource resource) {
        logResource(Level.INFO, "Received " + action.name() + " for the %s %s/%s", resource);
        if (action == Action.ADDED || action == Action.MODIFIED) {
            scheduler.execute(() -> callback.accept(action, resource));
        } else if (action == Action.DELETED) {
            cache.remove(resource.getMetadata().getUid());
        } else {
            logResource(Level.WARNING, "EntandoResourceObserver could not process the action " + action.name() + " on the %s %s/%s",
                    resource);
        }
    }

    private void logResource(Level info, String format, SerializedEntandoResource resource) {
        LOGGER.log(info,
                () -> format(format, resource.getKind(), resource.getMetadata().getNamespace(), resource.getMetadata().getName()));
    }

    private boolean processGenerationIncrement(SerializedEntandoResource newResource) {
        OperatorProcessingInstruction instruction = CoordinatorUtils.resolveProcessingInstruction(newResource);
        if (instruction == OperatorProcessingInstruction.FORCE) {
            //Remove to avoid recursive updates
            final SerializedEntandoResource latestResource = operations
                    .removeAnnotation(newResource, AnnotationNames.PROCESSING_INSTRUCTION.getName());
            markResourceVersionProcessed(latestResource);
            logResource(Level.FINE, "Processing of %s %s/%s has been forced using entando.org/processing-instruction.", latestResource);
            return true;
        } else if (instruction == OperatorProcessingInstruction.DEFER || instruction == OperatorProcessingInstruction.IGNORE) {
            logResource(Level.FINE, "Processing of %s %s/%s has been deferred or ignored using entando.org/processing-instruction.",
                    newResource);
            return false;
        } else {
            final boolean needsObservation = newResource.getStatus().getObservedGeneration() == null
                    || newResource.getMetadata().getGeneration() == null
                    || newResource.getStatus().getObservedGeneration() < newResource.getMetadata().getGeneration();
            if (needsObservation) {
                logResource(Level.FINE, "%s %s/%s is processed after a metadata.generation increment.", newResource);
            } else {
                logResource(Level.FINE,
                        "%s %s/%s was ignored because its metadata.generation is still the same as the status.observedGeneration.",
                        newResource);
            }
            return needsObservation;
        }
    }

    private boolean hasNewResourceVersion(SerializedEntandoResource newResource) {
        Deque<String> queue = cache.computeIfAbsent(newResource.getMetadata().getUid(), key -> new ConcurrentLinkedDeque<>());
        if (queue.contains(newResource.getMetadata().getResourceVersion())) {
            //TODO observe logs to see if this actually still happens
            logResource(Level.WARNING, "Duplicate event for %s %s/%s. ResourceVersion=" + newResource.getMetadata().getResourceVersion(),
                    newResource);
            return false;
        }
        markResourceVersionProcessed(newResource);
        logResource(Level.FINE, "%s %s/%s has a new resource version: " + newResource.getMetadata().getResourceVersion(), newResource);
        return true;
    }

    private void markResourceVersionProcessed(SerializedEntandoResource newResource) {
        Deque<String> queue = cache.computeIfAbsent(newResource.getMetadata().getUid(), key -> new ConcurrentLinkedDeque<>());
        queue.offer(newResource.getMetadata().getResourceVersion());
        while (queue.size() > 10) {
            queue.poll();
        }
    }

    public void shutDownAndWait(int i, TimeUnit timeUnit) {
        interruptionSafe(() -> {
            watchers.forEach(Watch::close);
            watchers.clear();
            scheduler.shutdown();
            if (!scheduler.awaitTermination(i, timeUnit)) {
                LOGGER.log(Level.WARNING, () -> "Could not shut EntandoResourceObserver down.");
            }
            return null;
        });
    }

    public Long getCrdGeneration() {
        return generation;
    }
}
