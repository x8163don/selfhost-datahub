package com.datahub.authentication;

import java.util.Objects;
import javax.annotation.Nonnull;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Represents a unique DataHub actor (i.e. principal). Defining characteristics of all DataHub
 * Actors includes a
 *
 * <p>a) Actor Type: A specific type of actor, e.g. CORP_USER or SERVICE_USER. b) Actor Id: A unique
 * id for the actor.
 *
 * <p>These pieces of information are in turn used to construct an Entity Urn, which can be used as
 * a primary key to fetch and update specific information about the actor.
 */
@Getter
@AllArgsConstructor
public class Actor {

  /** The {@link ActorType} associated with a DataHub actor. */
  private final ActorType type;

  /** The unique id associated with a DataHub actor. */
  private final String id;

  /**
   * @return the string-ified urn rendering of the authenticated actor.
   */
  @Nonnull
  public String toUrnStr() {
    if (Objects.requireNonNull(getType()) == ActorType.USER) {
      return String.format("urn:li:corpuser:%s", getId());
    }
    throw new IllegalArgumentException(
        String.format("Unrecognized ActorType %s provided", getType()));
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;

    Actor actor = (Actor) o;

    if (type != actor.type) return false;
    return id.equals(actor.id);
  }

  @Override
  public int hashCode() {
    int result = type.hashCode();
    result = 31 * result + id.hashCode();
    return result;
  }
}
