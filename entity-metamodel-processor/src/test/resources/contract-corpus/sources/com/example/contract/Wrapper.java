package com.example.contract;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * The non-{@code @Table}-encloser edge, pinned by this fixture: the enclosing class is not an
 * entity, so it gets no metamodel and there is nothing for the nested one to live inside. The
 * nested {@code @Table} type gets a TOP-LEVEL metamodel instead, carrying the enclosing simple name
 * as prefix context — {@code Wrapper_Attachment__}.
 */
public class Wrapper {

  @Table("attachments")
  public record Attachment(
      @Id
      @Column("attachment_id")
      Long id,

      @Column("file_name")
      String fileName
  ) {

  }
}
