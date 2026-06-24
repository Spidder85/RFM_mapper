package org.ikozmin.rfm.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class CatalogInfo {
    private String date;
    private Boolean isActive;
    private Object idRecStatus;
    private String idXml;

    private Long idTerroristCatalog;
    private String terroristCatalogNumber;
    private String terroristCatalogDate;
    private String idDbf;
    private String idDoc;

    public String getDate() {
        return date;
    }

    public Boolean getIsActive() {
        return isActive;
    }

    public Object getIdRecStatus() {
        return idRecStatus;
    }

    public String getIdXml() {
        return idXml;
    }

    public Long getIdTerroristCatalog() {
        return idTerroristCatalog;
    }

    public String getTerroristCatalogNumber() {
        return terroristCatalogNumber;
    }

    public String getTerroristCatalogDate() {
        return terroristCatalogDate;
    }

    public String getIdDbf() {
        return idDbf;
    }

    public String getIdDoc() {
        return idDoc;
    }

    public String effectiveDate() {
        if (date != null && !date.trim().isEmpty()) {
            return date;
        }

        return terroristCatalogDate;
    }

    public String requireIdXml() {
        if (idXml == null || idXml.trim().isEmpty()) {
            throw new IllegalStateException("Catalog response does not contain idXml");
        }

        return idXml;
    }

    public void setIdXml(String idXml) {
        this.idXml = idXml;
    }

    public void setIdDbf(String idDbf) {
        this.idDbf = idDbf;
    }

    public void setIdDoc(String idDoc) {
        this.idDoc = idDoc;
    }
}
