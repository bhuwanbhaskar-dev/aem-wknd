package com.adobe.aem.guides.wknd.core.services.impl;

import com.adobe.aem.guides.wknd.core.services.PackageAnalysisRequest;
import com.adobe.aem.guides.wknd.core.services.PackageAnalyzerService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.Group;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.event.jobs.Job;
import org.apache.sling.event.jobs.JobManager;
import org.apache.sling.event.jobs.consumer.JobExecutionContext;
import org.apache.sling.event.jobs.consumer.JobExecutionResult;
import org.apache.sling.event.jobs.consumer.JobExecutor;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component(service = {PackageAnalyzerService.class, JobExecutor.class},
        configurationPolicy = ConfigurationPolicy.REQUIRE,
        property = JobExecutor.PROPERTY_TOPICS + "=" + PackageAnalyzerServiceImpl.TOPIC)
@Designate(ocd = PackageAnalyzerServiceImpl.Configuration.class)
public class PackageAnalyzerServiceImpl implements PackageAnalyzerService, JobExecutor {
    public static final String TOPIC = "com/adobe/aem/guides/wknd/package_preflight";
    private static final Logger LOG = LoggerFactory.getLogger(PackageAnalyzerServiceImpl.class);
    private static final String OWNER = "preflight.owner";
    private static final String REQUEST = "preflight.request";
    private static final String SUBSERVICE = "package-preflight-reader";

    @ObjectClassDefinition(name = "WKND Package Preflight")
    public @interface Configuration {
        @AttributeDefinition(description = "Explicit operator group; membership permits aggregate analysis of ALL allowed roots.")
        String operatorGroup() default "wknd-package-analyzer-users";
        String[] allowedRoots() default {"/content/wknd", "/content/dam/wknd",
                "/content/experience-fragments/wknd", "/conf/wknd"};
        long maxNodes() default 100000;
        long maxProperties() default 1000000;
        long maxValues() default 2000000;
        long maxScanMillis() default 120000;
        int maxOutstandingJobs() default 20;
        @AttributeDefinition(description = "Planning allowances, not Adobe limits or guaranteed ZIP overhead.")
        long nodeAllowanceBytes() default 4096;
        long propertyAllowanceBytes() default 512;
        long archiveAllowanceBytes() default 65536;
        double transferTargetSeconds() default 60;
        @AttributeDefinition(description = "Use only this fraction of measured throughput times transfer target.")
        double headroomFactor() default 0.7;
    }

    @Reference private ResourceResolverFactory resolverFactory;
    @Reference private JobManager jobManager;
    private volatile Configuration configuration;

    @Activate
    void activate(Configuration config) {
        if (config.allowedRoots().length == 0 || config.operatorGroup().isBlank()
                || config.maxNodes() < 1 || config.maxProperties() < 1 || config.maxValues() < 1
                || config.maxScanMillis() < 1 || config.maxScanMillis() > 3600000
                || config.maxOutstandingJobs() < 1 || config.maxOutstandingJobs() > 1000
                || config.nodeAllowanceBytes() < 0 || config.propertyAllowanceBytes() < 0
                || config.archiveAllowanceBytes() < 0 || !Double.isFinite(config.transferTargetSeconds())
                || config.transferTargetSeconds() <= 0 || config.transferTargetSeconds() > 60
                || !Double.isFinite(config.headroomFactor()) || config.headroomFactor() <= 0
                || config.headroomFactor() > 1) {
            throw new IllegalArgumentException("Invalid package preflight configuration");
        }
        Arrays.stream(config.allowedRoots()).forEach(PackageAnalysisRequest::validatePath);
        configuration = config;
    }

    @Override
    public synchronized JsonObject submit(String requestJson, ResourceResolver caller) {
        String owner = requireOperator(caller);
        Configuration config = configuration;
        PackageAnalysisRequest.parse(requestJson, Arrays.asList(config.allowedRoots()));
        // Admission is best-effort across Author instances; queue concurrency is
        // controlled by Sling, not an in-memory executor or semaphore.
        Collection<Job> outstanding = new ArrayList<>(
                jobManager.findJobs(JobManager.QueryType.QUEUED, TOPIC, config.maxOutstandingJobs()));
        outstanding.addAll(jobManager.findJobs(JobManager.QueryType.ACTIVE, TOPIC, config.maxOutstandingJobs()));
        if (outstanding.size() >= config.maxOutstandingJobs()
                || outstanding.stream().anyMatch(job -> owner.equals(job.getProperty(OWNER, String.class)))) {
            throw new ApiException(429, "An analysis is already outstanding for you, or the queue is full. Retry later.");
        }
        Job job = jobManager.addJob(TOPIC, Map.of(OWNER, owner, REQUEST, requestJson));
        if (job == null) throw new ApiException(503, "Analysis could not be queued.");
        return summary(job);
    }

