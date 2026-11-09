/*
 * Copyright (c) Ignitechs. All Rights Reserved.
 */
package com.ignitechs.sms.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AT command session with a GSM/LTE modem (3GPP TS 27.005 / 27.007).
 * Works over any byte stream: a serial port or a TCP socket to a
 * serial-to-Ethernet converter / router that exposes the modem.
 *
 * Not thread-safe; callers serialise access (the dispatcher thread does).
 */
public class AtModem
{
  public static final class Response
  {
    public final boolean ok;
    public final List<String> lines;
    public final String error;

    Response(boolean ok, List<String> lines, String error) { this.ok = ok; this.lines = lines; this.error = error; }

    /** First line beginning with prefix, with the prefix removed, or null. */
    public String find(String prefix)
    {
      for (String l : lines) if (l.startsWith(prefix)) return l.substring(prefix.length()).trim();
      return null;
    }
  }

  private static final Logger LOG = Logger.getLogger("ignitechsSms.modem");
  private static final Pattern CMGL = Pattern.compile("^\\+CMGL:\\s*(\\d+)\\s*,\\s*\"([^\"]*)\"\\s*,\\s*\"([^\"]*)\".*$");

  private final InputStream in;
  private final OutputStream out;
  private final String name;
  private final StringBuilder pending = new StringBuilder();

  public AtModem(String name, InputStream in, OutputStream out)
  {
    this.name = name;
    this.in = in;
    this.out = out;
  }

  // ------------------------------------------------------------------ high level

  /**
   * Bring the modem into a known state: echo off, verbose errors, SIM unlocked,
   * PDU mode. Returns a short human readable description of the modem.
   */
  public String initialize(String simPin, String smsc) throws IOException
  {
    Response r = null;
    for (int i = 0; i < 3; i++)
    {
      drain();
      r = command("AT", 2000);
      if (r.ok) break;
    }
    if (r == null || !r.ok) throw new IOException("Modem not responding to AT");

    command("ATE0", 2000);
    command("AT+CMEE=1", 2000);

    Response pin = command("AT+CPIN?", 5000);
    String state = pin.find("+CPIN:");
    if (state != null && state.toUpperCase().contains("SIM PIN"))
    {
      if (simPin == null || simPin.trim().isEmpty()) throw new IOException("SIM requires a PIN but none is configured");
      Response p = command("AT+CPIN=\"" + simPin.trim() + "\"", 10000);
      if (!p.ok) throw new IOException("SIM PIN rejected: " + p.error);
      sleep(3000);
    }
    else if (state != null && state.toUpperCase().contains("PUK"))
      throw new IOException("SIM is PUK locked");
    else if (!pin.ok)
      throw new IOException("SIM not ready: " + pin.error);

    Response mode = command("AT+CMGF=0", 3000);
    if (!mode.ok) throw new IOException("Modem does not support PDU mode: " + mode.error);

    if (smsc != null && !smsc.trim().isEmpty())
      command("AT+CSCA=\"" + PhoneNumbers.normalize(smsc) + "\"", 3000);

    Response mr = command("AT+CGMM", 2000);
    return mr.ok && !mr.lines.isEmpty() ? mr.lines.get(0) : "modem";
  }

  /** Signal quality 0-31 (99 = unknown), or -1 when not available. */
  public int signalQuality() throws IOException
  {
    Response r = command("AT+CSQ", 3000);
    String v = r.find("+CSQ:");
    if (v == null) return -1;
    try { return Integer.parseInt(v.split(",")[0].trim()); }
    catch (NumberFormatException e) { return -1; }
  }

  /** Network registration: true when registered (home or roaming). */
  public boolean isRegistered() throws IOException
  {
    Response r = command("AT+CREG?", 3000);
    String v = r.find("+CREG:");
    if (v == null) return false;
    String[] f = v.split(",");
    String stat = f.length > 1 ? f[1].trim() : f[0].trim();
    return "1".equals(stat) || "5".equals(stat);
  }

  /** Send one SMS (possibly multi-part). Throws on any part failure. */
  public void sendSms(String phone, String text, int ref) throws IOException
  {
    for (SmsPdu.Encoded pdu : SmsPdu.encode(phone, text, ref, SmsPdu.VP_24H))
      sendPdu(pdu);
  }

  public void sendPdu(SmsPdu.Encoded pdu) throws IOException
  {
    drain();
    write("AT+CMGS=" + pdu.tpduLength + "\r");
    if (!waitForPrompt(10000))
    {
      write("\u001b");
      throw new IOException("No '>' prompt from modem");
    }
    write(pdu.hex + "\u001a");
    Response r = readResponse(60000);
    if (!r.ok) throw new IOException("Send failed: " + r.error);
  }

