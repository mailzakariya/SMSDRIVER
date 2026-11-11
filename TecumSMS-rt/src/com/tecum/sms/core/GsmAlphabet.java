/*
 * Copyright (c) Tecum. All Rights Reserved.
 */
package com.tecum.sms.core;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * GSM 03.38 default alphabet support: detection, septet encoding and
 * splitting of text into SMS-sized segments (GSM-7 or UCS-2).
 */
public final class GsmAlphabet
{
  private GsmAlphabet() {}

  /** Index = septet value. Index 27 (0x1B) is the escape to the extension table. */
  private static final String BASIC =
    "@£$¥èéùìòÇ\nØø\rÅå" +
    "Δ_ΦΓΛΩΠΨΣΘΞ\u001bÆæßÉ" +
    " !\"#¤%&'()*+,-./0123456789:;<=>?" +
    "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§" +
    "¿abcdefghijklmnopqrstuvwxyzäöñüà";

  /** Extension table characters and their septet codes (preceded by ESC). */
  private static final char[] EXT_CHARS = { '\f', '^', '{', '}', '\\', '[', '~', ']', '|', '€' };
  private static final int[]  EXT_CODES = { 0x0A, 0x14, 0x28, 0x29, 0x2F, 0x3C, 0x3D, 0x3E, 0x40, 0x65 };

  public static final int ESC = 0x1B;

  /** Max septets in a single GSM-7 message, and per part of a concatenated message. */
  public static final int GSM7_SINGLE = 160;
  public static final int GSM7_PART = 153;
  /** Max UTF-16 code units in a single UCS-2 message, and per part. */
  public static final int UCS2_SINGLE = 70;
  public static final int UCS2_PART = 67;

  private static int basicIndex(char c)
  {
    if (c == '\u001b') return -1;
    return BASIC.indexOf(c);
  }

  private static int extIndex(char c)
  {
    for (int i = 0; i < EXT_CHARS.length; i++)
      if (EXT_CHARS[i] == c) return EXT_CODES[i];
    return -1;
  }

  /** True when every character can be sent using the GSM-7 alphabet. */
  public static boolean isGsm7(String text)
  {
    for (int i = 0; i < text.length(); i++)
    {
      char c = text.charAt(i);
      if (basicIndex(c) < 0 && extIndex(c) < 0) return false;
    }
    return true;
  }

  /** Number of septets one character occupies (1, or 2 for extension chars). */
  private static int septetsFor(char c)
  {
    return basicIndex(c) >= 0 ? 1 : 2;
  }

  /** Encode text as unpacked septets (one byte per septet). Caller must check {@link #isGsm7}. */
  public static byte[] toSeptets(String text)
  {
    ByteArrayOutputStream out = new ByteArrayOutputStream(text.length() + 8);
    for (int i = 0; i < text.length(); i++)
    {
      char c = text.charAt(i);
      int b = basicIndex(c);
      if (b >= 0) { out.write(b); continue; }
      int e = extIndex(c);
      if (e < 0) throw new IllegalArgumentException("Character not in GSM-7 alphabet: U+" + Integer.toHexString(c));
      out.write(ESC);
      out.write(e);
    }
    return out.toByteArray();
  }

  /** Decode unpacked septets back to text. */
  public static String fromSeptets(byte[] septets)
  {
    StringBuilder sb = new StringBuilder(septets.length);
    for (int i = 0; i < septets.length; i++)
    {
      int s = septets[i] & 0x7F;
      if (s == ESC && i + 1 < septets.length)
      {
        int code = septets[++i] & 0x7F;
        char ch = ' ';
        for (int k = 0; k < EXT_CODES.length; k++)
          if (EXT_CODES[k] == code) ch = EXT_CHARS[k];
        sb.append(ch);
      }
      else sb.append(BASIC.charAt(s));
    }
    return sb.toString();
  }

  /**
   * Split text into message segments. GSM-7 segments never split an escape
   * pair; UCS-2 segments never split a surrogate pair.
   */
  public static List<String> split(String text, boolean gsm7)
  {
    List<String> parts = new ArrayList<>();
    if (text.isEmpty()) { parts.add(""); return parts; }
    if (gsm7)
    {
      int total = 0;
      for (int i = 0; i < text.length(); i++) total += septetsFor(text.charAt(i));
      if (total <= GSM7_SINGLE) { parts.add(text); return parts; }
      StringBuilder cur = new StringBuilder();
      int used = 0;
      for (int i = 0; i < text.length(); i++)
      {
        char c = text.charAt(i);
        int n = septetsFor(c);
        if (used + n > GSM7_PART) { parts.add(cur.toString()); cur.setLength(0); used = 0; }
        cur.append(c);
        used += n;
      }
      if (cur.length() > 0) parts.add(cur.toString());
    }
    else
    {
      if (text.length() <= UCS2_SINGLE) { parts.add(text); return parts; }
      int i = 0;
      while (i < text.length())
      {
        int end = Math.min(i + UCS2_PART, text.length());
        if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        parts.add(text.substring(i, end));
        i = end;
      }
    }
    return parts;
  }

  /**
   * Replace common "smart" punctuation with GSM-7 friendly equivalents so a
   * message is not forced into UCS-2 (which cuts capacity from 160 to 70).
   */
  public static String simplify(String text)
  {
    StringBuilder sb = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++)
    {
      char c = text.charAt(i);
      switch (c)
      {
        case '‘': case '’': case '‚': case '′': sb.append('\''); break;
        case '“': case '”': case '„': case '″': sb.append('"'); break;
        case '–': case '—': case '−': sb.append('-'); break;
        case '…': sb.append("..."); break;
        case ' ': case '\t': sb.append(' '); break;
        case '°': sb.append("deg"); break;
        default: sb.append(c);
      }
    }
    return sb.toString();
  }
}
