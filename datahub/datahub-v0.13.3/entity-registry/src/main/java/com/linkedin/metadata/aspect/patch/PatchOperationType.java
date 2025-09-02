package com.linkedin.metadata.aspect.patch;

import lombok.Getter;

public enum PatchOperationType {
  ADD("add"),
  REMOVE("remove");

  @Getter private final String value;

  PatchOperationType(String value) {
    this.value = value;
  }
}
