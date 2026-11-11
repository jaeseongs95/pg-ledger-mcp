package org.pgledger.application.history;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

/** One changed field of a history row: the value before and after, written as the JSON array {@code [before, after]}. */
@JsonFormat(shape = JsonFormat.Shape.ARRAY)
@JsonPropertyOrder({"before", "after"})
public record Change(String before, String after) {
}
