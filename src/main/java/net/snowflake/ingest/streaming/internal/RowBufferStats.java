/*
 * Copyright (c) 2021 Snowflake Computing Inc. All rights reserved.
 */

package net.snowflake.ingest.streaming.internal;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Keeps track of the active EP stats, used to generate a file EP info */
class RowBufferStats {
  private String currentMinStrValue;
  private String currentMaxStrValue;
  private BigInteger currentMinIntValue;
  private BigInteger currentMaxIntValue;
  private Double currentMinRealValue;
  private Double currentMaxRealValue;
  private long currentNullCount;
  // for binary or string columns
  private long currentMaxLength;

  /** Creates empty stats */
  RowBufferStats() {
    this.currentMaxStrValue = null;
    this.currentMinStrValue = null;
    this.currentMaxIntValue = null;
    this.currentMinIntValue = null;
    this.currentMaxRealValue = null;
    this.currentMinRealValue = null;
    this.currentNullCount = 0;
    this.currentMaxLength = 0;
  }

  // TODO performance test this vs in place update
  public static RowBufferStats getCombinedStats(RowBufferStats left, RowBufferStats right) {
    RowBufferStats combined = new RowBufferStats();

    if (left.currentMinIntValue != null) {
      combined.addIntValue(left.currentMinIntValue);
      combined.addIntValue(left.currentMaxIntValue);
    }

    if (right.currentMinIntValue != null) {
      combined.addIntValue(right.currentMinIntValue);
      combined.addIntValue(right.currentMaxIntValue);
    }

    if (left.currentMinStrValue != null) {
      combined.addStrValue(left.currentMinStrValue);
      combined.addStrValue(left.currentMaxStrValue);
    }

    if (right.currentMinStrValue != null) {
      combined.addStrValue(right.currentMinStrValue);
      combined.addStrValue(right.currentMaxStrValue);
    }

    if (left.currentMinRealValue != null) {
      combined.addRealValue(left.currentMinRealValue);
      combined.addRealValue(left.currentMaxRealValue);
    }

    if (right.currentMinRealValue != null) {
      combined.addRealValue(right.currentMinRealValue);
      combined.addRealValue(right.currentMaxRealValue);
    }

    combined.currentNullCount = left.currentNullCount + right.currentNullCount;

    return combined;
  }

  void addStrValue(String value) {

    // Snowflake compares strings in UTF-8 encoding, not Java's default UTF-16
    byte[] currentMinStringBytes =
        currentMinStrValue != null ? currentMinStrValue.getBytes(StandardCharsets.UTF_8) : null;
    byte[] currentMaxStringBytes =
        currentMaxStrValue != null ? currentMaxStrValue.getBytes(StandardCharsets.UTF_8) : null;
    byte[] valueBytes = value != null ? value.getBytes(StandardCharsets.UTF_8) : null;

    // Check if new min string
    if (currentMinStrValue == null) {
      currentMinStrValue = value;
    } else if (Arrays.compare(currentMinStringBytes, valueBytes) > 0) {
      currentMinStrValue = value;
    }

    // Check if new max string
    if (this.currentMaxStrValue == null) {
      this.currentMaxStrValue = value;
    } else if (Arrays.compare(currentMaxStringBytes, valueBytes) < 0) {
      this.currentMaxStrValue = value;
    }
  }

  String getCurrentMinStrValue() {
    return currentMinStrValue;
  }

  String getCurrentMaxStrValue() {
    return currentMaxStrValue;
  }

  void addIntValue(BigInteger value) {

    // Set new min value
    if (this.currentMinIntValue == null) {
      this.currentMinIntValue = value;
    } else if (this.currentMinIntValue.compareTo(value) > 0) {
      this.currentMinIntValue = value;
    }

    // Set new max value
    if (this.currentMaxIntValue == null) {
      this.currentMaxIntValue = value;
    } else if (this.currentMaxIntValue.compareTo(value) < 0) {
      this.currentMaxIntValue = value;
    }
  }

  BigInteger getCurrentMinIntValue() {
    return currentMinIntValue;
  }

  BigInteger getCurrentMaxIntValue() {
    return currentMaxIntValue;
  }

  void addRealValue(Double value) {

    // Set new min value
    if (this.currentMinRealValue == null) {
      this.currentMinRealValue = value;
    } else if (this.currentMinRealValue.compareTo(value) > 0) {
      this.currentMinRealValue = value;
    }

    // Set new max value
    if (this.currentMaxRealValue == null) {
      this.currentMaxRealValue = value;
    } else if (this.currentMaxRealValue.compareTo(value) < 0) {
      this.currentMaxRealValue = value;
    }
  }

  Double getCurrentMinRealValue() {
    return currentMinRealValue;
  }

  Double getCurrentMaxRealValue() {
    return currentMaxRealValue;
  }

  void incCurrentNullCount() {
    this.currentNullCount += 1;
  }

  long getCurrentNullCount() {
    return currentNullCount;
  }

  void setCurrentMaxLength(long currentMaxLength) {
    if (currentMaxLength > this.currentMaxLength) {
      this.currentMaxLength = currentMaxLength;
    }
  }

  long getCurrentMaxLength() {
    return currentMaxLength;
  }

  /**
   * Returns the number of distinct values (NDV). A value of -1 means the number is unknown
   *
   * @return -1 indicating the NDV is unknown
   */
  long getDistinctValues() {
    return -1;
  }
}
