package auracore.key49.storage;

/**
 * Tipo de artefacto almacenado en MinIO para un comprobante electrónico.
 * Cada tipo corresponde a una columna de path en la tabla documents.
 */
public enum DocumentArtifact {

    UNSIGNED_XML("unsigned.xml", "application/xml"),
    SIGNED_XML("signed.xml", "application/xml"),
    AUTHORIZED_XML("authorized.xml", "application/xml"),
    RIDE("ride.pdf", "application/pdf");

    private final String filename;
    private final String contentType;

    DocumentArtifact(String filename, String contentType) {
        this.filename = filename;
        this.contentType = contentType;
    }

    public String filename() {
        return filename;
    }

    public String contentType() {
        return contentType;
    }
}
