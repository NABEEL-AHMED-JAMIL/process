package process.model.enums;

/**
 * Who reviewed a result (MIG-225): INTERNAL, our reviewer (the default required review, MIG-221), or CUSTOMER.
 * Stored by name in result_review.party; ck_result_review_party_enum lists the same values
 * (RoundTripExecutionSchemaPostgresTest pins both).
 */
public enum ReviewParty {
    INTERNAL, CUSTOMER
}
