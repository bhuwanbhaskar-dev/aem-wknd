# External Link Rewriter in AEM as a Cloud Service

This document provides a technical overview of how the external link rewriter was implemented in the WKND project, the issue diagnosed, the resolution, and how Sling Rewriter pipelines function in AEM as a Cloud Service (AEMaaCS).

---

## 1. Objective and Use Case

In content pages under `/content/wknd`, all external links (`href` starting with `http://` or `https://`) must automatically:
1. Open in a new tab by injecting `target="_blank"`.
2. Protect against reverse tabnapping security vulnerabilities by injecting `rel="noopener noreferrer"`.
3. Leave internal links (such as `/content/wknd/...`) unaffected.
4. Avoid requiring content authors to manually set attributes in component dialogs.

---

## 2. Technical Architecture

Sling Rewriter processes the generated HTML response through a SAX-based event pipeline consisting of:
- **Generator (`htmlparser`):** Parses the HTML output into SAX events (`startElement`, `characters`, `endElement`).
- **Transformers:** Modifies or inspects the SAX event stream.
- **Serializer (`htmlwriter`):** Serializes the SAX events back into an HTML response stream.

### Components in WKND

#### A. Custom Transformer: `ExternalLinkTransformer`
File: [`core/.../rewriter/ExternalLinkTransformer.java`](../core/src/main/java/com/adobe/aem/guides/wknd/core/rewriter/ExternalLinkTransformer.java)

Extends `org.apache.sling.rewriter.DefaultTransformer` and overrides `startElement`:
- Checks if the element is an anchor tag (`<a>`).
- Inspects the `href` attribute. If it begins with `http://` or `https://`:
  - Clones attributes into `AttributesImpl`.
  - Appends `target="_blank"` if not already present.
  - Appends `rel="noopener noreferrer"` if not already present.
  - Passes modified attributes down the SAX pipeline.
- If not an external link, forwards the original attributes unchanged (`super.startElement`).

#### B. OSGi Factory: `ExternalLinkTransformerFactory`
File: [`core/.../rewriter/ExternalLinkTransformerFactory.java`](../core/src/main/java/com/adobe/aem/guides/wknd/core/rewriter/ExternalLinkTransformerFactory.java)

Registers an OSGi service implementing `org.apache.sling.rewriter.TransformerFactory` with the pipeline property:
```java
@Component(
    service = TransformerFactory.class,
    property = {
        "pipeline.type=external-link-transformer"
    }
)
public class ExternalLinkTransformerFactory implements TransformerFactory {
    @Override
    public Transformer createTransformer() {
        return new ExternalLinkTransformer();
    }
}
```

#### C. JCR Pipeline Configuration: `wknd-rewriter`
File: [`ui.apps/.../apps/wknd/config/rewriter/wknd-rewriter/.content.xml`](../ui.apps/src/main/content/jcr_root/apps/wknd/config/rewriter/wknd-rewriter/.content.xml)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<jcr:root xmlns:jcr="http://www.jcp.org/jcr/1.0" xmlns:nt="http://www.jcp.org/jcr/nt/1.0"
    jcr:primaryType="nt:unstructured"
    contentTypes="[text/html]"
    enabled="{Boolean}true"
    generatorType="htmlparser"
    order="{Long}1"
    paths="[/content/wknd]"
    serializerType="htmlwriter"
    transformerTypes="[linkchecker,external-link-transformer]"/>
```

---

## 3. Incident Diagnosis: HTTP 500 on `/content/wknd`

### Symptom
Accessing pages or console views (e.g., Coral UI column view) under `/content/wknd` failed with:
```text
com.adobe.granite.metrics.knownerrors.impl.ErrorLoggingComponentFilter Exception in component:
category=ADOBE_UNKNOWN, resourceType=cq/gui/components/coral/admin/page/columnitem,
exceptionType=org.apache.sling.api.SlingException, errorId=CQ_RESOURCE_TYPES, resource=/content/wknd/fr,
exceptionMessage = Unable to setup processor: Unable to get component of class 'interface org.apache.sling.rewriter.Transformer' with type 'linkrewriter'.

