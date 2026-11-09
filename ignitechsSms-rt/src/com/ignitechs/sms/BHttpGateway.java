/*
 * Copyright (c) Ignitechs. All Rights Reserved.
 */
package com.ignitechs.sms;

import java.net.HttpURLConnection;
import java.net.URL;

import javax.baja.security.BPassword;
import javax.baja.sys.BBoolean;
import javax.baja.sys.BFacets;
import javax.baja.sys.BIcon;
import javax.baja.sys.BInteger;
import javax.baja.sys.BRelTime;
import javax.baja.sys.Context;
import javax.baja.sys.Property;
import javax.baja.sys.Sys;
import javax.baja.sys.Type;

import com.ignitechs.sms.core.HttpSmsClient;

/**
 * Sends SMS through any HTTP(S) API. Choose a preset (Android SMS Gateway,
 * playSMS, Kannel, Teltonika router, Twilio) or build a custom request.
 *
 * Placeholders: {phone} (+E.164) {phoneDigits} {message} {username} {password}
 */
public class BHttpGateway extends BSmsGateway
{
  public static final Property preset = newProperty(0, BHttpPreset.DEFAULT, null);
  public BHttpPreset getPreset() { return (BHttpPreset) get(preset); }
  public void setPreset(BHttpPreset v) { set(preset, v, null); }

  public static final Property method = newProperty(0, "POST", null);
  public String getMethod() { return getString(method); }
  public void setMethod(String v) { setString(method, v, null); }

  public static final Property url = newProperty(0, "http://192.168.1.50:8080/message", BFacets.make(BFacets.FIELD_WIDTH, BInteger.make(70)));
  public String getUrl() { return getString(url); }
  public void setUrl(String v) { setString(url, v, null); }

  public static final Property contentType = newProperty(0, "application/json", null);
  public String getContentType() { return getString(contentType); }
  public void setContentType(String v) { setString(contentType, v, null); }

  /** Extra request headers, one "Name: value" per line. */
  public static final Property headers = newProperty(0, "", BFacets.make(BFacets.MULTI_LINE, BBoolean.TRUE));
  public String getHeaders() { return getString(headers); }
  public void setHeaders(String v) { setString(headers, v, null); }

  public static final Property body = newProperty(0,
    "{\"textMessage\":{\"text\":\"{message}\"},\"phoneNumbers\":[\"{phone}\"]}",
    BFacets.make(BFacets.MULTI_LINE, BBoolean.TRUE));
  public String getBody() { return getString(body); }
  public void setBody(String v) { setString(body, v, null); }

  public static final Property username = newProperty(0, "", null);
  public String getUsername() { return getString(username); }
  public void setUsername(String v) { setString(username, v, null); }

  public static final Property password = newProperty(0, BPassword.DEFAULT, null);
  public BPassword getPassword() { return (BPassword) get(password); }
  public void setPassword(BPassword v) { set(password, v, null); }

  /** Send username/password as HTTP Basic authentication. */
  public static final Property basicAuth = newProperty(0, true, null);
  public boolean getBasicAuth() { return getBoolean(basicAuth); }
  public void setBasicAuth(boolean v) { setBoolean(basicAuth, v, null); }

  /** Optional regular expression the response body must contain to count as success. */
  public static final Property successPattern = newProperty(0, "", null);
  public String getSuccessPattern() { return getString(successPattern); }
  public void setSuccessPattern(String v) { setString(successPattern, v, null); }

  public static final Property timeout = newProperty(0, BRelTime.makeSeconds(15), null);
  public BRelTime getTimeout() { return (BRelTime) get(timeout); }
  public void setTimeout(BRelTime v) { set(timeout, v, null); }

  @Override
  public Type getType() { return TYPE; }
  public static final Type TYPE = Sys.loadType(BHttpGateway.class);

