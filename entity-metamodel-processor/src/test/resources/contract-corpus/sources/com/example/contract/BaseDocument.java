package com.example.contract;

import org.springframework.data.annotation.Version;
import org.springframework.data.relational.core.mapping.Column;

/**
 * Non-{@code @Table} superclass: gets no metamodel of its own; its persistent fields flatten into
 * the concrete entity and are re-anchored there. Also the fixture for regenerating a child after a
 * change to a supertype that carries no annotation of its own.
 */
public class BaseDocument {

  @Column("created_by")
  String createdBy;

  @Version
  @Column("revision")
  Long revision;
}
