/*
 * Copyright (c) Tecum. All Rights Reserved.
 */
package com.tecum.sms.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Template-driven HTTP(S) SMS sender. Works with TextBee (300 SMS/month free tier
 * using only the API key at https://api.textbee.dev/api/v1/gateway/send-sms) as
 * well as any other cloud SMS subscription website or gateway (Twilio, ClickSend,
 * Vonage/Nexmo, Textbelt, MessageBird, Traccar, ntfy.sh, Jasmin, playSMS, Kannel).
 *
 * Placeholders: {phone} {phoneDigits} {message} {apiKey} {token} {username}
 *               {accountId} {deviceId} {password} {sender} {senderId}.
 */
public class HttpSmsClient
{
  public static final class Config
  {
    public String method = "POST";
    public String url = "https://api.textbee.dev/api/v1/gateway/send-sms";
    public String contentType = "application/json";
    public String headers = "x-api-key: {apiKey}";
    public String body = "{\"recipients\":[\"{phone}\"],\"message\":\"{message}\"}";
    public String apiKey = "";
    public String username = "";
    public String password = "";
    public String senderId = "";
    public boolean basicAuth;
    public String successPattern = "";
    public int timeoutMs = 15000;
  }

  public static final class Result
  {
    public final int code;
    public final String body;
    Result(int code, String body) { this.code = code; this.body = body; }
  }

  private final Config cfg;

  public HttpSmsClient(Config cfg) { this.cfg = cfg; }

  public Result send(String phone, String text) throws IOException
  {
    Map<String, String> v = values(phone, text);
    String renderedUrl = Templates.render(cfg.url, encodeAll(v, Encoding.URL));
    // Automatically upgrade legacy TextBee URLs (/devices//send-sms) when only API key is provided
    if (renderedUrl.contains("textbee.dev") && renderedUrl.contains("/devices//send-sms"))
    {
      renderedUrl = renderedUrl.replace("/devices//send-sms", "/send-sms");
    }

    String method = cfg.method == null || cfg.method.trim().isEmpty() ? "POST" : cfg.method.trim().toUpperCase();
    boolean hasBody = !"GET".equals(method) && cfg.body != null && !cfg.body.trim().isEmpty();
    String body = null;
    if (hasBody)
    {
      String ct = cfg.contentType == null ? "" : cfg.contentType.toLowerCase();
      Encoding enc = ct.contains("json") ? Encoding.JSON : ct.contains("x-www-form-urlencoded") ? Encoding.URL : Encoding.RAW;
      body = Templates.render(cfg.body, encodeAll(v, enc));
    }

    try
    {
      return executeHttp(method, renderedUrl, v, body);
    }
    catch (IOException primaryEx)
    {
      // Fallback for TextBee accounts that require deviceId resolution via GET /api/v1/gateway/devices
      if (renderedUrl.contains("textbee.dev") && renderedUrl.endsWith("/gateway/send-sms"))
      {
        String key = v.get("apiKey");
        if (key != null && !key.isEmpty())
        {
          String resolvedDeviceId = resolveTextBeeDefaultDeviceId(renderedUrl, key);
          if (resolvedDeviceId != null && !resolvedDeviceId.isEmpty())
          {
            String deviceUrl = renderedUrl.substring(0, renderedUrl.length() - "/send-sms".length())
              + "/devices/" + encode(resolvedDeviceId, Encoding.URL) + "/send-sms";
            return executeHttp(method, deviceUrl, v, body);
          }
        }
      }
      throw primaryEx;
    }
  }

  private Result executeHttp(String method, String url, Map<String, String> v, String body) throws IOException
  {
    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
    try
    {
      c.setConnectTimeout(cfg.timeoutMs);
      c.setReadTimeout(cfg.timeoutMs);
      c.setRequestMethod(method);
      c.setUseCaches(false);
      c.setInstanceFollowRedirects(true);
      c.setRequestProperty("User-Agent", "TecumSMS-Niagara-Driver/1.0");
      if (cfg.basicAuth && cfg.username != null && !cfg.username.isEmpty())
      {
        String secret = (cfg.password != null && !cfg.password.isEmpty())
          ? cfg.password
          : (cfg.apiKey == null ? "" : cfg.apiKey);
        String token = Base64.getEncoder().encodeToString(
          (cfg.username + ":" + secret).getBytes(StandardCharsets.UTF_8));
        c.setRequestProperty("Authorization", "Basic " + token);
      }
      for (Map.Entry<String, String> h : parseHeaders(Templates.render(cfg.headers, v)).entrySet())
        c.setRequestProperty(h.getKey(), h.getValue());
      if (body != null)
      {
        c.setDoOutput(true);
        if (cfg.contentType != null && !cfg.contentType.isEmpty())
          c.setRequestProperty("Content-Type", cfg.contentType.contains("charset") ? cfg.contentType : cfg.contentType + "; charset=UTF-8");
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(bytes.length);
        try (OutputStream os = c.getOutputStream()) { os.write(bytes); }
      }
      int code = c.getResponseCode();
      String resp = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
      if (code < 200 || code > 299)
        throw new IOException("HTTP " + code + ": " + abbreviate(resp, 200));
      if (cfg.successPattern != null && !cfg.successPattern.trim().isEmpty()
          && !Pattern.compile(cfg.successPattern.trim(), Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(resp).find())
        throw new IOException("Gateway response did not match success pattern: " + abbreviate(resp, 200));
      return new Result(code, resp);
    }
    finally { c.disconnect(); }
  }

  /**
   * Queries GET https://api.textbee.dev/api/v1/gateway/devices using only x-api-key
   * to auto-discover the account's first device _id if needed.
   */
  private String resolveTextBeeDefaultDeviceId(String sendSmsUrl, String apiKey)
  {
    String devicesUrl = sendSmsUrl.substring(0, sendSmsUrl.length() - "/send-sms".length()) + "/devices";
    HttpURLConnection c = null;
    try
    {
      c = (HttpURLConnection) new URL(devicesUrl).openConnection();
      c.setConnectTimeout(cfg.timeoutMs);
      c.setReadTimeout(cfg.timeoutMs);
      c.setRequestMethod("GET");
      c.setRequestProperty("x-api-key", apiKey);
      c.setRequestProperty("Accept", "application/json");
      c.setRequestProperty("User-Agent", "TecumSMS-Niagara-Driver/1.0");
      int code = c.getResponseCode();
      if (code >= 200 && code <= 299)
      {
        String json = read(c.getInputStream());
        Matcher m = Pattern.compile("\"_id\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        if (m.find()) return m.group(1);
      }
    }
    catch (Exception ignored) {}
    finally
    {
      if (c != null) c.disconnect();
    }
    return null;
  }

  Map<String, String> values(String phone, String text)
  {
    Map<String, String> v = new HashMap<>();
    String n = PhoneNumbers.normalize(phone);
    String user = cfg.username == null ? "" : cfg.username;
    String key = (cfg.apiKey != null && !cfg.apiKey.isEmpty())
      ? cfg.apiKey
      : (cfg.password != null && !cfg.password.isEmpty() ? cfg.password : user);
    String pass = (cfg.password != null && !cfg.password.isEmpty())
      ? cfg.password
      : (cfg.apiKey == null ? "" : cfg.apiKey);
    String sender = (cfg.senderId != null && !cfg.senderId.isEmpty()) ? cfg.senderId : "TecumSMS";

    v.put("phone", n);
    v.put("phoneDigits", PhoneNumbers.digitsOnly(n));
    v.put("message", text);
    v.put("apiKey", key);
    v.put("token", key);
    v.put("username", user);
    v.put("accountId", user);
    v.put("deviceId", user);
    v.put("password", pass);
    v.put("sender", sender);
    v.put("senderId", sender);
    return v;
  }

  enum Encoding { URL, JSON, RAW }

  static Map<String, String> encodeAll(Map<String, String> v, Encoding e)
  {
    Map<String, String> out = new HashMap<>();
    for (Map.Entry<String, String> x : v.entrySet())
      out.put(x.getKey(), encode(x.getValue(), e));
    return out;
  }

  static String encode(String s, Encoding e)
  {
    switch (e)
    {
      case URL:
        try { return URLEncoder.encode(s, "UTF-8").replace("+", "%20"); }
        catch (UnsupportedEncodingException x) { throw new IllegalStateException(x); }
      case JSON: return jsonEscape(s);
      default: return s;
    }
  }

  public static String jsonEscape(String s)
  {
    StringBuilder sb = new StringBuilder(s.length() + 8);
    for (int i = 0; i < s.length(); i++)
    {
      char c = s.charAt(i);
      switch (c)
      {
        case '"': sb.append("\\\""); break;
        case '\\': sb.append("\\\\"); break;
        case '\n': sb.append("\\n"); break;
        case '\r': sb.append("\\r"); break;
        case '\t': sb.append("\\t"); break;
        default:
          if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
          else sb.append(c);
      }
    }
    return sb.toString();
  }

  static Map<String, String> parseHeaders(String text)
  {
    Map<String, String> h = new java.util.LinkedHashMap<>();
    if (text == null) return h;
    for (String line : text.split("\\r?\\n|;;"))
    {
      int i = line.indexOf(':');
      if (i <= 0) continue;
      h.put(line.substring(0, i).trim(), line.substring(i + 1).trim());
    }
    return h;
  }

  private static String read(InputStream is) throws IOException
  {
    if (is == null) return "";
    try (InputStream s = is)
    {
      ByteArrayOutputStream b = new ByteArrayOutputStream();
      byte[] buf = new byte[4096];
      int n;
      while ((n = s.read(buf)) > 0 && b.size() < 65536) b.write(buf, 0, n);
      return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }
  }

  static String abbreviate(String s, int max)
  {
    if (s == null) return "";
    String t = s.replaceAll("\\s+", " ").trim();
    return t.length() <= max ? t : t.substring(0, max) + "...";
  }
}
