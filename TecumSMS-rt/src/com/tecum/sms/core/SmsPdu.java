/*
 * Copyright (c) Tecum. All Rights Reserved.
 */
package com.tecum.sms.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds SMS-SUBMIT PDUs (3GPP TS 23.040) for AT+CMGS in PDU mode.
 * Supports GSM-7 and UCS-2 and concatenated (multi-part) messages.
 */
public final class SmsPdu
{
  /** One encoded PDU ready to hand to a modem. */
  public static final class Encoded
  {
    /** Full PDU hex including the leading SMSC field. */
    public final String hex;
    /** Value for AT+CMGS=n : octet count excluding the SMSC field. */
    public final int tpduLength;

    Encoded(String hex, int tpduLength) { this.hex = hex; this.tpduLength = tpduLength; }
    @Override public String toString() { return "AT+CMGS=" + tpduLength + " " + hex; }
  }

  private SmsPdu() {}

  /** Relative validity period octet: 0xA7 = 24 hours. */
  public static final int VP_24H = 0xA7;

  /**
   * Encode a message into one or more SMS-SUBMIT PDUs.
   *
   * @param phone destination; a leading '+' selects international numbering
   * @param text message text
   * @param refNumber concatenation reference (0-255), used only for multi-part
   * @param validity relative validity octet, or -1 to omit the validity period
   */
  public static List<Encoded> encode(String phone, String text, int refNumber, int validity)
  {
    boolean gsm7 = GsmAlphabet.isGsm7(text);
    List<String> parts = GsmAlphabet.split(text, gsm7);
    List<Encoded> out = new ArrayList<>(parts.size());
    for (int i = 0; i < parts.size(); i++)
    {
      byte[] udh = null;
      if (parts.size() > 1)
        udh = new byte[] { 0x05, 0x00, 0x03, (byte) refNumber, (byte) parts.size(), (byte) (i + 1) };
      out.add(encodePart(phone, parts.get(i), gsm7, udh, validity));
    }
    return out;
  }

  static Encoded encodePart(String phone, String text, boolean gsm7, byte[] udh, int validity)
  {
    ByteArrayOutputStream t = new ByteArrayOutputStream(176);
    int firstOctet = 0x01;                       // SMS-SUBMIT
    if (validity >= 0) firstOctet |= 0x10;       // relative validity period present
    if (udh != null) firstOctet |= 0x40;         // user data header present
    t.write(firstOctet);
    t.write(0x00);                               // message reference (set by phone)
    writeAddress(t, phone);
    t.write(0x00);                               // protocol identifier
    t.write(gsm7 ? 0x00 : 0x08);                 // data coding scheme
    if (validity >= 0) t.write(validity & 0xFF);

    if (gsm7)
    {
      byte[] septets = GsmAlphabet.toSeptets(text);
      int udhBits = udh == null ? 0 : udh.length * 8;
      int fillBits = udhBits == 0 ? 0 : (7 - (udhBits % 7)) % 7;
      int udhSeptets = (udhBits + fillBits) / 7;
      t.write(udhSeptets + septets.length);      // UDL in septets
      byte[] packed = packSeptets(udh, fillBits, septets);
      t.write(packed, 0, packed.length);
    }
    else
    {
      byte[] body = text.getBytes(StandardCharsets.UTF_16BE);
      int udhLen = udh == null ? 0 : udh.length;
      t.write(udhLen + body.length);             // UDL in octets
      if (udh != null) t.write(udh, 0, udh.length);
      t.write(body, 0, body.length);
    }

    byte[] tpdu = t.toByteArray();
    return new Encoded("00" + Hex.encode(tpdu), tpdu.length);
  }

  /** Destination address: digit count, type of address, swapped BCD digits. */
  static void writeAddress(ByteArrayOutputStream t, String phone)
  {
    String p = phone == null ? "" : phone.trim();
    boolean intl = p.startsWith("+");
    String digits = PhoneNumbers.digitsOnly(p);
    if (digits.isEmpty()) throw new IllegalArgumentException("Invalid phone number: '" + phone + "'");
    t.write(digits.length());
    t.write(intl ? 0x91 : 0x81);
    for (int i = 0; i < digits.length(); i += 2)
    {
      int lo = digits.charAt(i) - '0';
      int hi = i + 1 < digits.length() ? digits.charAt(i + 1) - '0' : 0x0F;
      t.write((hi << 4) | lo);
    }
  }

  /**
   * Pack septets LSB-first. When a UDH is present it occupies the first
   * octets, followed by fill bits so that the text starts on a septet boundary.
   */
  static byte[] packSeptets(byte[] udh, int fillBits, byte[] septets)
  {
    int udhLen = udh == null ? 0 : udh.length;
    int totalBits = udhLen * 8 + fillBits + septets.length * 7;
    byte[] out = new byte[(totalBits + 7) / 8];
    if (udh != null) System.arraycopy(udh, 0, out, 0, udhLen);
    int bitPos = udhLen * 8 + fillBits;
    for (byte s : septets)
    {
      int v = s & 0x7F;
      for (int b = 0; b < 7; b++, bitPos++)
        if ((v & (1 << b)) != 0) out[bitPos >> 3] |= (byte) (1 << (bitPos & 7));
    }
    return out;
  }

  /** Unpack LSB-first septets (no UDH), used for decoding and tests. */
  public static byte[] unpackSeptets(byte[] packed, int septetCount)
  {
    byte[] out = new byte[septetCount];
    for (int i = 0; i < septetCount; i++)
    {
      int v = 0;
      for (int b = 0; b < 7; b++)
      {
        int bitPos = i * 7 + b;
        if ((packed[bitPos >> 3] & (1 << (bitPos & 7))) != 0) v |= 1 << b;
      }
      out[i] = (byte) v;
    }
    return out;
  }
}
