/*
 * Copyright (c) Ignitechs. All Rights Reserved.
 */
package com.ignitechs.sms.core;

/** Upper-case hex helpers. */
public final class Hex
{
  private static final char[] DIGITS = "0123456789ABCDEF".toCharArray();

  private Hex() {}

  public static String encode(byte[] b)
  {
    char[] c = new char[b.length * 2];
    for (int i = 0; i < b.length; i++)
    {
      c[i * 2] = DIGITS[(b[i] >> 4) & 0x0F];
      c[i * 2 + 1] = DIGITS[b[i] & 0x0F];
    }
    return new String(c);
  }

  public static byte[] decode(String s)
  {
    if ((s.length() & 1) != 0) throw new IllegalArgumentException("Odd hex length");
    byte[] b = new byte[s.length() / 2];
    for (int i = 0; i < b.length; i++)
      b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
    return b;
  }
}
