package com.adobe.aem.guides.wknd.core.servlets;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import javax.servlet.Servlet;
import com.adobe.aem.guides.wknd.core.services.PackageAnalysisRequest;
import com.adobe.aem.guides.wknd.core.services.PackageAnalyzerService;
import com.adobe.aem.guides.wknd.core.services.PackageAnalyzerService.ApiException;
import com.google.gson.JsonObject;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Submit with POST + Granite CSRF token; GET polls an owner-scoped Sling job. */
@Designate(ocd = PackageAnalyzerServlet.Configuration.class)
@Component(service = Servlet.class, configurationPolicy = ConfigurationPolicy.REQUIRE, property = {
        "sling.servlet.paths=/bin/api/analyze-package",
        "sling.servlet.methods=GET",
        "sling.servlet.methods=POST"})
public class PackageAnalyzerServlet extends SlingAllMethodsServlet {
    private static final long serialVersionUID = 1L;
    private static final Logger LOG = LoggerFactory.getLogger(PackageAnalyzerServlet.class);
    public static final String PATH = "/bin/api/analyze-package";

    @ObjectClassDefinition(name = "WKND Package Preflight Endpoint (Author only)")
    public @interface Configuration { boolean enabled() default false; }
    @Reference private PackageAnalyzerService analyzerService;
    private volatile boolean enabled;

    @Activate
    void activate(Configuration config) { enabled = config.enabled(); }

    @Override
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        handle(request, response, true);
    }

    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response) throws IOException {
        handle(request, response, false);
    }

    private void handle(SlingHttpServletRequest request, SlingHttpServletResponse response, boolean submit) throws IOException {
        response.setHeader("Cache-Control", "private, no-store");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        try {
            if (!enabled) throw new ApiException(404, "Endpoint is not enabled.");
            String user = request.getResourceResolver().getUserID();
            if (user == null || user.isBlank() || "anonymous".equals(user)) throw new ApiException(401, "Authentication required.");
            JsonObject result;
            if (submit) {
                String contentType = request.getContentType();
                if (contentType == null || !"application/json".equalsIgnoreCase(contentType.split(";")[0].trim())) {
                    throw new ApiException(415, "Use Content-Type: application/json.");
                }
                // Enforce limit during reading, including requests without Content-Length.
                StringBuilder json = new StringBuilder();
                char[] buffer = new char[2048];
                int count;
                var reader = request.getReader();
                while ((count = reader.read(buffer)) != -1) {
                    if (json.length() + count > PackageAnalysisRequest.MAX_REQUEST_CHARS) {
                        throw new ApiException(413, "Request is too large.");
                    }
                    json.append(buffer, 0, count);
                }
                result = analyzerService.submit(json.toString(), request.getResourceResolver());
                response.setStatus(202);
                response.setHeader("Location", PATH + "?jobId=" + URLEncoder.encode(
                        result.get("jobId").getAsString(), StandardCharsets.UTF_8));
                response.setHeader("Retry-After", "5");
            } else {
                String[] ids = request.getParameterValues("jobId");
                if (ids == null || ids.length != 1 || request.getParameter("path") != null) {
                    throw new ApiException(400, "GET requires exactly one jobId. Submit package filters using POST first.");
                }
                result = analyzerService.status(ids[0], request.getResourceResolver());
                response.setStatus(200);
            }
            response.getWriter().write(result.toString());
        } catch (ApiException e) {
            if (e.getStatus() == 429 || e.getStatus() == 503) response.setHeader("Retry-After", "10");
            writeError(response, e.getStatus(), e.getMessage());
        } catch (RuntimeException e) {
            LOG.error("Package preflight endpoint failed", e);
            writeError(response, 503, "Package preflight is temporarily unavailable.");
        }
    }

    private void writeError(SlingHttpServletResponse response, int status, String message) throws IOException {
        JsonObject error = new JsonObject();
        error.addProperty("error", message);
        response.setStatus(status);
        response.getWriter().write(error.toString());
    }
}
