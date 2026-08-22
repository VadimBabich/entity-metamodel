package com.example.contract;

import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Generic-expressibility witnesses: exact declared types carry into {@code PropertyRef<E,T>} —
 * parameterized, array and bounded-wildcard forms, each spelled out in the generated initializer.
 */
@Table("inventories")
public record Inventory(
    @Id
    @Column("inventory_id")
    Long id,

    @Column("tags")
    List<String> tags,

    @Column("scores")
    int[] scores,

    @Column("limits")
    List<? extends Number> limits
) {

}
