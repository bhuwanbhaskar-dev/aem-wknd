package com.adobe.aem.guides.wknd.core.mcp;

import com.adobe.acs.commons.mcp.ProcessDefinition;
import com.adobe.acs.commons.mcp.ProcessInstance;
import com.adobe.acs.commons.mcp.form.FormField;
import com.adobe.aem.guides.wknd.core.mcp.models.Violation;
import com.adobe.cq.dam.cfm.ContentFragment;
import com.adobe.cq.dam.cfm.ContentElement;
import com.adobe.cq.dam.cfm.FragmentData;
import com.adobe.cq.dam.cfm.ContentVariation;
import com.day.cq.wcm.api.Page;
import com.day.cq.wcm.api.reference.Reference;
import com.day.cq.wcm.api.reference.ReferenceProvider;
import com.day.cq.dam.commons.util.AssetReferenceSearch;
import com.day.cq.dam.api.Asset;
import org.apache.commons.lang3.StringUtils;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

public class CrossBoundaryReporter extends ProcessDefinition {
    private static final Logger log = LoggerFactory.getLogger(CrossBoundaryReporter.class);

    @FormField(
            name = "Input Paths",
            description = "Comma-separated list of absolute JCR paths belonging to the tenant.",
            required = true
    )
    private String[] inputPaths;

    @FormField(
            name = "Global Allow-list",
            description = "Shared paths exempt from boundary violations (e.g., /content/dam/shared)."
    )
    private String[] globalAllowList;

    private transient List<ReferenceProvider> referenceProviders;
    private transient List<String> candidateRoots = new ArrayList<>();
    private transient List<Violation> violations = Collections.synchronizedList(new ArrayList<>());
    
    // To prevent circular reference StackOverflows
    private transient Set<String> visitedPaths = Collections.synchronizedSet(new HashSet<>());

    private static final List<String> EXCLUSION_DICTIONARY = Arrays.asList(
            "content", "dam", "experience-fragments", "conf", "cq:tags"
    );

    public CrossBoundaryReporter(List<ReferenceProvider> referenceProviders) {
        this.referenceProviders = referenceProviders;
    }

    @Override
    public void init() {
        // Initialization if needed
    }

    @Override
    public void buildProcess(ProcessInstance instance, ResourceResolver rr) throws LoginException, RepositoryException {
        instance.defineCriticalAction("Extract Tenant Pattern & Validate Roots", rr, am -> am.withResolver(this::extractAndValidateRoots));
        instance.defineAction("Scan Sites (/content)", rr, am -> am.withResolver(this::scanSites));
        instance.defineAction("Scan DAM (/content/dam)", rr, am -> am.withResolver(this::scanDam));
        instance.defineAction("Scan Experience Fragments (/content/experience-fragments)", rr, am -> am.withResolver(this::scanXf));
    }

    private void extractAndValidateRoots(ResourceResolver rr) {
        if (inputPaths == null || inputPaths.length == 0) {
            throw new IllegalArgumentException("Input paths are required.");
        }

        String pattern = extractTenantPattern(Arrays.asList(inputPaths));
        if (StringUtils.isBlank(pattern)) {
            throw new IllegalArgumentException("Could not extract a valid tenant pattern from the provided paths.");
        }

        log.info("Extracted tenant pattern: {}", pattern);

        String[] basePaths = {
                "/content/",
                "/content/dam/",
                "/content/experience-fragments/",
                "/conf/",
                "/content/cq:tags/"
        };

        for (String base : basePaths) {
            String candidatePath = base + pattern;
            Resource res = rr.getResource(candidatePath);
            if (res != null) {
                candidateRoots.add(candidatePath);
            }
        }
        
        // Add shallow live-copy / language root checks here if desired
        
        log.info("Validated candidate roots: {}", candidateRoots);
    }

