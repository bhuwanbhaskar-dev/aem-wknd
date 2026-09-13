package com.adobe.aem.guides.wknd.core.services;

import java.io.StringReader;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.apache.jackrabbit.vault.fs.api.PathFilterSet;
import org.apache.jackrabbit.vault.fs.config.DefaultWorkspaceFilter;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

/** Bounded filter snapshot parsed with FileVault's native rule implementation. */
public final class PackageAnalysisRequest {
    public static final int MAX_REQUEST_CHARS = 65536;
    public final DefaultWorkspaceFilter filter;
    public final String filterXml;
    public final double measuredMbps;

    private PackageAnalysisRequest(DefaultWorkspaceFilter filter, String xml, double speed) {
        this.filter = filter;
        this.filterXml = xml;
        this.measuredMbps = speed;
    }

    public static PackageAnalysisRequest parse(String json, List<String> allowedRoots) {
        try {
            if (json == null || json.length() > MAX_REQUEST_CHARS) throw new IllegalArgumentException();
            JsonObject body = JsonParser.parseString(json).getAsJsonObject();
            if (!body.keySet().equals(Set.of("filterXml", "measuredMbps"))) throw new IllegalArgumentException();
            if (!body.getAsJsonPrimitive("filterXml").isString()
                    || !body.getAsJsonPrimitive("measuredMbps").isNumber()) throw new IllegalArgumentException();
            String xml = body.get("filterXml").getAsString();
            double speed = body.get("measuredMbps").getAsDouble();
            if (!Double.isFinite(speed) || speed <= 0 || speed > 1_000_000) throw new IllegalArgumentException();

            DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler());
            Element root = builder.parse(new InputSource(new StringReader(xml))).getDocumentElement();
            if (!"workspaceFilter".equals(root.getTagName()) || !"1.0".equals(root.getAttribute("version"))) {
                throw new IllegalArgumentException();
            }
            validateAttributes(root, Set.of("version", "xmlns:xsi", "xsi:noNamespaceSchemaLocation"));
            int roots = 0;
            int rules = 0;
            for (Node child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (!(child instanceof Element)) continue;
                Element entry = (Element) child;
                if (!"filter".equals(entry.getTagName()) || ++roots > 32) throw new IllegalArgumentException();
                validateAttributes(entry, Set.of("root", "mode"));
                String path = entry.getAttribute("root");
                validatePath(path);
                if (allowedRoots.stream().noneMatch(allowed -> isWithin(path, allowed))) throw new IllegalArgumentException();
                for (Node rule = entry.getFirstChild(); rule != null; rule = rule.getNextSibling()) {
                    if (!(rule instanceof Element)) continue;
                    Element element = (Element) rule;
                    if (!Set.of("include", "exclude").contains(element.getTagName()) || ++rules > 128) {
                        throw new IllegalArgumentException();
                    }
                    validateAttributes(element, Set.of("pattern", "matchProperties"));
                    if (!element.hasAttribute("pattern") || element.getAttribute("pattern").length() > 1024
                            || element.getElementsByTagName("*").getLength() != 0) throw new IllegalArgumentException();
                    if (element.hasAttribute("matchProperties")
                            && !Set.of("true", "false").contains(element.getAttribute("matchProperties"))) {
                        throw new IllegalArgumentException();
                    }
                }
            }
            if (roots == 0) throw new IllegalArgumentException();
            DefaultWorkspaceFilter filter = new DefaultWorkspaceFilter();
            filter.load(root);
            // Reject ambiguity rather than silently modifying a package definition.
            Set<String> seen = new HashSet<>();
            for (PathFilterSet set : filter.getFilterSets()) {
                for (String previous : seen) {
                    if (isWithin(set.getRoot(), previous) || isWithin(previous, set.getRoot())) {
                        throw new IllegalArgumentException();
                    }
                }
                seen.add(set.getRoot());
            }
            return new PackageAnalysisRequest(filter, xml, speed);
        } catch (Exception e) {
            throw new PackageAnalyzerService.ApiException(400,
                    "Invalid request. Supply filterXml (workspaceFilter 1.0, disjoint permitted roots, include/exclude rules) "
                    + "and a positive measuredMbps. DTDs, external entities and unsupported attributes are rejected.");
        }
    }

    private static void validateAttributes(Element element, Set<String> names) {
        for (int i = 0; i < element.getAttributes().getLength(); i++) {
            if (!names.contains(element.getAttributes().item(i).getNodeName())) throw new IllegalArgumentException();
        }
    }

    public static boolean isWithin(String path, String root) {
        return path.equals(root) || path.startsWith(root + "/");
    }

    public static void validatePath(String path) {
        if (path == null || path.length() > 2048 || path.endsWith("/") || path.contains("//")
                || path.contains("\\") || path.chars().anyMatch(Character::isISOControl)
                || !(path.startsWith("/content/") || path.startsWith("/conf/"))) throw new IllegalArgumentException();
        for (String part : path.split("/")) {
            if (".".equals(part) || "..".equals(part) || part.contains("*") || part.contains("[")) {
                throw new IllegalArgumentException();
            }
        }
    }
}
