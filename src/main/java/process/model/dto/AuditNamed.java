package process.model.dto;

/**
 * A DTO that can show who made and last changed the thing it describes.
 *
 * Separate from the entity-side interface because several list endpoints build their DTOs from
 * a native query mapped by column position. Adding two columns to that SQL means renumbering
 * every index below them, which is exactly the kind of edit that silently shifts a field into
 * the wrong slot -- so the names are attached afterwards, by id, instead.
 */
public interface AuditNamed {

    Long auditKey();

    void setCreatedByName(String createdByName);

    void setUpdatedByName(String updatedByName);
}
