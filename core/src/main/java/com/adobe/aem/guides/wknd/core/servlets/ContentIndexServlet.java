/*
 * Copyright 2024 Adobe Systems Incorporated
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.adobe.aem.guides.wknd.core.servlets;

import com.adobe.aem.guides.wknd.core.services.ContentIndexService;
import com.adobe.aem.guides.wknd.core.services.ContentIndexService.ContentIndexException;
import com.adobe.aem.guides.wknd.core.services.ContentIndexService.IndexEntry;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.servlets.SlingAllMethodsServlet;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.Servlet;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;

/**
 * Servlet that exposes the {@link ContentIndexService} over HTTP.
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET  /bin/wknd/content-index}         — read current index</li>
 *   <li>{@code POST /bin/wknd/content-index}         — rebuild the index</li>
 * </ul>
 *
 * <h2>Why a path-based servlet here?</h2>
 * <p>
 * Path-based servlets (registered at a fixed URL like /bin/wknd/...) are
 * simpler for API-style endpoints. Resource-type-based servlets are better
 * for rendering components. Both are valid; the choice depends on the use case.
 * </p>
 *
 * <h2>Security note</h2>
 * <p>
 * This servlet is deployed on Author only (see OSGi config in config.author/).
 * It does not use the request's ResourceResolver for repository operations —
 * all JCR access goes through ContentIndexService which uses its own service
 * resolver. The request ResourceResolver is used only to check the caller's
 * identity for authorization purposes (omitted here for simplicity, but
 * in production you would check if the caller is an author/admin).
 * </p>
 */
@Component(service = Servlet.class, property = {
    "sling.servlet.paths=/bin/wknd/content-index",
    "sling.servlet.methods=GET",
    "sling.servlet.methods=POST"
})
public class ContentIndexServlet extends SlingAllMethodsServlet {

    private static final long serialVersionUID = 1L;

    private static final Logger LOG = LoggerFactory.getLogger(ContentIndexServlet.class);
    private static final String CONTENT_TYPE_JSON = "application/json;charset=UTF-8";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /*
     * ── Key learning point ────────────────────────────────────────────────
     *
     * The servlet injects ContentIndexService (the interface, not the impl).
     * The servlet itself does NOT call ResourceResolverFactory.
     *
     * This is the correct separation of concerns:
     *   - Servlet: HTTP I/O, request parsing, response writing, error codes
     *   - Service:  Business logic, JCR access, subservice authentication
     *
     * The servlet's own request ResourceResolver (request.getResourceResolver())
     * runs as the logged-in user (e.g., admin during development). We do NOT
     * use that resolver to access /var/wknd/content-index — we delegate to
     * the service which uses a restricted system user.
     */
    @Reference
    private ContentIndexService contentIndexService;

    /**
     * GET /bin/wknd/content-index
     *
     * Returns the currently persisted index as JSON.
     * Does NOT trigger a new scan.
     */
    @Override
    protected void doGet(SlingHttpServletRequest request, SlingHttpServletResponse response)
            throws IOException {
        LOG.debug("[ContentIndexServlet] GET /bin/wknd/content-index requested by '{}'",
            request.getResourceResolver().getUserID());

        response.setContentType(CONTENT_TYPE_JSON);
        response.setHeader("Cache-Control", "no-store, private");

        try {
            List<IndexEntry> entries = contentIndexService.readIndex();
            response.getWriter().write(buildJsonResponse("read", entries, null).toString());
            response.setStatus(HttpServletResponse.SC_OK);
        } catch (ContentIndexException e) {
            LOG.error("[ContentIndexServlet] readIndex() failed", e);
            writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    /**
     * POST /bin/wknd/content-index
     *
     * Triggers a full index rebuild. Scans /content/wknd/us/en and writes
     * results to /var/wknd/content-index. Returns the new index as JSON.
     */
    @Override
    protected void doPost(SlingHttpServletRequest request, SlingHttpServletResponse response)
            throws IOException {
        LOG.info("[ContentIndexServlet] POST /bin/wknd/content-index — rebuild requested by '{}'",
            request.getResourceResolver().getUserID());

        response.setContentType(CONTENT_TYPE_JSON);
        response.setHeader("Cache-Control", "no-store, private");

        try {
            List<IndexEntry> entries = contentIndexService.buildIndex();
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write(buildJsonResponse("built", entries, null).toString());
        } catch (ContentIndexException e) {
            LOG.error("[ContentIndexServlet] buildIndex() failed", e);
            writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getMessage());
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    private JsonObject buildJsonResponse(String action, List<IndexEntry> entries, String error) {
        JsonObject root = new JsonObject();
        root.addProperty("action", action);
        root.addProperty("indexRoot", ContentIndexService.INDEX_ROOT);
        root.addProperty("contentRoot", ContentIndexService.CONTENT_ROOT);

        if (error != null) {
            root.addProperty("error", error);
        } else {
            root.addProperty("entryCount", entries.size());
            JsonArray arr = new JsonArray();
            for (IndexEntry e : entries) {
                JsonObject item = new JsonObject();
                item.addProperty("path", e.getPath());
                item.addProperty("title", e.getTitle());
                item.addProperty("lastModified", e.getLastModified());
                arr.add(item);
            }
            root.add("entries", arr);
        }
        return root;
    }

    private void writeError(SlingHttpServletResponse response, int status, String message)
            throws IOException {
        response.setStatus(status);
        JsonObject error = new JsonObject();
        error.addProperty("error", message);
        response.getWriter().write(GSON.toJson(error));
    }
}
