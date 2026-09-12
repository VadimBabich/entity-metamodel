package com.example.diagnostics;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("table_interface_owners")
public interface TableInterfaceOwner {

  @Table("owned_by_interface")
  class Owned {

    @Id
    @Column("id")
    Long id;
  }
}
