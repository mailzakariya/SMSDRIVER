/*
 * Copyright (c) Ignitechs. All Rights Reserved.
 */
package com.ignitechs.sms.core;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.net.ssl.SSLSocketFactory;

/**
 * Minimal SMPP v3.4 transmitter client. Opens a session per batch:
 * bind_transmitter, submit_sm (one per segment), unbind.
 * Compatible with open-source SMSCs such as Kannel and Jasmin as well as
 * carrier SMPP endpoints.
 */
public class SmppClient
{
  public static final int BIND_TRANSMITTER = 0x00000002;
  public static final int SUBMIT_SM = 0x00000004;
  public static final int UNBIND = 0x00000006;
  public static final int ENQUIRE_LINK = 0x00000015;
  public static final int GENERIC_NACK = 0x80000000;
  public static final int RESP = 0x80000000;

  public static final class Config
  {
    public String host = "";
    public int port = 2775;
    public boolean tls;
    public String systemId = "";
    public String password = "";
    public String systemType = "";
    public String sourceAddress = "";
    public int sourceTon = 5;   // alphanumeric
    public int sourceNpi = 0;
    public int timeoutMs = 15000;
  }

  private final Config cfg;
  private Socket socket;
  private DataInputStream in;
  private OutputStream out;
  private int seq = 1;

  public SmppClient(Config cfg) { this.cfg = cfg; }

  /** Connect, bind, send every segment, unbind. Returns the message ids. */
  public String send(String phone, String text, int ref) throws IOException
  {
    connect();
    try
    {
      bind();
      StringBuilder ids = new StringBuilder();
      for (byte[][] seg : segments(text, ref))
      {
        byte[] resp = submit(phone, seg[0], seg[1][0] & 0xFF, seg[2][0] & 0xFF);
        if (ids.length() > 0) ids.append(',');
        ids.append(cString(resp, 0));
      }
      try { request(UNBIND, new byte[0]); } catch (IOException ignore) {}
      return ids.toString();
    }
    finally { close(); }
  }

  /** Connect and bind only, used as a connectivity test. */
  public void test() throws IOException
  {
    connect();
    try
    {
      bind();
      request(ENQUIRE_LINK, new byte[0]);
      try { request(UNBIND, new byte[0]); } catch (IOException ignore) {}
    }
    finally { close(); }
  }

  /** Each segment: {shortMessage bytes, {dataCoding}, {esmClass}}. */
  static java.util.List<byte[][]> segments(String text, int ref)
  {
    boolean gsm7 = GsmAlphabet.isGsm7(text);
    List<String> parts = GsmAlphabet.split(text, gsm7);
    java.util.List<byte[][]> out = new java.util.ArrayList<>();
    for (int i = 0; i < parts.size(); i++)
    {
      byte[] body = gsm7 ? GsmAlphabet.toSeptets(parts.get(i)) : parts.get(i).getBytes(StandardCharsets.UTF_16BE);
      int esm = 0;
      if (parts.size() > 1)
      {
        byte[] udh = { 0x05, 0x00, 0x03, (byte) ref, (byte) parts.size(), (byte) (i + 1) };
        byte[] joined = new byte[udh.length + body.length];
        System.arraycopy(udh, 0, joined, 0, udh.length);
        System.arraycopy(body, 0, joined, udh.length, body.length);
        body = joined;
        esm = 0x40;
      }
      out.add(new byte[][] { body, { (byte) (gsm7 ? 0x00 : 0x08) }, { (byte) esm } });
    }
    return out;
  }

  private void connect() throws IOException
  {
    Socket s = cfg.tls ? SSLSocketFactory.getDefault().createSocket() : new Socket();
    s.connect(new InetSocketAddress(cfg.host, cfg.port), cfg.timeoutMs);
    s.setSoTimeout(cfg.timeoutMs);
    s.setTcpNoDelay(true);
    socket = s;
    in = new DataInputStream(s.getInputStream());
    out = s.getOutputStream();
  }

  private void close()
  {
    try { if (socket != null) socket.close(); } catch (IOException ignore) {}
    socket = null;
  }

  private void bind() throws IOException
  {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    putC(b, cfg.systemId);
    putC(b, cfg.password);
    putC(b, cfg.systemType);
    b.write(0x34);            // interface_version 3.4
    b.write(0);               // addr_ton
    b.write(0);               // addr_npi
    putC(b, "");              // address_range
    request(BIND_TRANSMITTER, b.toByteArray());
  }

