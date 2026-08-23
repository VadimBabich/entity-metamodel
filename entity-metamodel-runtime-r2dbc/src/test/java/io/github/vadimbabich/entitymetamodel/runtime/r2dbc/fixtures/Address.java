package io.github.vadimbabich.entitymetamodel.runtime.r2dbc.fixtures;

/** Embedded value: its properties are columns of the embedding table, not of a table of its own. */
public class Address {

  public String street;

  public String city;
}
