package com.adobe.aem.guides.wknd.core.servlets;

import com.adobe.aem.guides.wknd.core.services.PackageAnalyzerService;
import com.adobe.aem.guides.wknd.core.services.PackageAnalyzerService.ApiException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedReader;
import java.io.StringReader;
import java.util.Map;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.ResourceResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import io.wcm.testing.mock.aem.junit5.AemContext;
import io.wcm.testing.mock.aem.junit5.AemContextExtension;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith({AemContextExtension.class, MockitoExtension.class})
class PackageAnalyzerServletTest {
    private final AemContext context = new AemContext();
    @Mock PackageAnalyzerService service;
    @Mock SlingHttpServletRequest request;
    @Mock ResourceResolver caller;
    PackageAnalyzerServlet servlet;

    @BeforeEach void setup() {
        context.registerService(PackageAnalyzerService.class, service);
        servlet = context.registerInjectActivateService(new PackageAnalyzerServlet(), Map.of("enabled", true));
        when(request.getResourceResolver()).thenReturn(caller);
        lenient().when(caller.getUserID()).thenReturn("alice");
    }

    @Test void postReturns202AndEncodedPollingLocation() throws Exception {
        when(request.getContentType()).thenReturn("application/json; charset=UTF-8");
        when(request.getReader()).thenReturn(new BufferedReader(new StringReader("{}")));
        JsonObject result = new JsonObject();
        result.addProperty("jobId", "2026/09/a b");
        when(service.submit("{}", caller)).thenReturn(result);
        servlet.doPost(request, context.response());
        assertEquals(202, context.response().getStatus());
        assertTrue(context.response().getHeader("Location").contains("2026%2F09%2Fa+b"));
        assertEquals("private, no-store", context.response().getHeader("Cache-Control"));
    }

    @Test void getPollsWithoutCreatingWork() throws Exception {
        when(request.getParameterValues("jobId")).thenReturn(new String[]{"id"});
        when(service.status("id", caller)).thenReturn(new JsonObject());
        servlet.doGet(request, context.response());
        assertEquals(200, context.response().getStatus());
        verify(service, never()).submit(anyString(), any());
    }

    @Test void legacyGetAndDuplicateIdsAreRejected() throws Exception {
        servlet.doGet(request, context.response());
        assertEquals(400, context.response().getStatus());
        when(request.getParameterValues("jobId")).thenReturn(new String[]{"one", "two"});
        servlet.doGet(request, context.response());
        assertEquals(400, context.response().getStatus());
        verifyNoInteractions(service);
    }

    @Test void anonymousAndUnsupportedContentTypeAreRejected() throws Exception {
        when(caller.getUserID()).thenReturn("anonymous");
        servlet.doPost(request, context.response());
        assertEquals(401, context.response().getStatus());
        when(caller.getUserID()).thenReturn("alice");
        servlet.doPost(request, context.response());
        assertEquals(415, context.response().getStatus());
        verifyNoInteractions(service);
    }

    @Test void oversizedBodyRejectedBeforeSubmission() throws Exception {
        when(request.getContentType()).thenReturn("application/json");
        when(request.getReader()).thenReturn(new BufferedReader(new StringReader("x".repeat(65537))));
        servlet.doPost(request, context.response());
        assertEquals(413, context.response().getStatus());
        verifyNoInteractions(service);
    }

    @Test void apiErrorsAreJsonAndPrivate() throws Exception {
        when(request.getParameterValues("jobId")).thenReturn(new String[]{"id"});
        when(service.status("id", caller)).thenThrow(new ApiException(429, "Try \"later\".\nWait."));
        servlet.doGet(request, context.response());
        assertEquals(429, context.response().getStatus());
        assertEquals("10", context.response().getHeader("Retry-After"));
        assertEquals("Try \"later\".\nWait.", JsonParser.parseString(context.response().getOutputAsString()).getAsJsonObject().get("error").getAsString());
        assertEquals("private, no-store", context.response().getHeader("Cache-Control"));
    }

    @Test void unexpectedExceptionsDoNotLeakDetails() throws Exception {
        when(request.getParameterValues("jobId")).thenReturn(new String[]{"id"});
        when(service.status("id", caller)).thenThrow(new IllegalStateException("private repository path"));
        servlet.doGet(request, context.response());
        assertEquals(503, context.response().getStatus());
        assertFalse(context.response().getOutputAsString().contains("private repository"));
    }
}