  /** An inbound SMS read from SIM/modem storage. */
  public static final class Inbound
  {
    public final int index;
    public final String from;
    public final String text;
    Inbound(int index, String from, String text) { this.index = index; this.from = from; this.text = text; }
  }

  /**
   * Read all stored messages (text mode), delete them from storage and
   * return them. Leaves the modem in PDU mode.
   */
  public List<Inbound> readAndDeleteInbox() throws IOException
  {
    List<Inbound> result = new ArrayList<>();
    try
    {
      if (!command("AT+CMGF=1", 3000).ok) return result;
      command("AT+CSCS=\"GSM\"", 2000);
      Response r = command("AT+CMGL=\"ALL\"", 15000);
      if (!r.ok) return result;
      result.addAll(parseCmgl(r.lines));
      for (Inbound m : result) command("AT+CMGD=" + m.index, 5000);
    }
    finally
    {
      command("AT+CMGF=0", 3000);
    }
    return result;
  }

  static List<Inbound> parseCmgl(List<String> lines)
  {
    List<Inbound> out = new ArrayList<>();
    for (int i = 0; i < lines.size(); i++)
    {
      Matcher m = CMGL.matcher(lines.get(i));
      if (!m.matches()) continue;
      StringBuilder body = new StringBuilder();
      while (i + 1 < lines.size() && !lines.get(i + 1).startsWith("+CMGL:"))
      {
        if (body.length() > 0) body.append('\n');
        body.append(lines.get(++i));
      }
      out.add(new Inbound(Integer.parseInt(m.group(1)), m.group(3), body.toString()));
    }
    return out;
  }

  // ------------------------------------------------------------------ low level

  public Response command(String cmd, long timeoutMs) throws IOException
  {
    if (LOG.isLoggable(Level.FINE)) LOG.fine(name + " >> " + cmd);
    write(cmd + "\r");
    return readResponse(timeoutMs);
  }

  private void write(String s) throws IOException
  {
    out.write(s.getBytes(StandardCharsets.ISO_8859_1));
    out.flush();
  }

  /** Discard anything waiting in the input buffer. */
  public void drain() throws IOException
  {
    pending.setLength(0);
    while (in.available() > 0)
    {
      byte[] b = new byte[in.available()];
      if (in.read(b) <= 0) break;
    }
  }

  private Response readResponse(long timeoutMs) throws IOException
  {
    List<String> lines = new ArrayList<>();
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (true)
    {
      String line = readLine(deadline);
      if (line == null) return new Response(false, lines, "timeout");
      if (line.isEmpty()) continue;
      if (LOG.isLoggable(Level.FINE)) LOG.fine(name + " << " + line);
      if (line.equals("OK")) return new Response(true, lines, null);
      if (line.equals("ERROR") || line.startsWith("+CMS ERROR") || line.startsWith("+CME ERROR")
          || line.equals("NO CARRIER"))
        return new Response(false, lines, line);
      if (line.startsWith("AT")) continue;            // echo
      lines.add(line);
    }
  }

  private boolean waitForPrompt(long timeoutMs) throws IOException
  {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline)
    {
      if (pending.indexOf(">") >= 0) { pending.setLength(0); return true; }
      if (pending.indexOf("ERROR") >= 0) return false;
      if (!fill()) sleep(20);
    }
    return false;
  }

  /** Read one CR/LF terminated line, or null on timeout. */
  private String readLine(long deadline) throws IOException
  {
    while (true)
    {
      for (int i = 0; i < pending.length(); i++)
      {
        char c = pending.charAt(i);
        if (c == '\n' || c == '\r')
        {
          String line = pending.substring(0, i).trim();
          pending.delete(0, i + 1);
          return line;
        }
      }
      if (System.currentTimeMillis() > deadline) return null;
      if (!fill()) sleep(20);
    }
  }

  /** Pull available bytes into the pending buffer; false when nothing was read. */
  private boolean fill() throws IOException
  {
    int n = in.available();
    if (n <= 0) return false;
    byte[] b = new byte[Math.min(n, 4096)];
    int r = in.read(b);
    if (r < 0) throw new IOException("Modem connection closed");
    if (r == 0) return false;
    pending.append(new String(b, 0, r, StandardCharsets.ISO_8859_1));
    return true;
  }

  private static void sleep(long ms)
  {
    try { Thread.sleep(ms); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
  }
}
