/*
 * Copyright (c) Tecum. All Rights Reserved.
 */
package com.tecum.sms.core;

/**
 * A snapshot of a point's value and status, independent of Niagara types so
 * the rule logic can be unit tested.
 */
public final class PointSample
{
  public enum Kind { NUMERIC, BOOLEAN, ENUM, STRING, NONE }

  public Kind kind = Kind.NONE;
  public double number = Double.NaN;
  public boolean bool;
  public int ordinal = -1;
  /** Enum tag (e.g. "occupied"); null when not an enum. */
  public String tag;
  /** Display text using the point facets, e.g. "21.5 °C", "On", "Occupied". */
  public String display = "";
  /** Status text, e.g. "{ok}" or "{alarm,unackedAlarm}". */
  public String statusText = "{ok}";

  public boolean alarm, fault, down, overridden, stale, isNull, disabled;

  /** The value is trustworthy (not offline / fault / null / disabled). */
  public boolean isValueReliable()
  {
    return kind != Kind.NONE && !down && !fault && !isNull && !disabled;
  }

  public static PointSample numeric(double v)
  {
    PointSample s = new PointSample();
    s.kind = Kind.NUMERIC; s.number = v; s.display = trim(v);
    return s;
  }

  public static PointSample bool(boolean v)
  {
    PointSample s = new PointSample();
    s.kind = Kind.BOOLEAN; s.bool = v; s.number = v ? 1 : 0; s.display = v ? "true" : "false";
    return s;
  }

  public static PointSample enumeration(int ordinal, String tag, String display)
  {
    PointSample s = new PointSample();
    s.kind = Kind.ENUM; s.ordinal = ordinal; s.tag = tag; s.number = ordinal; s.display = display;
    return s;
  }

  public static PointSample string(String v)
  {
    PointSample s = new PointSample();
    s.kind = Kind.STRING; s.display = v == null ? "" : v;
    return s;
  }

  static String trim(double v)
  {
    if (v == Math.rint(v) && !Double.isInfinite(v) && Math.abs(v) < 1e15) return Long.toString((long) v);
    return Double.toString(v);
  }
}
