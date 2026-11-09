/*
 * Copyright (c) Ignitechs. All Rights Reserved.
 */
package com.ignitechs.sms.core;

/** Phone number normalisation and comparison. */
public final class PhoneNumbers
{
  private PhoneNumbers() {}

  public static String digitsOnly(String s)
  {
    if (s == null) return "";
    StringBuilder sb = new StringBuilder(s.length());
    for (int i = 0; i < s.length(); i++)
    {
      char c = s.charAt(i);
      if (c >= '0' && c <= '9') sb.append(c);
    }
    return sb.toString();
  }

  /**
   * Normalise to "+&lt;digits&gt;" when the number is international ("+..." or
   * "00..."), otherwise return the bare digits (national format).
   * Spaces, dashes, dots and brackets are removed.
   */
  public static String normalize(String s)
  {
    if (s == null) return "";
    String t = s.trim();
    String digits = digitsOnly(t);
    if (digits.isEmpty()) return "";
    if (t.startsWith("+")) return "+" + digits;
    if (digits.startsWith("00") && digits.length() > 4) return "+" + digits.substring(2);
    return digits;
  }

  /** A usable number has 3 to 15 digits (E.164 maximum is 15). */
  public static boolean isValid(String s)
  {
    String t = s == null ? "" : s.trim();
    for (int i = 0; i < t.length(); i++)
    {
      char c = t.charAt(i);
      boolean ok = (c >= '0' && c <= '9') || c == '+' || c == ' ' || c == '-' || c == '(' || c == ')' || c == '.';
      if (!ok) return false;
    }
    int n = digitsOnly(normalize(t)).length();
    return n >= 3 && n <= 15;
  }

  /**
   * True when two numbers refer to the same subscriber. Exact match on the
   * normalised form, or (for numbers reported by a modem in national format)
   * a match on the last 9 digits.
   */
  public static boolean sameNumber(String a, String b)
  {
    String na = normalize(a), nb = normalize(b);
    if (na.isEmpty() || nb.isEmpty()) return false;
    if (na.equals(nb)) return true;
    String da = digitsOnly(na), db = digitsOnly(nb);
    if (da.length() < 9 || db.length() < 9) return false;
    return da.substring(da.length() - 9).equals(db.substring(db.length() - 9));
  }
}
