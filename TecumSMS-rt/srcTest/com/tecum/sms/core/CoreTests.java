/*
 * Copyright (c) Tecum. All Rights Reserved.
 */
package com.tecum.sms.core;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;

/**
 * Dependency-free tests for the protocol and rule logic (runs on a plain JDK 8,
 * no Niagara installation required). Run with: build.ps1 -Test
 */
public final class CoreTests
{
  private static int passed, failed;

  public static void main(String[] args) throws Exception
  {
    run("pdu: reference vector 'hellohello'", CoreTests::pduReference);
    run("pdu: national number type", CoreTests::pduNational);
    run("pdu: septet pack/unpack round trip", CoreTests::septetRoundTrip);
    run("pdu: multi-part GSM-7 with UDH", CoreTests::pduMultipart);
    run("pdu: UCS-2 (Arabic) message", CoreTests::pduUcs2);
    run("gsm: alphabet detection and extension chars", CoreTests::gsmDetect);
    run("gsm: split does not break escape pairs", CoreTests::gsmSplitEscape);
    run("gsm: simplify smart punctuation", CoreTests::gsmSimplify);
    run("phone: normalise and compare", CoreTests::phones);
    run("template: placeholders", CoreTests::templates);
    run("rule: greater-than with delay, repeat, deadband, normal", CoreTests::ruleGreaterThan);
    run("rule: equals on enum states (list)", CoreTests::ruleEnumStates);
    run("rule: boolean true/false and facet text", CoreTests::ruleBoolean);
    run("rule: range conditions", CoreTests::ruleRange);
    run("rule: status conditions and unreliable values", CoreTests::ruleStatus);
    run("rule: any change rate limited", CoreTests::ruleAnyChange);
    run("modem: init, send PDU, read inbox (simulated modem)", CoreTests::modemSimulated);
    run("modem: CMGL parsing", CoreTests::cmglParse);
    run("smpp: bind/submit/unbind against in-process SMSC", CoreTests::smppServer);
    run("smpp: long UCS-2 message segments", CoreTests::smppSegments);
    run("http: JSON body, basic auth, success pattern", CoreTests::httpJson);
    run("http: GET with URL-encoded query and failure pattern", CoreTests::httpGet);
    run("http: subscription apiKey and senderId placeholders", CoreTests::httpApiKeyAndSender);
    run("email: Email-to-SMS address and template formatting", CoreTests::emailToSmsFormatting);
    run("dispatcher: failover, retry, dedupe, priority", CoreTests::dispatcher);
    run("dispatcher: rate limit holds messages", CoreTests::dispatcherRateLimit);

    System.out.println();
    System.out.println("Passed: " + passed + "  Failed: " + failed);
    if (failed > 0) System.exit(1);
  }

  interface Test { void run() throws Exception; }

  static void run(String name, Test t)
  {
    try { t.run(); passed++; System.out.println("  PASS  " + name); }
    catch (Throwable e) { failed++; System.out.println("  FAIL  " + name + " -> " + e); e.printStackTrace(System.out); }
  }

  static void check(boolean cond, String msg) { if (!cond) throw new AssertionError(msg); }
  static void eq(Object exp, Object act) { if (exp == null ? act != null : !exp.equals(act)) throw new AssertionError("expected <" + exp + "> but was <" + act + ">"); }

  // ------------------------------------------------------------------ PDU

  static void pduReference()
  {
    // Widely published example (dreamfabric.com/sms): "hellohello" to +46708251358, VP=0xAA
    List<SmsPdu.Encoded> p = SmsPdu.encode("+46708251358", "hellohello", 0, 0xAA);
    eq(1, p.size());
    eq("0011000B916407281553F80000AA0AE8329BFD4697D9EC37", p.get(0).hex);
    eq(23, p.get(0).tpduLength);
  }

  static void pduNational()
  {
    SmsPdu.Encoded e = SmsPdu.encode("0501234567", "Hi", 0, -1).get(0);
    // first octet 01 (no VP), MR 00, 10 digits, type 81, BCD 5010325476
    check(e.hex.startsWith("0001000A815010325476"), e.hex);
  }

  static void septetRoundTrip()
  {
    String s = "Chiller 2 FAULT @ 14:05 {ack} [x] 10€";
    byte[] sep = GsmAlphabet.toSeptets(s);
    byte[] packed = SmsPdu.packSeptets(null, 0, sep);
    eq(s, GsmAlphabet.fromSeptets(SmsPdu.unpackSeptets(packed, sep.length)));
  }

