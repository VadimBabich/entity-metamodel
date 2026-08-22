package io.github.vadimbabich.entitymetamodel.runtime.fixtures;

import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Relationship fixture: neither property is a column of this table. {@code @MappedCollection} is
 * optional, which is why neither annotation nor arity can be the test.
 */
@Table("parents")
public class Parent {

  @Id
  @Column("parent_id")
  Long id;

  List<Child> children;

  Child soleChild;
}
