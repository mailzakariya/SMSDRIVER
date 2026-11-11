/*
 * Copyright (c) Tecum. All Rights Reserved.
 */
package com.tecum.sms.core;

import java.util.Locale;

/**
 * State machine for one notification rule.
 *
 * NORMAL --condition true--> PENDING --held for activeDelay--> ACTIVE (send alert)
 * ACTIVE --repeat interval elapsed--> ACTIVE (send repeat, up to maxRepeats)
 * ACTIVE --condition false (with deadband)--> NORMAL (send return-to-normal if enabled)
 *
 * The ANY_CHANGE condition is an event: every change of the displayed value
 * sends a message, rate limited by the repeat interval.
 */
public final class RuleEvaluator
{
  /** Condition ordinals; must match BSmsCondition. */
  public static final int VALUE_EQUALS = 0;
  public static final int VALUE_NOT_EQUALS = 1;
  public static final int GREATER_THAN = 2;
  public static final int LESS_THAN = 3;
  public static final int GREATER_OR_EQUAL = 4;
  public static final int LESS_OR_EQUAL = 5;
  public static final int OUTSIDE_RANGE = 6;
  public static final int INSIDE_RANGE = 7;
  public static final int IS_TRUE = 8;
  public static final int IS_FALSE = 9;
  public static final int ANY_CHANGE = 10;
  public static final int STATUS_ALARM = 11;
  public static final int STATUS_FAULT = 12;
  public static final int STATUS_DOWN = 13;
  public static final int STATUS_OVERRIDDEN = 14;
  public static final int STATUS_NOT_OK = 15;

  public enum Decision { NONE, ALERT, REPEAT, NORMAL, CHANGE }
  public enum State { NORMAL, PENDING, ACTIVE }

  public static final class Settings
  {
    public int condition = GREATER_THAN;
    /** For equals / not-equals: one or more values separated by ',' or '|'. */
    public String compareValue = "";
    public double limit;
    public double lowLimit;
    public double highLimit = 100;
    public double deadband;
    public long activeDelayMs;
    public long repeatIntervalMs;
    public int maxRepeats;
  }

  private State state = State.NORMAL;
  private long pendingSince;
  private long lastSentAt;
  private int repeats;
  private String lastDisplay;
  private long lastChangeSentAt = Long.MIN_VALUE / 2;

  public State getState() { return state; }
  public boolean isActive() { return state == State.ACTIVE; }

  public void reset()
  {
    state = State.NORMAL;
    repeats = 0;
    lastDisplay = null;
  }

  /**
   * Evaluate a sample at time now (ms). Returns what, if anything, to send.
   * A null sample (source not resolved) holds the current state.
   */
  public synchronized Decision update(Settings s, PointSample sample, long now)
  {
    if (sample == null) return Decision.NONE;

    if (s.condition == ANY_CHANGE)
    {
      if (!sample.isValueReliable()) return Decision.NONE;
      String d = sample.display;
      boolean changed = lastDisplay != null && !lastDisplay.equals(d);
      lastDisplay = d;
      if (changed && now - lastChangeSentAt >= s.repeatIntervalMs)
      {
        lastChangeSentAt = now;
        return Decision.CHANGE;
      }
      return Decision.NONE;
    }

    Boolean cond = test(s, sample, state != State.NORMAL);
    if (cond == null) return Decision.NONE;     // value unreliable: hold state

    switch (state)
    {
      case NORMAL:
        if (!cond) return Decision.NONE;
        if (s.activeDelayMs <= 0) return activate(now);
        state = State.PENDING;
        pendingSince = now;
        return Decision.NONE;

      case PENDING:
        if (!cond) { state = State.NORMAL; return Decision.NONE; }
        if (now - pendingSince >= s.activeDelayMs) return activate(now);
        return Decision.NONE;

      case ACTIVE:
      default:
        if (!cond)
        {
          state = State.NORMAL;
          repeats = 0;
          return Decision.NORMAL;
        }
        if (s.repeatIntervalMs > 0 && (s.maxRepeats <= 0 || repeats < s.maxRepeats)
            && now - lastSentAt >= s.repeatIntervalMs)
        {
          repeats++;
          lastSentAt = now;
          return Decision.REPEAT;
        }
        return Decision.NONE;
    }
  }

