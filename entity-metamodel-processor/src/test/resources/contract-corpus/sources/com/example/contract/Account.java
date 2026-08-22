package com.example.contract;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Inclusion witness: {@code nickname} is persistent without {@code @Column} and is included —
 * {@code @Column} renames, it does not decide membership; {@code draftNote} is {@code @Transient}
 * despite carrying {@code @Column} and is excluded, because transient wins. {@code sourceURLPath}
 * exercises the acronym boundary of the member-name transformation.
 */
@Table("accounts")
public class Account {

  @Id
  @Column("account_id")
  Long id;

  @Column("owner_email")
  String ownerEmail;

  String nickname;

  @Transient
  @Column("draft_note")
  String draftNote;

  @Column("source_url_path")
  String sourceURLPath;
}
