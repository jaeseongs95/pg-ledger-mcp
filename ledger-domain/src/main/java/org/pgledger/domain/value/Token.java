package org.pgledger.domain.value;

/** A closed-set constant that has a wire token (the value the external contract uses). */
public interface Token {

    String token();
}
