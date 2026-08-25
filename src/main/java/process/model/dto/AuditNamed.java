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

    /**
     * The author's id as well as their name.
     *
     * The name alone cannot answer "is this mine?" -- two people can share one, and a filter
     * that matches on display text would quietly include somebody else's work.
     */
    void setCreatedBy(Long createdBy);
}