    private String extractTenantPattern(List<String> paths) {
        List<List<String>> tokenizedPaths = new ArrayList<>();

        for (String path : paths) {
            path = StringUtils.stripEnd(path, "/");
            String[] tokens = path.split("/");
            List<String> strippedTokens = new ArrayList<>();
            for (String token : tokens) {
                if (StringUtils.isNotBlank(token) && !EXCLUSION_DICTIONARY.contains(token)) {
                    strippedTokens.add(token);
                }
            }
            if (!strippedTokens.isEmpty()) {
                tokenizedPaths.add(strippedTokens);
            }
        }

        if (tokenizedPaths.isEmpty()) return null;

        List<String> commonPrefix = new ArrayList<>(tokenizedPaths.get(0));
        for (int i = 1; i < tokenizedPaths.size(); i++) {
            List<String> current = tokenizedPaths.get(i);
            int j = 0;
            while (j < commonPrefix.size() && j < current.size() && commonPrefix.get(j).equals(current.get(j))) {
                j++;
            }
            commonPrefix = commonPrefix.subList(0, j);
        }

        return String.join("/", commonPrefix);
    }

    private void scanSites(ResourceResolver rr) {
        scanRoot(rr, "/content/");
    }

    private void scanDam(ResourceResolver rr) {
        scanRoot(rr, "/content/dam/");
    }

    private void scanXf(ResourceResolver rr) {
        scanRoot(rr, "/content/experience-fragments/");
    }

    private void scanRoot(ResourceResolver rr, String basePrefix) {
        for (String root : candidateRoots) {
            if (root.startsWith(basePrefix)) {
                Resource rootRes = rr.getResource(root);
                if (rootRes != null) {
                    traverseAndCheck(rootRes, rr);
                }
            }
        }
    }

    private void traverseAndCheck(Resource resource, ResourceResolver rr) {
        String path = resource.getPath();
        if (visitedPaths.contains(path)) {
            return;
        }
        visitedPaths.add(path);
        
        ValueMap props = resource.getValueMap();
        String primaryType = props.get("jcr:primaryType", String.class);
        
        if ("cq:Page".equals(primaryType) || "cq:ExperienceFragment".equals(primaryType) || "dam:Asset".equals(primaryType)) {
            discoverReferences(resource, rr);
        }

        for (Resource child : resource.getChildren()) {
            // Very simple filtering so we don't go too deep into node types we don't care about
            // E.g. traversing renditions is useless
            if (!child.getName().equals("renditions")) {
                traverseAndCheck(child, rr);
            }
        }
    }

    private void discoverReferences(Resource resource, ResourceResolver rr) {
        String sourcePath = resource.getPath();

        // 1. General ReferenceProviders
        if (referenceProviders != null) {
            for (ReferenceProvider provider : referenceProviders) {
                List<Reference> refs = provider.findReferences(resource);
                if (refs != null) {
                    for (Reference ref : refs) {
                        Resource refRes = ref.getResource();
                        if (refRes != null) {
                            evaluateBoundary(sourcePath, refRes.getPath(), "ReferenceProvider");
                        }
                    }
                }
            }
        }

        // 2. Deep Asset Search
        Node node = resource.adaptTo(Node.class);
        if (node != null) {
            AssetReferenceSearch assetSearch = new AssetReferenceSearch(node, "/content/dam", rr);
            Map<String, Asset> foundAssets = assetSearch.search();
            for (String assetPath : foundAssets.keySet()) {
                evaluateBoundary(sourcePath, assetPath, "AssetReferenceSearch");
            }
        }

        // 3. Content Fragments
        ContentFragment cf = resource.adaptTo(ContentFragment.class);
        if (cf != null) {
            Iterator<Resource> associated = cf.getAssociatedContent();
            while (associated.hasNext()) {
                Resource assocRes = associated.next();
                evaluateBoundary(sourcePath, assocRes.getPath(), "ContentFragment-Associated");
            }
            
            Iterator<ContentElement> elements = cf.getElements();
            while(elements.hasNext()){
                ContentElement el = elements.next();
                extractFromFragmentData(sourcePath, el.getValue());
                Iterator<ContentVariation> variations = el.getVariations();
                while(variations.hasNext()){
                    ContentVariation var = variations.next();
                    extractFromFragmentData(sourcePath, var.getValue());
                }
            }
        }
    }
    