  private HttpSmsClient.Config config()
  {
    HttpSmsClient.Config c = new HttpSmsClient.Config();
    c.method = getMethod();
    c.url = getUrl().trim();
    c.contentType = getContentType();
    c.headers = getHeaders();
    c.body = getBody();
    c.username = getUsername();
    c.password = getPassword().getValue();
    c.basicAuth = getBasicAuth();
    c.successPattern = getSuccessPattern();
    c.timeoutMs = (int) Math.max(1000, getTimeout().getMillis());
    return c;
  }

  @Override
  protected void doSend(String phone, String text, int ref) throws Exception
  {
    new HttpSmsClient(config()).send(phone, text);
  }

  /** Reachability check: the server answers (any HTTP status) on the base URL. */
  @Override
  protected String doTest() throws Exception
  {
    String u = getUrl().trim();
    int q = u.indexOf('?');
    if (q > 0) u = u.substring(0, q);
    int brace = u.indexOf('{');
    if (brace > 0) u = u.substring(0, u.lastIndexOf('/', brace) + 1);
    HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
    try
    {
      c.setConnectTimeout((int) Math.max(1000, getTimeout().getMillis()));
      c.setReadTimeout((int) Math.max(1000, getTimeout().getMillis()));
      c.setRequestMethod("GET");
      int code = c.getResponseCode();
      return getPreset().getDisplayTag(null) + " reachable (HTTP " + code + ")";
    }
    finally { c.disconnect(); }
  }

  /** Selecting a preset fills in the request template. */
  @Override
  public void changed(Property p, Context cx)
  {
    super.changed(p, cx);
    if (p == preset && isRunning() && cx != Context.decoding) applyPreset(getPreset().getOrdinal());
  }

  void applyPreset(int ordinal)
  {
    switch (ordinal)
    {
      case BHttpPreset.ANDROID_SMS_GATEWAY:
        // SMS Gateway for Android (open source, Apache-2.0), local server mode
        setMethod("POST");
        setUrl("http://<phone-ip>:8080/message");
        setContentType("application/json");
        setBody("{\"textMessage\":{\"text\":\"{message}\"},\"phoneNumbers\":[\"{phone}\"]}");
        setBasicAuth(true);
        setSuccessPattern("");
        break;
      case BHttpPreset.PLAY_SMS:
        // playSMS (open source) webservice; password = webservice token
        setMethod("GET");
        setUrl("http://<server>/playsms/index.php?app=ws&op=pv&u={username}&h={password}&to={phone}&msg={message}");
        setContentType("");
        setBody("");
        setBasicAuth(false);
        setSuccessPattern("\"status\"\\s*:\\s*\"OK\"|\"error\"\\s*:\\s*\"0\"");
        break;
      case BHttpPreset.KANNEL:
        // Kannel (open source) sendsms interface
        setMethod("GET");
        setUrl("http://<kannel-host>:13013/cgi-bin/sendsms?username={username}&password={password}&to={phone}&text={message}&charset=UTF-8");
        setContentType("");
        setBody("");
        setBasicAuth(false);
        setSuccessPattern("Accepted|Queued|Sent");
        break;
      case BHttpPreset.TELTONIKA_ROUTER:
        // Teltonika RUT/TRB series, legacy sms_send CGI (enable it in Services > Mobile Utilities)
        setMethod("GET");
        setUrl("http://<router-ip>/cgi-bin/sms_send?username={username}&password={password}&number={phone}&text={message}");
        setContentType("");
        setBody("");
        setBasicAuth(false);
        setSuccessPattern("OK");
        break;
      case BHttpPreset.TWILIO:
        // username = Account SID, password = Auth Token; set your Twilio number in the body
        setMethod("POST");
        setUrl("https://api.twilio.com/2010-04-01/Accounts/{username}/Messages.json");
        setContentType("application/x-www-form-urlencoded");
        setBody("To={phone}&From=%2B10000000000&Body={message}");
        setBasicAuth(true);
        setSuccessPattern("\"sid\"");
        break;
      default:
        break;
    }
  }

  @Override
  public BIcon getIcon() { return ICON; }
  private static final BIcon ICON = BIcon.std("cloud.png");
}