  private Decision activate(long now)
  {
    state = State.ACTIVE;
    lastSentAt = now;
    repeats = 0;
    return Decision.ALERT;
  }

  /**
   * Evaluate the condition. Returns null when the value cannot be trusted
   * (for value based conditions). When currently active the deadband is
   * applied so the rule does not chatter around the limit.
   */
  public static Boolean test(Settings s, PointSample p, boolean active)
  {
    switch (s.condition)
    {
      case STATUS_ALARM: return p.alarm;
      case STATUS_FAULT: return p.fault;
      case STATUS_DOWN: return p.down;
      case STATUS_OVERRIDDEN: return p.overridden;
      case STATUS_NOT_OK: return p.alarm || p.fault || p.down || p.stale || p.isNull;
      default: break;
    }
    if (!p.isValueReliable()) return null;

    double db = Math.abs(s.deadband);
    double v = p.number;
    switch (s.condition)
    {
      case VALUE_EQUALS: return matches(p, s.compareValue);
      case VALUE_NOT_EQUALS: return !matches(p, s.compareValue);
      case GREATER_THAN: return numeric(p) ? (active ? v > s.limit - db : v > s.limit) : null;
      case GREATER_OR_EQUAL: return numeric(p) ? (active ? v >= s.limit - db : v >= s.limit) : null;
      case LESS_THAN: return numeric(p) ? (active ? v < s.limit + db : v < s.limit) : null;
      case LESS_OR_EQUAL: return numeric(p) ? (active ? v <= s.limit + db : v <= s.limit) : null;
      case OUTSIDE_RANGE:
        if (!numeric(p)) return null;
        return active ? (v > s.highLimit - db || v < s.lowLimit + db) : (v > s.highLimit || v < s.lowLimit);
      case INSIDE_RANGE:
        if (!numeric(p)) return null;
        return active ? (v >= s.lowLimit - db && v <= s.highLimit + db) : (v >= s.lowLimit && v <= s.highLimit);
      case IS_TRUE: return truth(p);
      case IS_FALSE: { Boolean t = truth(p); return t == null ? null : !t; }
      default: return null;
    }
  }

  private static boolean numeric(PointSample p)
  {
    return !Double.isNaN(p.number) && p.kind != PointSample.Kind.STRING;
  }

  private static Boolean truth(PointSample p)
  {
    switch (p.kind)
    {
      case BOOLEAN: return p.bool;
      case NUMERIC: case ENUM: return p.number != 0;
      case STRING: return parseBool(p.display);
      default: return null;
    }
  }

  /** True when the sample matches any of the comma or '|' separated values. */
  public static boolean matches(PointSample p, String compare)
  {
    if (compare == null) return false;
    for (String raw : compare.split("[,|]"))
    {
      String c = raw.trim();
      if (c.isEmpty()) continue;
      if (matchesOne(p, c)) return true;
    }
    return false;
  }

  private static boolean matchesOne(PointSample p, String c)
  {
    String lc = c.toLowerCase(Locale.ENGLISH);
    if (p.display != null && p.display.trim().equalsIgnoreCase(c)) return true;
    switch (p.kind)
    {
      case BOOLEAN:
      {
        Boolean b = parseBool(lc);
        return b != null && b == p.bool;
      }
      case ENUM:
        if (p.tag != null && p.tag.equalsIgnoreCase(c)) return true;
        try { return Integer.parseInt(c) == p.ordinal; }
        catch (NumberFormatException e) { return false; }
      case NUMERIC:
        try { return Math.abs(Double.parseDouble(c) - p.number) < 1e-6; }
        catch (NumberFormatException e) { return false; }
      default:
        return false;
    }
  }

  static Boolean parseBool(String s)
  {
    if (s == null) return null;
    switch (s.trim().toLowerCase(Locale.ENGLISH))
    {
      case "true": case "on": case "1": case "yes": case "active": case "open": case "alarm": return Boolean.TRUE;
      case "false": case "off": case "0": case "no": case "inactive": case "closed": case "normal": return Boolean.FALSE;
      default: return null;
    }
  }
}