  private byte[] submit(String phone, byte[] shortMessage, int dataCoding, int esmClass) throws IOException
  {
    String dest = PhoneNumbers.normalize(phone);
    boolean intl = dest.startsWith("+");
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    putC(b, "");                                   // service_type
    b.write(cfg.sourceTon);
    b.write(cfg.sourceNpi);
    putC(b, cfg.sourceAddress);
    b.write(intl ? 1 : 0);                         // dest ton: international / unknown
    b.write(1);                                    // dest npi: ISDN
    putC(b, PhoneNumbers.digitsOnly(dest));
    b.write(esmClass);
    b.write(0);                                    // protocol_id
    b.write(0);                                    // priority_flag
    putC(b, "");                                   // schedule_delivery_time
    putC(b, "");                                   // validity_period
    b.write(0);                                    // registered_delivery
    b.write(0);                                    // replace_if_present_flag
    b.write(dataCoding);
    b.write(0);                                    // sm_default_msg_id
    b.write(shortMessage.length);
    b.write(shortMessage, 0, shortMessage.length);
    return request(SUBMIT_SM, b.toByteArray());
  }

  /** Send a request and wait for its response, answering enquire_link meanwhile. */
  private byte[] request(int commandId, byte[] body) throws IOException
  {
    int mySeq = seq++;
    writePdu(commandId, 0, mySeq, body);
    while (true)
    {
      int len = in.readInt();
      if (len < 16 || len > 65536) throw new IOException("Invalid SMPP PDU length " + len);
      int id = in.readInt();
      int status = in.readInt();
      int s = in.readInt();
      byte[] rb = new byte[len - 16];
      in.readFully(rb);
      if (id == ENQUIRE_LINK) { writePdu(ENQUIRE_LINK | RESP, 0, s, new byte[0]); continue; }
      if (id == GENERIC_NACK) throw new IOException("SMSC rejected request (generic_nack status 0x" + Integer.toHexString(status) + ")");
      if (id == (commandId | RESP) && s == mySeq)
      {
        if (status != 0) throw new IOException(describe(commandId) + " failed: " + statusText(status));
        return rb;
      }
      // anything else (e.g. stray deliver_sm) is ignored
    }
  }

  private void writePdu(int id, int status, int s, byte[] body) throws IOException
  {
    ByteArrayOutputStream b = new ByteArrayOutputStream(16 + body.length);
    putInt(b, 16 + body.length);
    putInt(b, id);
    putInt(b, status);
    putInt(b, s);
    b.write(body, 0, body.length);
    out.write(b.toByteArray());
    out.flush();
  }

  static void putInt(ByteArrayOutputStream b, int v)
  {
    b.write(v >>> 24); b.write(v >>> 16); b.write(v >>> 8); b.write(v);
  }

  static void putC(ByteArrayOutputStream b, String s)
  {
    byte[] x = (s == null ? "" : s).getBytes(StandardCharsets.ISO_8859_1);
    b.write(x, 0, x.length);
    b.write(0);
  }

  static String cString(byte[] b, int off)
  {
    int end = off;
    while (end < b.length && b[end] != 0) end++;
    return new String(b, off, end - off, StandardCharsets.ISO_8859_1);
  }

  private static String describe(int id)
  {
    switch (id)
    {
      case BIND_TRANSMITTER: return "bind_transmitter";
      case SUBMIT_SM: return "submit_sm";
      case UNBIND: return "unbind";
      case ENQUIRE_LINK: return "enquire_link";
      default: return "command 0x" + Integer.toHexString(id);
    }
  }

  static String statusText(int status)
  {
    switch (status)
    {
      case 0x0E: return "invalid password (ESME_RINVPASWD)";
      case 0x0F: return "invalid system id (ESME_RINVSYSID)";
      case 0x0D: return "bind failed (ESME_RBINDFAIL)";
      case 0x0B: return "invalid destination address (ESME_RINVDSTADR)";
      case 0x0A: return "invalid source address (ESME_RINVSRCADR)";
      case 0x14: return "message queue full (ESME_RMSGQFUL)";
      case 0x58: return "throttling error (ESME_RTHROTTLED)";
      case 0x45: return "submit failed (ESME_RSUBMITFAIL)";
      default: return "status 0x" + Integer.toHexString(status);
    }
  }
}
