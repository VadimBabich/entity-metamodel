package com.example.contract;

import java.time.Instant;
import java.time.LocalDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Typing witnesses: primitives box in the ref type argument while the raw-type token stays
 * primitive, and enum and temporal types carry as declared. Member ordering places
 * {@code captured} before {@code capturedAt} — case-insensitive, shorter prefix first.
 */
@Table("payments")
public record Payment(
    @Id
    @Column("payment_id")
    long id,

    @Column("retry_count")
    int retryCount,

    @Column("captured")
    boolean captured,

    @Column("captured_at")
    Instant capturedAt,

    @Column("settled_on")
    LocalDate settledOn,

    @Column("status")
    PaymentStatus status
) {

}
