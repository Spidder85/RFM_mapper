package org.ikozmin.rfm.client;

import org.ikozmin.rfm.model.CatalogInfo;
import org.ikozmin.rfm.model.CatalogType;
import org.ikozmin.rfm.model.DownloadedFile;

import java.nio.file.Path;

public interface RfmClient {
    void authenticate(String userName, String password);

    CatalogInfo getCatalog(CatalogType catalogType);

    DownloadedFile downloadFile(CatalogType catalogType, String id, Path tempFile);
}