  static void pduMultipart()
  {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 400; i++) sb.append((char) ('a' + i % 26));
    List<SmsPdu.Encoded> p = SmsPdu.encode("+15551234567", sb.toString(), 0x42, SmsPdu.VP_24H);
    eq(3, p.size());                              // 153 + 153 + 94
    SmsPdu.Encoded first = p.get(0);
    // first octet must have UDHI (0x40) + VP (0x10) + SUBMIT (0x01) = 0x51
    eq("51", first.hex.substring(2, 4));
    // UDL = 7 (UDH septets) + 153 = 160 = 0xA0 ; UDH 050003 42 03 01
    check(first.hex.contains("A0050003420301"), first.hex);
    check(p.get(2).hex.contains("050003420303"), "last part sequence");
    check(first.tpduLength <= 140 + 15, "length " + first.tpduLength);
  }

  static void pduUcs2()
  {
    String ar = "إنذار حريق";    // "fire alarm" in Arabic
    SmsPdu.Encoded e = SmsPdu.encode("+971501234567", ar, 0, SmsPdu.VP_24H).get(0);
    check(e.hex.contains("0008A7"), "DCS 08 + VP");            // PID 00, DCS 08, VP A7
    check(e.hex.endsWith("14" + Hex.encode(ar.getBytes(StandardCharsets.UTF_16BE))), e.hex);
    StringBuilder longAr = new StringBuilder();
    for (int i = 0; i < 15; i++) longAr.append(ar);
    eq(3, SmsPdu.encode("+971501234567", longAr.toString(), 1, -1).size()); // 150 chars -> 67+67+16
  }

  static void gsmDetect()
  {
    check(GsmAlphabet.isGsm7("Temp HIGH: 28.5 deg C {AHU-1}"), "ascii+ext");
    check(!GsmAlphabet.isGsm7("Temp 28.5°C"), "degree sign is not GSM");
    check(!GsmAlphabet.isGsm7("حريق"), "arabic");
    eq(2, GsmAlphabet.toSeptets("{").length);
  }

  static void gsmSplitEscape()
  {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 152; i++) sb.append('a');
    sb.append("{bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");   // '{' would straddle septet 153/154
    List<String> parts = GsmAlphabet.split(sb.toString(), true);
    eq(2, parts.size());
    eq(152, parts.get(0).length());
    check(parts.get(1).startsWith("{"), "escape moved to next part");
  }

  static void gsmSimplify()
  {
    String s = GsmAlphabet.simplify("“Temp” – 28°C…");
    eq("\"Temp\" - 28degC...", s);
    check(GsmAlphabet.isGsm7(s), "simplified is GSM-7");
  }

  static void phones()
  {
    eq("+971501234567", PhoneNumbers.normalize(" +971 (50) 123-4567 "));
    eq("+971501234567", PhoneNumbers.normalize("00971501234567"));
    eq("0501234567", PhoneNumbers.normalize("050 123 4567"));
    check(PhoneNumbers.isValid("+44 20 7946 0958"), "valid");
    check(!PhoneNumbers.isValid("call me"), "invalid letters");
    check(!PhoneNumbers.isValid("12"), "too short");
    check(PhoneNumbers.sameNumber("+971501234567", "0501234567"), "national vs intl");
    check(!PhoneNumbers.sameNumber("+971501234567", "+971501234568"), "different");
  }

  static void templates()
  {
    Map<String, String> v = new HashMap<>();
    v.put("point", "AHU1 SAT");
    v.put("value", "31.2 °C");
    eq("AHU1 SAT = 31.2 °C {unknown}", Templates.render("{point} = {value} {unknown}", v));
    eq("abcd...", Templates.limit("abcdefghij", 7));
  }

  // ------------------------------------------------------------------ rules

  static PointSample num(double v) { return PointSample.numeric(v); }

  static void ruleGreaterThan()
  {
    RuleEvaluator.Settings s = new RuleEvaluator.Settings();
    s.condition = RuleEvaluator.GREATER_THAN;
    s.limit = 30; s.deadband = 1; s.activeDelayMs = 10000; s.repeatIntervalMs = 60000; s.maxRepeats = 2;
    RuleEvaluator r = new RuleEvaluator();
    eq(RuleEvaluator.Decision.NONE, r.update(s, num(31), 0));        // pending
    eq(RuleEvaluator.Decision.NONE, r.update(s, num(29), 5000));     // dropped before delay
    eq(RuleEvaluator.State.NORMAL, r.getState());
    eq(RuleEvaluator.Decision.NONE, r.update(s, num(31), 6000));
    eq(RuleEvaluator.Decision.ALERT, r.update(s, num(31), 16000));   // held 10 s
    eq(RuleEvaluator.Decision.NONE, r.update(s, num(29.5), 20000));  // within deadband: still active
    eq(RuleEvaluator.Decision.REPEAT, r.update(s, num(32), 76000));
    eq(RuleEvaluator.Decision.REPEAT, r.update(s, num(32), 136000));
    eq(RuleEvaluator.Decision.NONE, r.update(s, num(32), 196000));   // max repeats reached
    eq(RuleEvaluator.Decision.NORMAL, r.update(s, num(28.9), 200000));
    eq(RuleEvaluator.Decision.NONE, r.update(s, num(28), 201000));
  }

  static void ruleEnumStates()
  {
    RuleEvaluator.Settings s = new RuleEvaluator.Settings();
    s.condition = RuleEvaluator.VALUE_EQUALS;
    s.compareValue = "Fault | Trip";
    RuleEvaluator r = new RuleEvaluator();
    eq(RuleEvaluator.Decision.NONE, r.update(s, PointSample.enumeration(1, "running", "Running"), 0));
    eq(RuleEvaluator.Decision.ALERT, r.update(s, PointSample.enumeration(3, "trip", "Trip"), 1000));
    eq(RuleEvaluator.Decision.NORMAL, r.update(s, PointSample.enumeration(1, "running", "Running"), 2000));
    s.compareValue = "2";
    check(RuleEvaluator.matches(PointSample.enumeration(2, "fault", "Fault"), s.compareValue), "ordinal match");
    check(RuleEvaluator.matches(PointSample.enumeration(2, "fault", "Fault"), "FAULT"), "tag ignore case");
  }

  static void ruleBoolean()
  {
    RuleEvaluator.Settings s = new RuleEvaluator.Settings();
    s.condition = RuleEvaluator.IS_TRUE;
    RuleEvaluator r = new RuleEvaluator();
    eq(RuleEvaluator.Decision.ALERT, r.update(s, PointSample.bool(true), 0));
    eq(RuleEvaluator.Decision.NORMAL, r.update(s, PointSample.bool(false), 1));
    PointSample b = PointSample.bool(true);
    b.display = "Tripped";                                          // facet trueText
    check(RuleEvaluator.matches(b, "tripped"), "facet text");
    check(RuleEvaluator.matches(b, "on"), "on alias");
    check(!RuleEvaluator.matches(b, "off"), "off alias");
  }

  static void ruleRange()
  {
    RuleEvaluator.Settings s = new RuleEvaluator.Settings();
    s.condition = RuleEvaluator.OUTSIDE_RANGE;
    s.lowLimit = 18; s.highLimit = 26; s.deadband = 0.5;
    check(RuleEvaluator.test(s, num(17.9), false), "below");
    check(!RuleEvaluator.test(s, num(22), false), "inside");
    check(RuleEvaluator.test(s, num(18.2), true), "deadband keeps active");
    check(!RuleEvaluator.test(s, num(18.6), true), "clears beyond deadband");
    s.condition = RuleEvaluator.INSIDE_RANGE;
    check(RuleEvaluator.test(s, num(20), false), "inside");
    check(!RuleEvaluator.test(s, num(30), false), "outside");
  }

  static void ruleStatus()
  {
    RuleEvaluator.Settings s = new RuleEvaluator.Settings();
    s.condition = RuleEvaluator.STATUS_DOWN;
    RuleEvaluator r = new RuleEvaluator();
    PointSample p = num(10);
    p.down = true;
    eq(RuleEvaluator.Decision.ALERT, r.update(s, p, 0));
    eq(RuleEvaluator.Decision.NORMAL, r.update(s, num(10), 1000));

    // value conditions hold their state while the point is offline
    s.condition = RuleEvaluator.GREATER_THAN;
    s.limit = 5;
    RuleEvaluator r2 = new RuleEvaluator();
    eq(RuleEvaluator.Decision.ALERT, r2.update(s, num(10), 0));
    PointSample off = num(0);
    off.down = true;
    eq(RuleEvaluator.Decision.NONE, r2.update(s, off, 1000));
    check(r2.isActive(), "still active while offline");
  }

  static void ruleAnyChange()
  {
    RuleEvaluator.Settings s = new RuleEvaluator.Settings();
    s.condition = RuleEvaluator.ANY_CHANGE;
    s.repeatIntervalMs = 10000;
    RuleEvaluator r = new RuleEvaluator();
    eq(RuleEvaluator.Decision.NONE, r.update(s, PointSample.string("Auto"), 0));      // first sample
    eq(RuleEvaluator.Decision.CHANGE, r.update(s, PointSample.string("Hand"), 20000));
    eq(RuleEvaluator.Decision.NONE, r.update(s, PointSample.string("Off"), 25000));   // within min interval
    eq(RuleEvaluator.Decision.CHANGE, r.update(s, PointSample.string("Auto"), 40000));
  }

  // ------------------------------------------------------------------ modem

  /** Simulates a modem: reacts to commands written to its OutputStream. */
  static final class FakeModem
  {
    final StringBuilder rx = new StringBuilder();
    final List<String> commands = new CopyOnWriteArrayList<>();
    final StringBuilder tx = new StringBuilder();
    boolean textMode;
    boolean hasMessage = true;
    String lastPdu;

    final InputStream in = new InputStream()
    {
      @Override public synchronized int available() { synchronized (tx) { return tx.length(); } }
      @Override public int read()
      {
        synchronized (tx) { if (tx.length() == 0) return -1; char c = tx.charAt(0); tx.deleteCharAt(0); return c; }
      }
      @Override public int read(byte[] b, int off, int len)
      {
        synchronized (tx)
        {
          int n = Math.min(len, tx.length());
          for (int i = 0; i < n; i++) b[off + i] = (byte) tx.charAt(i);
          tx.delete(0, n);
          return n;
        }
      }
    };

    final OutputStream out = new OutputStream()
    {
      boolean awaitingPdu;
      @Override public void write(int b)
      {
        char c = (char) (b & 0xFF);
        if (awaitingPdu)
        {
          if (c == 0x1A) { lastPdu = rx.toString(); rx.setLength(0); awaitingPdu = false; reply("\r\n+CMGS: 17\r\n\r\nOK\r\n"); }
          else rx.append(c);
          return;
        }
        if (c != '\r') { rx.append(c); return; }
        String cmd = rx.toString(); rx.setLength(0);
        commands.add(cmd);
        if (cmd.startsWith("AT+CMGS=")) { awaitingPdu = true; reply("\r\n> "); return; }
        reply(respond(cmd));
      }
    };

    void reply(String s) { synchronized (tx) { tx.append(s); } }

    String respond(String cmd)
    {
      switch (cmd)
      {
        case "AT": case "ATE0": case "AT+CMEE=1": case "AT+CSCS=\"GSM\"": return "\r\nOK\r\n";
        case "AT+CPIN?": return "\r\n+CPIN: READY\r\n\r\nOK\r\n";
        case "AT+CMGF=0": textMode = false; return "\r\nOK\r\n";
        case "AT+CMGF=1": textMode = true; return "\r\nOK\r\n";
        case "AT+CGMM": return "\r\nSIM800L\r\n\r\nOK\r\n";
        case "AT+CSQ": return "\r\n+CSQ: 21,0\r\n\r\nOK\r\n";
        case "AT+CREG?": return "\r\n+CREG: 0,5\r\n\r\nOK\r\n";
        case "AT+CMGL=\"ALL\"":
          if (!textMode) return "\r\n+CMS ERROR: 302\r\n";
          if (!hasMessage) return "\r\nOK\r\n";
          return "\r\n+CMGL: 3,\"REC UNREAD\",\"+971501234567\",,\"24/05/01,10:00:00+16\"\r\nACK 1234\r\n\r\nOK\r\n";
        case "AT+CMGD=3": hasMessage = false; return "\r\nOK\r\n";
        default: return "\r\nERROR\r\n";
      }
    }
  }

  static void modemSimulated() throws Exception
  {
    FakeModem fm = new FakeModem();
    AtModem m = new AtModem("test", fm.in, fm.out);
    eq("SIM800L", m.initialize(null, null));
    eq(21, m.signalQuality());
    check(m.isRegistered(), "registered roaming");
    m.sendSms("+46708251358", "hellohello", 0);
    check(fm.lastPdu.startsWith("0011000B916407281553F80000A70A"), fm.lastPdu);
    List<AtModem.Inbound> inbox = m.readAndDeleteInbox();
    eq(1, inbox.size());
    eq("+971501234567", inbox.get(0).from);
    eq("ACK 1234", inbox.get(0).text);
    check(fm.commands.contains("AT+CMGD=3"), "deleted");
    eq("AT+CMGF=0", fm.commands.get(fm.commands.size() - 1));
    eq(0, m.readAndDeleteInbox().size());
  }

  static void cmglParse()
  {
    List<AtModem.Inbound> l = AtModem.parseCmgl(Arrays.asList(
      "+CMGL: 1,\"REC READ\",\"+15551112222\",\"\",\"24/01/01,08:00:00+00\"",
      "STATUS",
      "+CMGL: 2,\"REC UNREAD\",\"0501234567\",,\"24/01/01,08:01:00+00\"",
      "line one", "line two"));
    eq(2, l.size());
    eq("STATUS", l.get(0).text);
    eq("0501234567", l.get(1).from);
    eq("line one\nline two", l.get(1).text);
  }

  // ------------------------------------------------------------------ SMPP

  static void smppServer() throws Exception
  {
    final List<String> received = new CopyOnWriteArrayList<>();
    final AtomicReference<String> boundAs = new AtomicReference<>();
    try (ServerSocket ss = new ServerSocket(0, 5, java.net.InetAddress.getLoopbackAddress()))
    {
      Thread srv = new Thread(() -> {
        try (Socket s = ss.accept())
        {
          DataInputStream in = new DataInputStream(s.getInputStream());
          DataOutputStream out = new DataOutputStream(s.getOutputStream());
          while (true)
          {
            int len = in.readInt(), id = in.readInt(); in.readInt(); int seq = in.readInt();
            byte[] body = new byte[len - 16]; in.readFully(body);
            byte[] respBody = new byte[0];
            if (id == SmppClient.BIND_TRANSMITTER)
            {
              String sys = SmppClient.cString(body, 0);
              String pwd = SmppClient.cString(body, sys.length() + 1);
              boundAs.set(sys + "/" + pwd);
              respBody = "SMSC\0".getBytes(StandardCharsets.ISO_8859_1);
            }
            else if (id == SmppClient.SUBMIT_SM)
            {
              // skip to destination: service_type\0 ton npi source\0 ton npi dest\0
              int p = 0;
              p = skipC(body, p); p += 2; p = skipC(body, p); p += 2;
              String dest = SmppClient.cString(body, p); p = skipC(body, p);
              p += 1 + 1 + 1; p = skipC(body, p); p = skipC(body, p); p += 2;
              int dcs = body[p++]; p++;
              int smLen = body[p++] & 0xFF;
              byte[] sm = Arrays.copyOfRange(body, p, p + smLen);
              received.add(dest + ":" + dcs + ":" + GsmAlphabet.fromSeptets(sm));
              respBody = "msg-1\0".getBytes(StandardCharsets.ISO_8859_1);
            }
            out.writeInt(16 + respBody.length); out.writeInt(id | SmppClient.RESP); out.writeInt(0); out.writeInt(seq);
            out.write(respBody); out.flush();
            if (id == SmppClient.UNBIND) break;
          }
        }
        catch (IOException ignore) {}
      });
      srv.start();
      SmppClient.Config c = new SmppClient.Config();
      c.host = "127.0.0.1"; c.port = ss.getLocalPort(); c.systemId = "niagara"; c.password = "secret"; c.sourceAddress = "BMS";
      String ids = new SmppClient(c).send("+971501234567", "Boiler trip", 1);
      srv.join(3000);
      eq("msg-1", ids);
      eq("niagara/secret", boundAs.get());
      eq(Collections.singletonList("971501234567:0:Boiler trip"), received);
    }
  }

  static int skipC(byte[] b, int p) { while (b[p] != 0) p++; return p + 1; }

  static void smppSegments()
  {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < 100; i++) sb.append('ع');
    List<byte[][]> segs = SmppClient.segments(sb.toString(), 9);
    eq(2, segs.size());
    eq(0x08, segs.get(0)[1][0] & 0xFF);
    eq(0x40, segs.get(0)[2][0] & 0xFF);
    eq(6 + 67 * 2, segs.get(0)[0].length);
    eq(2, segs.get(1)[0][4] & 0xFF);   // total parts
    eq(2, segs.get(1)[0][5] & 0xFF);   // sequence
  }

  // ------------------------------------------------------------------ HTTP

  static void httpJson() throws Exception
  {
    final AtomicReference<String> body = new AtomicReference<>();
    final AtomicReference<String> auth = new AtomicReference<>();
    HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    srv.createContext("/message", ex -> {
      body.set(readAll(ex.getRequestBody()));
      auth.set(ex.getRequestHeaders().getFirst("Authorization"));
      byte[] r = "{\"id\":\"abc\",\"state\":\"Pending\"}".getBytes(StandardCharsets.UTF_8);
      ex.sendResponseHeaders(202, r.length);
      ex.getResponseBody().write(r);
      ex.close();
    });
    srv.start();
    try
    {
      HttpSmsClient.Config c = new HttpSmsClient.Config();
      c.url = "http://127.0.0.1:" + srv.getAddress().getPort() + "/message";
      c.body = "{\"textMessage\":{\"text\":\"{message}\"},\"phoneNumbers\":[\"{phone}\"]}";
      c.username = "sms"; c.password = "p@ss"; c.basicAuth = true;
      c.successPattern = "\"id\"";
      new HttpSmsClient(c).send("00971 50 123 4567", "Line1\n\"quoted\" ع");
      eq("{\"textMessage\":{\"text\":\"Line1\\n\\\"quoted\\\" ع\"},\"phoneNumbers\":[\"+971501234567\"]}", body.get());
      eq("Basic c21zOnBAc3M=", auth.get());
    }
    finally { srv.stop(0); }
  }

  static void httpGet() throws Exception
  {
    final AtomicReference<String> query = new AtomicReference<>();
    HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    srv.createContext("/cgi-bin/sendsms", ex -> {
      query.set(ex.getRequestURI().getRawQuery());
      byte[] r = "0: Accepted for delivery".getBytes(StandardCharsets.UTF_8);
      ex.sendResponseHeaders(202, r.length);
      ex.getResponseBody().write(r);
      ex.close();
    });
    srv.start();
    try
    {
      HttpSmsClient.Config c = new HttpSmsClient.Config();
      c.method = "GET";
      c.url = "http://127.0.0.1:" + srv.getAddress().getPort() + "/cgi-bin/sendsms?username={username}&password={password}&to={phone}&text={message}";
      c.username = "kannel"; c.password = "a&b";
      c.successPattern = "Accepted";
      new HttpSmsClient(c).send("+15551234567", "AHU-1 fault & trip");
      eq("username=kannel&password=a%26b&to=%2B15551234567&text=AHU-1%20fault%20%26%20trip", query.get());

      c.successPattern = "^OK";
      try { new HttpSmsClient(c).send("+15551234567", "x"); throw new AssertionError("expected failure"); }
      catch (IOException expected) { check(expected.getMessage().contains("success pattern"), expected.getMessage()); }
    }
    finally { srv.stop(0); }
  }

  static String readAll(InputStream in) throws IOException
  {
    ByteArrayOutputStream b = new ByteArrayOutputStream();
    byte[] buf = new byte[1024]; int n;
    while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
    return new String(b.toByteArray(), StandardCharsets.UTF_8);
  }

  // ------------------------------------------------------------------ dispatcher

  static final class FakeTransport implements SmsDispatcher.Transport
  {
    final String name; volatile int failuresLeft; final List<String> sent = new CopyOnWriteArrayList<>();
    FakeTransport(String name, int failures) { this.name = name; this.failuresLeft = failures; }
    public String transportName() { return name; }
    public void send(String phone, String text) throws Exception
    {
      if (failuresLeft > 0) { failuresLeft--; throw new IOException(name + " down"); }
      sent.add(phone + ":" + text);
    }
    public List<AtModem.Inbound> pollInbound() { return Collections.emptyList(); }
    public boolean supportsInbound() { return false; }
  }

  static final class RecordingListener implements SmsDispatcher.Listener
  {
    final List<String> events = new CopyOnWriteArrayList<>();
    final CountDownLatch done;
    RecordingListener(int expectedFinal) { done = new CountDownLatch(expectedFinal); }
    public void onSent(OutgoingSms m, SmsDispatcher.Transport via) { events.add("sent:" + m.text + "@" + via.transportName()); done.countDown(); }
    public void onAttemptFailed(SmsDispatcher.Transport via, OutgoingSms m, Exception e) { events.add("attemptFailed:" + via.transportName()); }
    public void onFailed(OutgoingSms m, String reason, boolean retry) { events.add("failed:" + m.text + ":" + retry); if (!retry) done.countDown(); }
    public void onInbound(SmsDispatcher.Transport via, AtModem.Inbound msg) {}
    public void onQueueChanged(int size) {}
  }

  static void dispatcher() throws Exception
  {
    FakeTransport primary = new FakeTransport("modem", 1);   // fails once -> failover
    FakeTransport backup = new FakeTransport("http", 0);
    List<SmsDispatcher.Transport> list = new ArrayList<>(Arrays.asList(primary, backup));
    RecordingListener l = new RecordingListener(3);
    SmsDispatcher.Config c = new SmsDispatcher.Config();
    c.retryDelayMs = 100; c.maxRetries = 1; c.duplicateWindowMs = 60000; c.inboundPollMs = 0;
    SmsDispatcher d = new SmsDispatcher("t", () -> list, l, c);

    check(d.enqueue(new OutgoingSms("+1555", "A", "test", "x", 5)), "queued");
    check(!d.enqueue(new OutgoingSms("+1555", "A", "test", "x", 5)), "duplicate suppressed");
    check(d.enqueue(new OutgoingSms("+1555", "B-urgent", "test", "x", 1)), "queued urgent");
    d.start();
    long until = System.currentTimeMillis() + 5000;
    while (primary.sent.size() + backup.sent.size() < 2 && System.currentTimeMillis() < until) Thread.sleep(20);
    // third message: all gateways fail -> retried once then given up
    primary.failuresLeft = 10; backup.failuresLeft = 10;
    d.enqueue(new OutgoingSms("+1666", "C", "test", "x", 5));
    check(l.done.await(5, TimeUnit.SECONDS), "completed: " + l.events);
    d.stop();

    eq("sent:B-urgent@http", l.events.get(1));                 // priority first, failed over from modem
    check(l.events.contains("sent:A@modem"), l.events.toString());
    check(l.events.contains("failed:C:true"), l.events.toString());
    check(l.events.contains("failed:C:false"), l.events.toString());
  }

  static void dispatcherRateLimit() throws Exception
  {
    FakeTransport t = new FakeTransport("gw", 0);
    RecordingListener l = new RecordingListener(2);
    SmsDispatcher.Config c = new SmsDispatcher.Config();
    c.maxPerHour = 2; c.duplicateWindowMs = 0; c.inboundPollMs = 0;
    SmsDispatcher d = new SmsDispatcher("rl", () -> Collections.<SmsDispatcher.Transport>singletonList(t), l, c);
    for (int i = 0; i < 4; i++) d.enqueue(new OutgoingSms("+1", "m" + i, "o", "r", 5));
    d.start();
    check(l.done.await(5, TimeUnit.SECONDS), "two sent");
    Thread.sleep(300);
    d.stop();
    eq(2, t.sent.size());
    eq(2, d.queueSize());                                    // held, not dropped
  }

  static void httpApiKeyAndSender() throws Exception
  {
    HttpSmsClient.Config c = new HttpSmsClient.Config();
    c.apiKey = "sub-key-999";
    c.username = "dev-123";
    c.senderId = "BMS-HQ";
    HttpSmsClient client = new HttpSmsClient(c);
    Map<String, String> v = client.values("+971 50 123 4567", "Chiller Trip");
    eq("sub-key-999", v.get("apiKey"));
    eq("sub-key-999", v.get("token"));
    eq("sub-key-999", v.get("password"));
    eq("dev-123", v.get("username"));
    eq("dev-123", v.get("accountId"));
    eq("dev-123", v.get("deviceId"));
    eq("BMS-HQ", v.get("sender"));
    eq("BMS-HQ", v.get("senderId"));
  }

  static void emailToSmsFormatting() throws Exception
  {
    Map<String, String> vars = SmtpSmsClient.buildEmailVariables(
      "+971 50 123 4567", "High Temp Alert", "sms.clicksend.com", "key-abc", "user1", "", "TowerA");
    String to = SmtpSmsClient.resolveToAddress("{phoneDigits}@{domain}", vars);
    eq("971501234567@sms.clicksend.com", to);
    String subj = Templates.render("SMS {phone} [{apiKey}]", vars);
    eq("SMS +971501234567 [key-abc]", subj);
  }
}