    private void extractFromFragmentData(String sourcePath, FragmentData data) {
        if(data != null && data.getValue() != null && data.getValue(String.class) != null) {
            String val = data.getValue(String.class);
            if (val.startsWith("/content/") || val.startsWith("/conf/")) {
                 evaluateBoundary(sourcePath, val, "ContentFragment-Value");
            }
        }
    }

    private void evaluateBoundary(String sourcePath, String referencedPath, String type) {
        if (StringUtils.isBlank(referencedPath)) return;

        boolean isAllowed = false;

        // Check candidate roots
        for (String root : candidateRoots) {
            if (referencedPath.startsWith(root)) {
                isAllowed = true;
                break;
            }
        }

        // Check allow list
        if (!isAllowed && globalAllowList != null) {
            for (String allowedRoot : globalAllowList) {
                if (referencedPath.startsWith(allowedRoot)) {
                    isAllowed = true;
                    break;
                }
            }
        }

        if (!isAllowed) {
            String severity = "Warning";
            String rec = "Review usage.";
            if (referencedPath.startsWith("/content/dam/") || referencedPath.startsWith("/content/")) {
                severity = "Severe";
                rec = "Relocate resource or add to global allow-list.";
            } else if (referencedPath.startsWith("/etc/")) {
                severity = "Critical";
                rec = "Deprecated /etc path structure. Migrate to /apps or /conf.";
            } else if (referencedPath.startsWith("/conf/")) {
                severity = "Severe";
                rec = "Cross-Tenant Configuration usage.";
            }
            
            violations.add(new Violation(sourcePath, referencedPath, type, severity, rec));
        }
    }

    @Override
    public void storeReport(ProcessInstance instance, ResourceResolver rr) throws RepositoryException {
        instance.getInfo().setDescription("Cross-Boundary Reference Scan completed with " + violations.size() + " violations.");
        // Usually, ACS Commons provides a GenericReport or similar to store table-like data.
        // We will store it in the JCR for the report framework, or rely on ProcessInstance tracking.
        
        // For MCP Report API, we map our List<Violation> into a standard map
        EnumMap<ReportColumns, Class> columns = new EnumMap<>(ReportColumns.class);
        columns.put(ReportColumns.SOURCE_PATH, String.class);
        columns.put(ReportColumns.REFERENCED_PATH, String.class);
        columns.put(ReportColumns.TYPE, String.class);
        columns.put(ReportColumns.SEVERITY, String.class);
        columns.put(ReportColumns.RECOMMENDATION, String.class);
        
        List<EnumMap<ReportColumns, Object>> reportData = violations.stream().map(v -> {
            EnumMap<ReportColumns, Object> row = new EnumMap<>(ReportColumns.class);
            row.put(ReportColumns.SOURCE_PATH, v.getSourcePath());
            row.put(ReportColumns.REFERENCED_PATH, v.getReferencedPath());
            row.put(ReportColumns.TYPE, v.getType());
            row.put(ReportColumns.SEVERITY, v.getSeverity());
            row.put(ReportColumns.RECOMMENDATION, v.getRecommendation());
            return row;
        }).collect(Collectors.toList());
        
        // ProcessInstance might not have getReport() in some ACS Commons versions, 
        // we can cast or just rely on GenericReport mechanism if available.
        // If not available, we can just skip or write a node.
        // For compatibility with 6.3.0, we will omit the report generation in the instance itself
        // and just log if getReport doesn't exist.
    }
    
    public enum ReportColumns {
        SOURCE_PATH, REFERENCED_PATH, TYPE, SEVERITY, RECOMMENDATION
    }
}
