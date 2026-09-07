package com.adobe.aem.guides.wknd.core.preview;

import java.util.Calendar;
import java.util.Collections;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.distribution.DistributionRequest;
import org.apache.sling.distribution.DistributionRequestType;
import org.apache.sling.distribution.DistributionResponse;
import org.apache.sling.distribution.Distributor;
import org.apache.sling.distribution.SimpleDistributionRequest;
import org.apache.sling.event.jobs.Job;
import org.apache.sling.event.jobs.consumer.JobConsumer;
import org.apache.sling.serviceusermapping.ServiceUserMapped;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.day.cq.replication.ReplicationStatus;

/**
 * Executes a bounded, Preview-only content distribution using the
 * least-privileged
 * service user and the AEMaaCS-native Sling Content Distribution (SCD) API.
 *
 * <p>
 * Replaces the legacy {@code com.day.cq.replication.Replicator} with
 * {@link Distributor}, eliminating the need for a JCR {@code Session} and
 * aligning
 * with the forward-compatible SCD layer in AEM as a Cloud Service.
 * </p>
 *
 * <p>
 * Loop prevention Layer 2: before executing an ADD (ACTIVATE), this consumer
 * compares the Publish and Preview {@link ReplicationStatus} timestamps. If
 * Preview
 * is already at or ahead of Publish, the job is silently completed without
 * re-distributing.
 * </p>
 *
 * <p>
 * The component requires an OSGi configuration PID to activate. An empty
 * {@code .cfg.json} placed only in {@code config.author} ensures the consumer
 * stays dormant on Publish and Preview tiers.
 * </p>
 */
@Component(service = JobConsumer.class, configurationPolicy = ConfigurationPolicy.REQUIRE, property = JobConsumer.PROPERTY_TOPICS
        + "=" + PreviewSyncConstants.JOB_TOPIC)
public final class PreviewSyncJobConsumer implements JobConsumer {

    private static final Logger LOGGER = LoggerFactory.getLogger(PreviewSyncJobConsumer.class);

    /** SCD ADD action — equivalent to content activation. */
    private static final String DIST_ADD = "ADD";

    /** SCD DELETE action — equivalent to content deactivation. */
    private static final String DIST_DELETE = "DELETE";

    @Reference
    private ResourceResolverFactory resourceResolverFactory;

    /**
     * Sling Content Distribution {@link Distributor} — the AEMaaCS-native API
     * for programmatic content distribution. Replaces the legacy
     * {@code Replicator}.
     */
    @Reference
    private Distributor distributor;

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
    public JobResult process(Job job) {
        String path = job.getProperty(PreviewSyncConstants.JOB_PATH, String.class);
        String distType = job.getProperty(PreviewSyncConstants.JOB_ACTION, String.class);
        if (path == null || distType == null) {
            LOGGER.error("Invalid Preview synchronization job {}", job.getId());
            return JobResult.CANCEL;
        }

        DistributionRequestType requestType = toRequestType(distType);
        if (requestType == null) {
            LOGGER.error("Unsupported distribution action '{}' for path {}", distType, path);
            return JobResult.CANCEL;
        }

        try (ResourceResolver resolver = resourceResolverFactory.getServiceResourceResolver(
                Collections.singletonMap(ResourceResolverFactory.SUBSERVICE, PreviewSyncConstants.SERVICE_NAME))) {

            // Loop prevention Layer 2: for ADD, compare Publish vs Preview timestamps.
            if (requestType == DistributionRequestType.ADD && isPreviewAlreadyCurrent(resolver, path)) {
                LOGGER.debug("Preview status for {} is already up-to-date; skipping distribution", path);
                return JobResult.OK;
            }

            DistributionRequest request = new SimpleDistributionRequest(requestType, path);
            DistributionResponse response = distributor.distribute(
                    PreviewSyncConstants.PREVIEW_AGENT_ID, resolver, request);
            if (response == null || !response.isSuccessful()) {
                String message = response == null ? "no distribution response" : response.getMessage();
                LOGGER.warn("Preview distribution was not accepted for {}: {}", path, message);
                return JobResult.FAILED;
            }
            LOGGER.info("Distributed {} to Preview agent ({})", path, distType);
            return JobResult.OK;

        } catch (org.apache.sling.api.resource.LoginException e) {
            LOGGER.warn("Preview distribution login failed for {}; the Sling Job will retry", path, e);
            return JobResult.FAILED;
        } catch (Exception e) {
            LOGGER.warn("Preview distribution failed for {}; the Sling Job will retry", path, e);
            return JobResult.FAILED;
        }
    }

    /**
     * Maps the SCD event distribution type string to a
     * {@link DistributionRequestType}.
     *
     * @return {@code ADD} for activate, {@code DELETE} for deactivate, {@code null}
     *         if unsupported
     */
    private DistributionRequestType toRequestType(String distType) {
        if (DIST_ADD.equals(distType)) {
            return DistributionRequestType.ADD;
        } else if (DIST_DELETE.equals(distType)) {
            return DistributionRequestType.DELETE;
        }
        return null;
    }

    /**
     * Checks whether the Preview tier's replication timestamp is already at or
     * ahead
     * of the Publish tier's timestamp for the given path. If so, distributing again
     * would be redundant and could cause an infinite loop.
     *
     * @return {@code true} if Preview is already current and distribution should be
     *         skipped
     */
    private boolean isPreviewAlreadyCurrent(ResourceResolver resolver, String path) {
        Resource resource = resolver.getResource(path);
        if (resource == null) {
            return false;
        }

        ReplicationStatus status = resource.adaptTo(ReplicationStatus.class);
        if (status == null) {
            return false;
        }

        Calendar publishTime = status.getLastPublished();
        ReplicationStatus previewStatus = status.getStatusForAgent(PreviewSyncConstants.PREVIEW_AGENT_ID);
        Calendar previewTime = previewStatus != null ? previewStatus.getLastPublished() : null;

        return previewTime != null && publishTime != null && !previewTime.before(publishTime);
    }
}
