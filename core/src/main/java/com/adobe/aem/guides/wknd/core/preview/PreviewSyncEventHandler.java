package com.adobe.aem.guides.wknd.core.preview;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.api.resource.ResourceUtil;
import org.apache.sling.caconfig.ConfigurationBuilder;
import org.apache.sling.discovery.DiscoveryService;
import org.apache.sling.discovery.InstanceDescription;
import org.apache.sling.discovery.TopologyView;
import org.apache.sling.distribution.event.DistributionEventProperties;
import org.apache.sling.distribution.event.DistributionEventTopics;
import org.apache.sling.event.jobs.JobManager;
import org.apache.sling.serviceusermapping.ServiceUserMapped;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Converts Sling Content Distribution (SCD) package-distributed events for
 * opted-in
 * content into durable Sling Jobs that distribute the same content to the
 * Preview tier.
 *
 * <p>
 * Uses the AEMaaCS-native
 * {@link DistributionEventTopics#AGENT_PACKAGE_DISTRIBUTED}
 * event surface instead of the legacy {@code com/day/cq/replication} topic.
 * </p>
 *
 * <p>
 * <strong>AEMaaCS Author topology:</strong> The Author tier runs as a cluster
 * of multiple pods (minimum two for business continuity). OSGi events are fired
 * on every pod that receives the SCD distribution event, which means every
 * Author
 * pod could independently enqueue the same Sling Job for the same path. To
 * prevent
 * duplicate Preview distributions, this handler uses a
 * {@link DiscoveryService} leader check so that only the elected leader pod
 * enqueues jobs. Events received during leader election (topology not current)
 * are
 * intentionally dropped — the design accepts at-most-once enqueueing semantics.
 * If strict at-least-once delivery to Preview is required, a periodic
 * reconciliation
 * workflow or explicit Tree Activation step must supplement this handler.
 * </p>
 *
 * <p>
 * Loop prevention uses three layers:
 * </p>
 * <ol>
 * <li><strong>Leader guard</strong> — only the Sling Discovery leader pod
 * enqueues jobs; all other Author pods drop the event immediately.</li>
 * <li><strong>Agent-name guard</strong> — only events whose
 * {@code distribution.component.name} equals {@code publish} are processed;
 * events originating from the {@code preview} agent are discarded.</li>
 * <li><strong>ReplicationStatus circuit breaker</strong> in
 * {@link PreviewSyncJobConsumer} — compares Publish vs Preview timestamps
 * before executing a distribution, guarding against job-level duplicates.</li>
 * </ol>
 *
 * <p>
 * The component will not activate until the {@code ServiceUserMapped}
 * service for sub-service {@code preview-sync} is registered, ensuring the
 * JCR system user exists before any event is processed.
 * </p>
 *
 * <p>
 * The component requires an OSGi configuration PID to activate. An empty
 * {@code .cfg.json} placed only in {@code config.author} ensures the handler
 * stays dormant on Publish and Preview tiers.
 * </p>
 */
@Component(service = EventHandler.class, configurationPolicy = ConfigurationPolicy.REQUIRE, property = {
        EventConstants.EVENT_TOPIC + "=" + DistributionEventTopics.AGENT_PACKAGE_DISTRIBUTED
})
public final class PreviewSyncEventHandler implements EventHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(PreviewSyncEventHandler.class);

    private static final String DIST_COMPONENT_NAME = DistributionEventProperties.DISTRIBUTION_COMPONENT_NAME;
    private static final String DIST_PATHS = DistributionEventProperties.DISTRIBUTION_PATHS;
    private static final String DIST_TYPE = DistributionEventProperties.DISTRIBUTION_TYPE;

    /** SCD ADD action maps to content activation. */
    private static final String DIST_ADD = "ADD";

    /** SCD DELETE action maps to content deactivation. */
    private static final String DIST_DELETE = "DELETE";

    @Reference
    private ResourceResolverFactory resourceResolverFactory;

    @Reference
    private JobManager jobManager;

    /**
     * Used for leader-only enqueueing: only the elected leader Author pod processes
     * distribution events. The Author tier is a multi-pod cluster; without this
     * guard
     * every pod would independently enqueue the same path, causing duplicate
     * Preview
     * distributions for every publish action.
     */
    @Reference
    private DiscoveryService discoveryService;

    /**
     * Defers component activation until the RepoInit-provisioned
     * {@code preview-sync-service} JCR system user is bound by
     * {@code ServiceUserMapperImpl.amended}. Prevents
     * {@code LoginException} during the first deployment window.
     */
    @Reference(target = "(subServiceName=preview-sync)", cardinality = ReferenceCardinality.MANDATORY, policy = ReferencePolicy.STATIC)
    @SuppressWarnings("unused")
    private ServiceUserMapped serviceUserMapped;

    @Override
    public void handleEvent(Event event) {
        // Leader-only: drop events on non-leader pods to avoid duplicate job enqueueing
        // across the multi-pod Author cluster. Events during topology change
        // (isCurrent=false)
        // are intentionally dropped; the design accepts at-most-once enqueueing
        // semantics.
        if (!isLocalLeader()) {
            return;
        }

        // Only process events from the Publish SCD agent; ignore preview and others.
        String agentName = (String) event.getProperty(DIST_COMPONENT_NAME);
        if (!PreviewSyncConstants.PUBLISH_AGENT_ID.equals(agentName)) {
            return;
        }

        String distType = (String) event.getProperty(DIST_TYPE);
        if (!DIST_ADD.equals(distType) && !DIST_DELETE.equals(distType)) {
            return;
        }

        Object rawPaths = event.getProperty(DIST_PATHS);
        String[] paths = rawPaths instanceof String[] ? (String[]) rawPaths : null;
        if (paths == null || paths.length == 0) {
            return;
        }

        try (ResourceResolver resolver = resourceResolverFactory.getServiceResourceResolver(
                Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, PreviewSyncConstants.SERVICE_NAME))) {
            for (String path : paths) {
                if (isEnabled(resolver, path, distType)) {
                    enqueue(path, distType);
                }
            }
        } catch (LoginException e) {
            LOGGER.error("Unable to obtain the preview-sync service resolver", e);
        }
    }

    /**
     * Checks whether the path is opted-in for preview synchronization via CAConfig.
     * <p>
     * For DELETE (DEACTIVATE) actions where the resource may already be removed,
     * the method walks up the ancestor hierarchy (bounded by
     * {@link PreviewSyncConstants#MAX_ANCESTOR_DEPTH}) to find the nearest existing
     * resource and resolves CAConfig from there via normal CAConfig inheritance.
     * </p>
     */
    private boolean isEnabled(ResourceResolver resolver, String path, String distType) {
        Resource resource = resolver.getResource(path);
        if (resource != null) {
            return isEnabledForResource(resource);
        }
        // For DELETE of already-removed resources, walk ancestors for inherited
        // CAConfig.
        if (DIST_DELETE.equals(distType)) {
            Resource ancestor = findNearestAncestor(resolver, path);
            return ancestor != null && isEnabledForResource(ancestor);
        }
        return false;
    }

    private boolean isEnabledForResource(Resource resource) {
        ConfigurationBuilder builder = resource.adaptTo(ConfigurationBuilder.class);
        PreviewSyncConfig config = builder == null ? null : builder.as(PreviewSyncConfig.class);
        return config != null && config.enableAutoSync();
    }

    /**
     * Returns {@code true} only when the local Author pod is the Sling Discovery
     * leader and the topology view is current (stable). Returns {@code false}
     * during
     * leader election or rolling restarts — events in that window are dropped.
     *
     * <p>
     * This is intentional: Sling Jobs provide at-least-once processing once
     * enqueued, but this handler uses at-most-once enqueueing semantics to avoid
     * duplicate Preview distributions across the multi-pod Author cluster. If
     * strict
     * at-least-once delivery to Preview is required, supplement with a
     * reconciliation
     * workflow.
     * </p>
     */
    private boolean isLocalLeader() {
        TopologyView topology = discoveryService.getTopology();
        if (topology == null || !topology.isCurrent()) {
            return false;
        }
        InstanceDescription local = topology.getLocalInstance();
        return local != null && local.isLeader();
    }

    /**
     * Walks ancestor paths to find the nearest existing resource, bounded by
     * {@link PreviewSyncConstants#MAX_ANCESTOR_DEPTH} to prevent unbounded
     * traversal.
     */
    private Resource findNearestAncestor(ResourceResolver resolver, String path) {
        String currentPath = ResourceUtil.getParent(path);
        int depth = 0;
        while (currentPath != null && !currentPath.isEmpty()
                && depth++ < PreviewSyncConstants.MAX_ANCESTOR_DEPTH) {
            Resource res = resolver.getResource(currentPath);
            if (res != null) {
                return res;
            }
            currentPath = ResourceUtil.getParent(currentPath);
        }
        return null;
    }

    private void enqueue(String path, String distType) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(PreviewSyncConstants.JOB_PATH, path);
        properties.put(PreviewSyncConstants.JOB_ACTION, distType);
        if (jobManager.addJob(PreviewSyncConstants.JOB_TOPIC, properties) == null) {
            LOGGER.warn("Preview synchronization job could not be queued for {}", path);
        }
    }
}
