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
import org.apache.sling.event.jobs.JobManager;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.event.Event;
import org.osgi.service.event.EventConstants;
import org.osgi.service.event.EventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.day.cq.replication.ReplicationAction;
import com.day.cq.replication.ReplicationActionType;

/**
 * Converts Publish replication events for opted-in content into durable Sling Jobs
 * that replicate the same content to the Preview tier.
 *
 * <p>Loop prevention uses two layers:</p>
 * <ol>
 *   <li><strong>Best-effort userId guard</strong> — events initiated by the
 *       {@code preview-sync-service} principal are dropped immediately.</li>
 *   <li><strong>ReplicationStatus circuit breaker</strong> in
 *       {@link PreviewSyncJobConsumer} — compares Publish vs Preview timestamps
 *       before executing.</li>
 * </ol>
 *
 * <p>The component requires an OSGi configuration PID to activate. An empty
 * {@code .cfg.json} placed only in {@code config.author} ensures the handler
 * stays dormant on Publish and Preview tiers.</p>
 */
@Component(
        service = EventHandler.class,
        configurationPolicy = ConfigurationPolicy.REQUIRE,
        property = {
                EventConstants.EVENT_TOPIC + "=" + ReplicationAction.EVENT_TOPIC
        }
)
public final class PreviewSyncEventHandler implements EventHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(PreviewSyncEventHandler.class);

    @Reference
    private ResourceResolverFactory resourceResolverFactory;

    @Reference
    private JobManager jobManager;

    @Override
    public void handleEvent(Event event) {
        ReplicationAction action = ReplicationAction.fromEvent(event);
        if (action == null || !isSupportedAction(action)) {
            return;
        }

        // Loop prevention Layer 1 (best-effort): skip events our own service user triggered.
        // getUserId() is not guaranteed to be populated in all AEMaaCS SCD scenarios,
        // so the ReplicationStatus check in PreviewSyncJobConsumer is the primary safety net.
        if (PreviewSyncConstants.PREVIEW_SYNC_PRINCIPAL.equals(action.getUserId())) {
            LOGGER.debug("Ignoring replication event initiated by own service user {}", action.getUserId());
            return;
        }

        String[] paths = action.getPaths();
        if (paths == null || paths.length == 0) {
            return;
        }

        try (ResourceResolver resolver = resourceResolverFactory.getServiceResourceResolver(
                Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, PreviewSyncConstants.SERVICE_NAME))) {
            for (String path : paths) {
                if (isEnabled(resolver, path, action.getType())) {
                    enqueue(path, action.getType());
                }
            }
        } catch (LoginException e) {
            LOGGER.error("Unable to obtain the preview-sync service resolver", e);
        }
    }

    private boolean isSupportedAction(ReplicationAction action) {
        return action.getType() == ReplicationActionType.ACTIVATE
                || action.getType() == ReplicationActionType.DEACTIVATE;
    }

    /**
     * Checks whether the path is opted-in for preview synchronization via CAConfig.
     * For DEACTIVATE actions where the resource may already be deleted, the method
     * walks up the ancestor hierarchy to find the nearest existing resource and
     * resolves CAConfig from there (since child nodes inherit parent configuration).
     */
    private boolean isEnabled(ResourceResolver resolver, String path, ReplicationActionType actionType) {
        Resource resource = resolver.getResource(path);
        if (resource != null) {
            return isEnabledForResource(resource);
        }
        // For DEACTIVATE of deleted resources, walk ancestors to resolve inherited CAConfig
        if (actionType == ReplicationActionType.DEACTIVATE) {
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

    private Resource findNearestAncestor(ResourceResolver resolver, String path) {
        String currentPath = ResourceUtil.getParent(path);
        while (currentPath != null && !currentPath.isEmpty()) {
            Resource res = resolver.getResource(currentPath);
            if (res != null) {
                return res;
            }
            currentPath = ResourceUtil.getParent(currentPath);
        }
        return null;
    }

    private void enqueue(String path, ReplicationActionType actionType) {
        Map<String, Object> properties = new HashMap<>();
        properties.put(PreviewSyncConstants.JOB_PATH, path);
        properties.put(PreviewSyncConstants.JOB_ACTION, actionType.name());
        if (jobManager.addJob(PreviewSyncConstants.JOB_TOPIC, properties) == null) {
            LOGGER.warn("Preview synchronization job could not be queued for {}", path);
        }
    }
}