java.io.IOException: Unable to get component of class 'interface org.apache.sling.rewriter.Transformer' with type 'linkrewriter'.
    at org.apache.sling.rewriter.impl.PipelineImpl.getPipelineComponent(PipelineImpl.java:165)
    at org.apache.sling.rewriter.impl.PipelineImpl.init(PipelineImpl.java:105)
    at org.apache.sling.rewriter.impl.ProcessorManagerImpl.getProcessor(ProcessorManagerImpl.java:422)
```

### Root Cause
In the pipeline definition (`.content.xml`), `transformerTypes` was configured as:
```xml
transformerTypes="[linkchecker,linkrewriter,external-link-transformer]"
```
1. **`linkchecker`**: Built-in Sling/AEM transformer (valid).
2. **`linkrewriter`**: Legacy transformer type from older setups; **does not exist** in AEM as a Cloud Service, and no `TransformerFactory` with `pipeline.type=linkrewriter` was registered.
3. **`external-link-transformer`**: Custom WKND transformer (valid).

When `PipelineImpl.init()` runs, it attempts to resolve an OSGi `TransformerFactory` for **every** entry in `transformerTypes`. If any entry cannot be resolved, initialization fails and throws an unhandled `IOException`, cascading into an HTTP 500 error.

### Resolution
Removed `linkrewriter` from `transformerTypes` in [`.content.xml`](../ui.apps/src/main/content/jcr_root/apps/wknd/config/rewriter/wknd-rewriter/.content.xml):
```xml
transformerTypes="[linkchecker,external-link-transformer]"
```

---

## 4. Deployment and Verification

1. **Commit and Push:**
   - Committed change to `feature/preview-sync` (`3366b4a`).
   - Pushed to Cloud Manager Git remote (`https://git.cloudmanager.adobe.com/...`).
2. **Rapid Development Environment (RDE) Deployment:**
   - Cloud Manager pipeline pushes do not update RDE instances directly.
   - Deployed the file directly to author and publish instances via Adobe I/O CLI:
     ```bash
     aio aem rde install "ui.apps/src/main/content/jcr_root/apps/wknd/config/rewriter/wknd-rewriter/.content.xml" --type content-xml --path "/apps/wknd/config/rewriter/wknd-rewriter"
     ```
3. **Verification:**
   - Navigated to an article containing an external link:
     `https://<author>/content/wknd/language-masters/en/magazine/guide-la-skateparks.html?wcmmode=disabled`
   - Verified the rendered HTML DOM:
     ```html
     <a class="cmp-title__link" href="https://www.google.com/travel/flights" target="_blank" rel="noopener noreferrer">...</a>
     ```

---

## 5. Architectural Deep-Dive: AEMaaCS vs AEM 6.5

