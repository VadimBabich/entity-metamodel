package com.example.diagnostics;

import com.example.diagnostics.money.Money;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/** A field type living in a sub-package: the qualified name shares the entity's package prefix. */
@Table("sub_package_holders")
public class SubPackageHolder {

  @Id
  @Column("holder_id")
  Long id;

  @Column("amount")
  Money amount;
}
