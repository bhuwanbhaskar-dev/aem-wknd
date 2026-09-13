package com.adobe.aem.guides.wknd.core.services.impl;

import com.adobe.aem.guides.wknd.core.services.PackageAnalyzerService.ApiException;
import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.jackrabbit.api.security.user.Group;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.event.jobs.Job;
import org.apache.sling.event.jobs.JobManager;
import org.apache.sling.event.jobs.consumer.JobExecutionContext;
import org.apache.sling.event.jobs.consumer.JobExecutionResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PackageAnalyzerServiceImplTest {
    @Mock ResourceResolverFactory resolverFactory;
    @Mock JobManager jobManager;
    @Mock ResourceResolver caller;
    @Mock UserManager userManager;
    @Mock User user;
    @Mock Group group;
    @InjectMocks PackageAnalyzerServiceImpl service;

    @BeforeEach void setup() throws Exception {
        var configuration = mock(PackageAnalyzerServiceImpl.Configuration.class, i -> i.getMethod().getDefaultValue());
        service.activate(configuration);
        lenient().when(caller.getUserID()).thenReturn("alice");
        lenient().when(caller.adaptTo(UserManager.class)).thenReturn(userManager);
        lenient().when(userManager.getAuthorizable("alice")).thenReturn(user);
        lenient().when(userManager.getAuthorizable("wknd-package-analyzer-users")).thenReturn(group);
        lenient().when(group.isMember(user)).thenReturn(true);
        lenient().when(jobManager.findJobs(any(), anyString(), anyLong())).thenReturn(Collections.emptyList());
    }

    String request() {
        JsonObject body = new JsonObject();
        body.addProperty("filterXml", "<workspaceFilter version=\"1.0\"><filter root=\"/content/dam/wknd\"/></workspaceFilter>");
        body.addProperty("measuredMbps", 100);
        return body.toString();
    }

    Job job(String owner) {
        Job job = mock(Job.class);
        lenient().when(job.getId()).thenReturn("job-1");
        lenient().when(job.getTopic()).thenReturn(PackageAnalyzerServiceImpl.TOPIC);
        lenient().when(job.getJobState()).thenReturn(Job.JobState.QUEUED);
        lenient().when(job.getProperty("preflight.owner", String.class)).thenReturn(owner);
        return job;
    }

    @Test void submitQueuesSnapshotWithoutOpeningServiceResolver() throws Exception {
        Job queued = job("alice");
        when(jobManager.addJob(eq(PackageAnalyzerServiceImpl.TOPIC), anyMap())).thenReturn(queued);
        assertEquals("job-1", service.submit(request(), caller).get("jobId").getAsString());
        verify(jobManager).addJob(PackageAnalyzerServiceImpl.TOPIC, Map.of("preflight.owner", "alice", "preflight.request", request()));
        verifyNoInteractions(resolverFactory);
    }

    @Test void rejectsAnonymousAndNonOperators() throws Exception {
        when(caller.getUserID()).thenReturn("anonymous");
        assertEquals(401, assertThrows(ApiException.class, () -> service.submit(request(), caller)).getStatus());
        when(caller.getUserID()).thenReturn("alice");
        when(group.isMember(user)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class, () -> service.submit(request(), caller)).getStatus());
        verifyNoInteractions(jobManager, resolverFactory);
    }

    @Test void rejectsSecondOutstandingJobForSameOwner() {
        Job queued = job("alice");
        when(jobManager.findJobs(eq(JobManager.QueryType.QUEUED), anyString(), anyLong())).thenReturn(List.of(queued));
        assertEquals(429, assertThrows(ApiException.class, () -> service.submit(request(), caller)).getStatus());
        verify(jobManager, never()).addJob(anyString(), anyMap());
    }

    @Test void nullJobIsServiceUnavailable() {
        assertEquals(503, assertThrows(ApiException.class, () -> service.submit(request(), caller)).getStatus());
    }

    @Test void resultIsOwnerAndTopicScopedAndRechecksMembership() throws Exception {
        Job job = job("bob");
        when(jobManager.getJobById("job-1")).thenReturn(job);
        assertEquals(404, assertThrows(ApiException.class, () -> service.status("job-1", caller)).getStatus());
        when(job.getProperty("preflight.owner", String.class)).thenReturn("alice");
        when(job.getResultMessage()).thenReturn("{\"scanComplete\":true,\"exactPackageSize\":false}");
        assertTrue(service.status("job-1", caller).getAsJsonObject("report").get("scanComplete").getAsBoolean());
        when(job.getTopic()).thenReturn("unrelated/topic");
        assertEquals(404, assertThrows(ApiException.class, () -> service.status("job-1", caller)).getStatus());
        when(group.isMember(user)).thenReturn(false);
        assertEquals(403, assertThrows(ApiException.class, () -> service.status("job-1", caller)).getStatus());
    }

    @Test void administratorsGroupIsRecognizedWithoutBuiltinAdminIdentity() throws Exception {
        when(group.isMember(user)).thenReturn(false);
        Group administrators = mock(Group.class);
        when(userManager.getAuthorizable("administrators")).thenReturn(administrators);
        when(administrators.isMember(user)).thenReturn(true);
        Job queued = job("alice");
        when(jobManager.addJob(eq(PackageAnalyzerServiceImpl.TOPIC), anyMap())).thenReturn(queued);
        assertEquals("job-1", service.submit(request(), caller).get("jobId").getAsString());
    }
    @Test void expiredResultIs404() {
        assertEquals(404, assertThrows(ApiException.class, () -> service.status("missing", caller)).getStatus());
    }

    @Test void workerClosesResolverAndDoesNotMutateContent() throws Exception {
        Job job = job("alice");
        when(job.getProperty("preflight.request", String.class)).thenReturn(request());
        ResourceResolver worker = mock(ResourceResolver.class);
        when(resolverFactory.getServiceResourceResolver(anyMap())).thenReturn(worker);
        JobExecutionContext context = mock(JobExecutionContext.class);
        JobExecutionContext.ResultBuilder result = mock(JobExecutionContext.ResultBuilder.class, RETURNS_SELF);
        JobExecutionResult completed = mock(JobExecutionResult.class);
        when(context.result()).thenReturn(result);
        when(result.succeeded()).thenReturn(completed);
        // Null session produces an explicit incomplete report, not a false zero.
        assertSame(completed, service.process(job, context));
        verify(worker).close();
        verify(worker, never()).commit();
        verify(result).message(contains("\"assessment\":\"UNKNOWN\""));
    }
}
