/*
 * Copyright (c) Tecum. All Rights Reserved.
 */
package com.tecum.sms.core;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Pure-Java SMTP / SMTPS / STARTTLS Email-to-SMS client.
 * Formats an SMS into an email addressed to an Email-to-SMS gateway
 * (e.g. {phoneDigits}@{domain} such as 971501234567@sms.clicksend.com,
 * 1234567890@vtext.com, or a fixed gateway address with {phone} and {apiKey}
 * in the Subject or Body).
 */
public class SmtpSmsClient
{
  public static final class Config
  {
    public String smtpHost = "localhost";
    public int smtpPort = 587;
    public boolean useSsl = false;
    public boolean useStartTls = true;
    public String username = "";
    public String password = "";
    public String apiKey = "";
    public String fromAddress = "niagara-sms@localhost";
    public String smsGatewayDomain = "sms.clicksend.com";
    public String toAddressFormat = "{phoneDigits}@{domain}";
    public String subjectTemplate = "SMS {phone}";
    public String bodyTemplate = "{message}";
    public String senderId = "";
    public int timeoutMs = 15000;
  }

  private final Config cfg;

  public SmtpSmsClient(Config cfg)
  {
    this.cfg = cfg;
  }

  public static Map<String, String> buildEmailVariables(
    String phone, String text, String domain, String apiKey, String username, String password, String senderId)
  {
    Map<String, String> v = new HashMap<>();
    String n = PhoneNumbers.normalize(phone);
    String digits = PhoneNumbers.digitsOnly(n);
    String key = (apiKey != null && !apiKey.isEmpty()) ? apiKey : (password == null ? "" : password);
    String pass = (password != null && !password.isEmpty()) ? password : (apiKey == null ? "" : apiKey);
    String dom = (domain == null || domain.trim().isEmpty()) ? "sms.clicksend.com" : domain.trim();
    String sender = (senderId == null || senderId.trim().isEmpty()) ? "TecumSMS" : senderId.trim();

    v.put("phone", n);
    v.put("phoneDigits", digits);
    v.put("phonePlain", digits);
    v.put("domain", dom);
    v.put("message", text == null ? "" : text);
    v.put("apiKey", key);
    v.put("token", key);
    v.put("username", username == null ? "" : username);
    v.put("password", pass);
    v.put("sender", sender);
    v.put("senderId", sender);
    return v;
  }

  public static String resolveToAddress(String toFormat, Map<String, String> vars)
  {
    String fmt = (toFormat == null || toFormat.trim().isEmpty()) ? "{phoneDigits}@{domain}" : toFormat.trim();
    String rendered = Templates.render(fmt, vars).trim();
    if (!rendered.contains("@"))
    {
      String dom = vars.get("domain");
      if (dom != null && !dom.isEmpty()) rendered = rendered + "@" + dom;
    }
    return rendered;
  }

  public void send(String phone, String text) throws IOException
  {
    Map<String, String> vars = buildEmailVariables(
      phone, text, cfg.smsGatewayDomain, cfg.apiKey, cfg.username, cfg.password, cfg.senderId);
    String toAddr = resolveToAddress(cfg.toAddressFormat, vars);
    String subj = Templates.render(cfg.subjectTemplate == null ? "" : cfg.subjectTemplate, vars);
    String body = Templates.render(cfg.bodyTemplate == null || cfg.bodyTemplate.isEmpty() ? "{message}" : cfg.bodyTemplate, vars);
    String fromAddr = (cfg.fromAddress == null || cfg.fromAddress.trim().isEmpty()) ? "niagara@localhost" : cfg.fromAddress.trim();

    Socket raw = new Socket();
    raw.connect(new InetSocketAddress(cfg.smtpHost, cfg.smtpPort), cfg.timeoutMs);
    raw.setSoTimeout(cfg.timeoutMs);
    Socket sock = raw;
    try
    {
      if (cfg.useSsl)
      {
        sock = ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, cfg.smtpHost, cfg.smtpPort, true);
        ((SSLSocket) sock).startHandshake();
      }
      BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
      BufferedWriter out = new BufferedWriter(new OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8));

      expect(in, 220);
      sendCmd(out, "EHLO tecumsms.local");
      expect(in, 250);

      if (!cfg.useSsl && cfg.useStartTls)
      {
        sendCmd(out, "STARTTLS");
        expect(in, 220);
        sock = ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(sock, cfg.smtpHost, cfg.smtpPort, true);
        ((SSLSocket) sock).startHandshake();
        in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
        out = new BufferedWriter(new OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8));
        sendCmd(out, "EHLO tecumsms.local");
        expect(in, 250);
      }

      String authSecret = (cfg.password != null && !cfg.password.isEmpty())
        ? cfg.password
        : (cfg.apiKey == null ? "" : cfg.apiKey);
      if (cfg.username != null && !cfg.username.trim().isEmpty())
      {
        String plain = "\0" + cfg.username.trim() + "\0" + authSecret;
        String b64 = Base64.getEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
        sendCmd(out, "AUTH PLAIN " + b64);
        expect(in, 235);
      }

      sendCmd(out, "MAIL FROM:<" + stripBrackets(fromAddr) + ">");
      expect(in, 250);
      sendCmd(out, "RCPT TO:<" + stripBrackets(toAddr) + ">");
      expect2xx(in);
      sendCmd(out, "DATA");
      expect(in, 354);

      String dateHdr = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.ENGLISH).format(new Date());
      out.write("From: <" + stripBrackets(fromAddr) + ">\r\n");
      out.write("To: <" + stripBrackets(toAddr) + ">\r\n");
      out.write("Subject: " + subj.replace("\r", " ").replace("\n", " ") + "\r\n");
      out.write("Date: " + dateHdr + "\r\n");
      out.write("MIME-Version: 1.0\r\n");
      out.write("Content-Type: text/plain; charset=UTF-8\r\n\r\n");
      for (String line : body.split("\\r?\\n", -1))
      {
        if (line.startsWith(".")) out.write(".");
        out.write(line + "\r\n");
      }
      out.write(".\r\n");
      out.flush();
      expect(in, 250);

      sendCmd(out, "QUIT");
    }
    finally
    {
      try { sock.close(); } catch (Exception ignored) {}
    }
  }

  private static String stripBrackets(String addr)
  {
    String s = addr.trim();
    if (s.startsWith("<") && s.endsWith(">")) return s.substring(1, s.length() - 1).trim();
    return s;
  }

  private static void sendCmd(BufferedWriter out, String cmd) throws IOException
  {
    out.write(cmd + "\r\n");
    out.flush();
  }

  private static void expect(BufferedReader in, int expectedCode) throws IOException
  {
    String resp = readSmtpResponse(in);
    if (!resp.startsWith(Integer.toString(expectedCode)))
      throw new IOException("SMTP expected " + expectedCode + " but got: " + resp);
  }

  private static void expect2xx(BufferedReader in) throws IOException
  {
    String resp = readSmtpResponse(in);
    if (!resp.startsWith("2"))
      throw new IOException("SMTP error: " + resp);
  }

  private static String readSmtpResponse(BufferedReader in) throws IOException
  {
    StringBuilder sb = new StringBuilder();
    String line;
    while ((line = in.readLine()) != null)
    {
      sb.append(line);
      if (line.length() < 4 || line.charAt(3) != '-') break;
      sb.append(" | ");
    }
    if (sb.length() == 0) throw new IOException("SMTP server closed connection unexpectedly");
    return sb.toString();
  }
}