    @Override
    public JsonObject status(String jobId, ResourceResolver caller) {
        String owner = requireOperator(caller);
        if (jobId == null || jobId.isBlank() || jobId.length() > 512
                || jobId.chars().anyMatch(Character::isISOControl)) {
            throw new ApiException(400, "Supply the jobId returned by POST. GET no longer starts an analysis.");
        }
        Job job = jobManager.getJobById(jobId);
        if (job == null || !TOPIC.equals(job.getTopic()) || !owner.equals(job.getProperty(OWNER, String.class))) {
            throw new ApiException(404, "Analysis not found, not owned by you, or expired from Sling job history.");
        }
        return summary(job);
    }

    private String requireOperator(ResourceResolver caller) {
        if (caller == null || caller.getUserID() == null || caller.getUserID().isBlank()
                || "anonymous".equals(caller.getUserID())) throw new ApiException(401, "Authentication required.");
        try {
            UserManager manager = caller.adaptTo(UserManager.class);
            if (manager != null) {
                Authorizable user = manager.getAuthorizable(caller.getUserID());
                Authorizable group = manager.getAuthorizable(configuration.operatorGroup());
                Authorizable administrators = manager.getAuthorizable("administrators");
                if (user instanceof User && (((User) user).isAdmin()
                        || (group instanceof Group && ((Group) group).isMember(user))
                        || (administrators instanceof Group && ((Group) administrators).isMember(user)))) {
                    return caller.getUserID();
                }
            }
        } catch (RepositoryException e) {
            LOG.warn("Cannot resolve package preflight operator membership", e);
        }
        throw new ApiException(403, "Package preflight operator membership is required.");
    }

    private JsonObject summary(Job job) {
        JsonObject response = new JsonObject();
        response.addProperty("jobId", job.getId());
        response.addProperty("jobState", job.getJobState().name());
        if (job.getCreated() != null) response.addProperty("createdAt", job.getCreated().toInstant().toString());
        if (job.getFinishedDate() != null) response.addProperty("finishedAt", job.getFinishedDate().toInstant().toString());
        String message = job.getResultMessage();
        if (message != null && !message.isBlank()) {
            try {
                response.add("report", JsonParser.parseString(message).getAsJsonObject());
            } catch (RuntimeException e) {
                response.addProperty("error", "Analysis did not produce a report. Check Author job diagnostics.");
            }
        }
        return response;
    }

    @Override
    public JobExecutionResult process(Job job, JobExecutionContext context) {
        if (!TOPIC.equals(job.getTopic())) return context.result().cancelled();
        Configuration config = configuration;
        try {
            PackageAnalysisRequest request = PackageAnalysisRequest.parse(
                    job.getProperty(REQUEST, String.class), Arrays.asList(config.allowedRoots()));
            try (ResourceResolver resolver = resolverFactory.getServiceResourceResolver(
                    Map.of(ResourceResolverFactory.SUBSERVICE, SUBSERVICE))) {
                PackageContentScanner.Limits limits = new PackageContentScanner.Limits(
                        config.maxNodes(), config.maxProperties(), config.maxValues(), config.maxScanMillis(),
                        config.nodeAllowanceBytes(), config.propertyAllowanceBytes(), config.archiveAllowanceBytes(),
                        config.transferTargetSeconds(), config.headroomFactor());
                JsonObject report = new PackageContentScanner().scan(request, resolver.adaptTo(Session.class),
                        limits, context::isStopped);
                report.addProperty("filterXml", request.filterXml);
                // No content mutations. Re-execution after a crash is safe; the scan is
                // recomputed from live content, and Sling persists the result message.
                return context.result().message(report.toString()).succeeded();
            }
        } catch (ApiException e) {
            return context.result().message("{\"status\":\"INCOMPLETE\",\"assessment\":\"UNKNOWN\","
                    + "\"reason\":\"INVALID_OR_NO_LONGER_ALLOWED_REQUEST\"}").cancelled();
        } catch (Exception e) {
            LOG.error("Package preflight job {} failed", job.getId(), e);
            return context.result().message("{\"status\":\"INCOMPLETE\",\"assessment\":\"UNKNOWN\","
                    + "\"reason\":\"SERVICE_UNAVAILABLE\"}").failed();
        }
    }
}
