package com.adobe.aem.guides.wknd.core.mcp.models;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.models.annotations.Model;

@Model(adaptables = Resource.class)
public class Violation {
    
    private String sourcePath;
    
    private String referencedPath;
    
    private String type;
    
    private String severity;
    
    private String recommendation;

    public Violation() {}

    public Violation(String sourcePath, String referencedPath, String type, String severity, String recommendation) {
        this.sourcePath = sourcePath;
        this.referencedPath = referencedPath;
        this.type = type;
        this.severity = severity;
        this.recommendation = recommendation;
    }

    public String getSourcePath() { return sourcePath; }
    public void setSourcePath(String sourcePath) { this.sourcePath = sourcePath; }

    public String getReferencedPath() { return referencedPath; }
    public void setReferencedPath(String referencedPath) { this.referencedPath = referencedPath; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getSeverity() { return severity; }
    public void setSeverity(String severity) { this.severity = severity; }

    public String getRecommendation() { return recommendation; }
    public void setRecommendation(String recommendation) { this.recommendation = recommendation; }
}
