package com.example.contract;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Nested-{@code @Table} witness — a common shape in practice, and the reason nesting is mirrored
 * rather than flattened: the nested view's metamodel is emitted as a nested class, each level named
 * by the same rule. Also the fixture for regenerating after a change to the nested type.
 */
@Table("vendors")
public class Vendor {

  @Id
  @Column("vendor_id")
  Long id;

  @Column("display_name")
  String displayName;

  @Table("vendor_permission_views")
  public record VendorPermissionView(
      @Id
      @Column("view_id")
      Long id,

      @Column("permission_key")
      String permissionKey,

      @Column("vendor_id")
      Long vendorId
  ) {

  }
}
