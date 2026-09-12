import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

@Table("default_packaged")
public class DefaultPackaged {

  @Id
  @Column("id")
  Long id;

  @Column("kind")
  Kind kind;

  public enum Kind {
    PLAIN
  }
}