### A. How Sling Rewriter Discovers Configurations (Apache Sling Source Code)
In [`org.apache.sling.rewriter.impl.ProcessorManagerImpl`](https://github.com/apache/sling-org-apache-sling-rewriter/blob/master/src/main/java/org/apache/sling/rewriter/impl/ProcessorManagerImpl.java):
```java
static final String CONFIG_REL_PATH = "config/rewriter";
static final String CONFIG_PATH = "/" + CONFIG_REL_PATH;

private String[] initProcessors() throws LoginException {
    try (final ResourceResolver resolver = this.createResourceResolver()) {
        for (final String path : resolver.getSearchPath()) { // ["/apps/", "/libs/"]
            final Resource spResource = resolver.getResource(path.substring(0, path.length() - 1));
            if (spResource != null) {
                final Iterator<Resource> spIter = spResource.listChildren();
                while (spIter.hasNext()) {
                    final Resource appResource = spIter.next();
                    // Resolves /apps/<app>/config/rewriter
                    final Resource parentResource = resolver.getResource(appResource.getPath() + CONFIG_PATH);
                    if (parentResource != null) {
                        for (Resource configResource : parentResource.getChildren()) {
                            // Reads rewriter pipeline configuration
                            addProcessor(configResource.getName(), configResource.getPath(), ...);
                        }
                    }
                }
            }
        }
    }
}
```

Key takeaways:
- **Hardcoded Path:** Sling Rewriter searches strictly inside `/apps/*/config/rewriter` and `/libs/*/config/rewriter`.
- **No Run-Mode Filtering:** Sling Rewriter does not inspect run modes in paths (e.g., `config.publish/rewriter` or `config.author/rewriter` are ignored).
- **Supported Node Properties:** Evaluated properties in `ProcessorConfigurationImpl` are limited to `contentTypes`, `enabled`, `generatorType`, `order`, `paths`, `resourceTypes`, `selectors`, `extensions`, `serializerType`, and `transformerTypes`. There is no `runModes` property.

### B. Why AEM 6.5 `ui.config/author/.../bundle-content` Worked
In AEM 6.5, projects commonly placed pipeline files in:
`ui.config/author/src/main/bundle-content/rewriter/xyz_transformer.json`

This worked because:
1. It was packaged into an **OSGi bundle** using `bnd-maven-plugin` or `maven-bundle-plugin`.
2. The bundle manifest contained a `Sling-Initial-Content` instruction that extracted the JSON into `/apps/<app>/config/rewriter` at bundle activation time.
3. The bundle was deployed only to Author (e.g., via `install.author` or an author-targeted Maven profile).

### C. Why That Pattern Fails in AEM as a Cloud Service
1. **Immutable Repository (`/apps` is read-only at runtime):**
   In AEMaaCS, `/apps` and `/libs` are mounted as immutable filesystems. Runtime write operations (including `Sling-Initial-Content` extracting content to `/apps`) fail. All JCR nodes in `/apps` must be deployed at build time via `ui.apps`.
2. **`ui.config` is a Container Package:**
   In AEMaaCS, `ui.config` has `packageType=container`. Container packages are restricted to OSGi configurations (`.cfg.json`) and embedded packages/bundles; they cannot deploy JCR content nodes.
3. **Single Build Image:**
   Cloud Manager deploys identical `ui.apps` artifacts to all tiers (author and publish). Separate author-only code packages are not supported.

---

## 6. How to Restrict External Link Transformation to Publish

Since the JCR pipeline node `/apps/wknd/config/rewriter/wknd-rewriter` is deployed to both Author and Publish, and unregistering the transformer on Author would trigger a pipeline setup error (500), the tier-restriction is managed **inside the transformer code via pass-through**.

### Recommended Pattern: OSGi Configuration with Tier Runmode

1. **Add OSGi Configuration to `ExternalLinkTransformerFactory`:**
   ```java
   @ObjectClassDefinition(name = "WKND External Link Transformer")
   public @interface Config {
       @AttributeDefinition(name = "Enabled", description = "Enable external link rewriting")
       boolean enabled() default false;
   }
   ```
2. **Provide Publish Configuration in `ui.config`:**
   File: `ui.config/src/main/content/jcr_root/apps/wknd/osgiconfig/config.publish/com.adobe.aem.guides.wknd.core.rewriter.ExternalLinkTransformerFactory.cfg.json`
   ```json
   {
     "enabled": true
   }
   ```
3. **Handle Pass-Through in `ExternalLinkTransformer`:**
   ```java
   @Override
   public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
       if (!enabled) {
           super.startElement(uri, localName, qName, attributes);
           return;
       }
       // ... external link logic ...
   }
   ```

### Benefits
- **Zero 500 Errors:** Pipeline initializes cleanly across all instances because the component is always present.
- **Authoring Safety:** Authors editing pages or working in Touch UI consoles are not impacted by unwanted link modifications.
- **Configurability:** Environment behavior is driven through standard AEM Cloud Service `osgiconfig` run-mode folders.
