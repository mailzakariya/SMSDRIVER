/*
 * Copyright (c) Tecum. All Rights Reserved.
 */
package com.tecum.sms;

import java.net.HttpURLConnection;
import java.net.URL;

import javax.baja.security.BPassword;
import javax.baja.sys.BBoolean;
import javax.baja.sys.BFacets;
import javax.baja.sys.BIcon;
import javax.baja.sys.BInteger;
import javax.baja.sys.BRelTime;
import javax.baja.sys.Context;
import javax.baja.sys.Flags;
import javax.baja.sys.Property;
import javax.baja.sys.Sys;
import javax.baja.sys.Type;

import com.tecum.sms.core.HttpSmsClient;

/**
 * Sends SMS through any open-source internet SMS platform, commercial SMS
 * subscription website, or HTTP(S) API.
 *
 * Choose a preset (TextBee Cloud, Android SMS Gateway Cloud/Local, Traccar SMS,
 * ntfy.sh, Twilio, ClickSend, Vonage/Nexmo, Textbelt, MessageBird, Jasmin SMS,
 * playSMS, Kannel, Teltonika router) or build a custom request.
 *
 * Placeholders: {phone} (+E.164) {phoneDigits} {message} {apiKey} {token}
 *               {username} {accountId} {deviceId} {password} {sender} {senderId}
 */
public class BHttpGateway extends BSmsGateway
{
  public static final Property preset = newProperty(Flags.SUMMARY, BHttpPreset.DEFAULT, null);
  public BHttpPreset getPreset() { return (BHttpPreset) get(preset); }
  public void setPreset(BHttpPreset v) { set(preset, v, null); }

  /** API Key or Bearer Token for the SMS subscription website. */
  public static final Property apiKey = newProperty(Flags.SUMMARY, "",
    BFacets.make(BFacets.FIELD_WIDTH, BInteger.make(55)));
  public String getApiKey() { return getString(apiKey); }
  public void setApiKey(String v) { setString(apiKey, v, null); }

  /** Username, Device ID, Topic, or Account SID depending on the preset. */
  public static final Property username = newProperty(Flags.SUMMARY, "",
    BFacets.make(BFacets.FIELD_WIDTH, BInteger.make(45)));
  public String getUsername() { return getString(username); }
  public void setUsername(String v) { setString(username, v, null); }

  /** Optional Password or Auth Secret (you can enter your API key in either Api Key or Password). */
  public static final Property password = newProperty(0, BPassword.DEFAULT, null);
  public BPassword getPassword() { return (BPassword) get(password); }
  public void setPassword(BPassword v) { set(password, v, null); }

  /** Optional Sender ID or From phone number (used by Twilio, ClickSend, Vonage, MessageBird). */
  public static final Property senderId = newProperty(0, "", null);
  public String getSenderId() { return getString(senderId); }
  public void setSenderId(String v) { setString(senderId, v, null); }

  public static final Property method = newProperty(0, "POST", null);
  public String getMethod() { return getString(method); }
  public void setMethod(String v) { setString(method, v, null); }

  public static final Property url = newProperty(0, "https://api.textbee.dev/api/v1/gateway/devices/{username}/send-sms",
    BFacets.make(BFacets.FIELD_WIDTH, BInteger.make(70)));
  public String getUrl() { return getString(url); }
  public void setUrl(String v) { setString(url, v, null); }

  public static final Property contentType = newProperty(0, "application/json", null);
  public String getContentType() { return getString(contentType); }
  public void setContentType(String v) { setString(contentType, v, null); }

  /** Extra request headers, one "Name: value" per line. */
  public static final Property headers = newProperty(0, "x-api-key: {apiKey}",
    BFacets.make(BFacets.MULTI_LINE, BBoolean.TRUE));
  public String getHeaders() { return getString(headers); }
  public void setHeaders(String v) { setString(headers, v, null); }

  public static final Property body = newProperty(0,
    "{\"recipients\":[\"{phone}\"],\"message\":\"{message}\"}",
    BFacets.make(BFacets.MULTI_LINE, BBoolean.TRUE));
  public String getBody() { return getString(body); }
  public void setBody(String v) { setString(body, v, null); }

  /** Send username/password (or username/apiKey) as HTTP Basic authentication. */
  public static final Property basicAuth = newProperty(0, false, null);
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
    BSmsNetwork net = BSmsNetwork.findFor(this);
    String effApiKey = getApiKey().trim();
    if (effApiKey.isEmpty() && net != null) effApiKey = net.getSubscriptionApiKey().trim();

    String effUser = getUsername().trim();
    if (effUser.isEmpty() && net != null) effUser = net.getSubscriptionAccountId().trim();

    String effSender = getSenderId().trim();
    if (effSender.isEmpty() && net != null) effSender = net.getSubscriptionSenderId().trim();

    HttpSmsClient.Config c = new HttpSmsClient.Config();
    c.method = getMethod();
    c.url = getUrl().trim();
    c.contentType = getContentType();
    c.headers = getHeaders();
    c.body = getBody();
    c.apiKey = effApiKey;
    c.username = effUser;
    c.password = getPassword().getValue();
    c.senderId = effSender;
    c.basicAuth = getBasicAuth();
    c.successPattern = getSuccessPattern();
    c.timeoutMs = (int) Math.max(1000, getTimeout().getMillis());
    return c;
  }

  public static HttpSmsClient.Config buildConfigForPreset(
    int ordinal, String customUrl, String apiKey, String username, String password, String senderId, int timeoutMs)
  {
    BHttpGateway temp = new BHttpGateway();
    temp.applyPreset(ordinal);
    HttpSmsClient.Config c = new HttpSmsClient.Config();
    c.method = temp.getMethod();
    c.url = (customUrl != null && !customUrl.trim().isEmpty()) ? customUrl.trim() : temp.getUrl().trim();
    c.contentType = temp.getContentType();
    c.headers = temp.getHeaders();
    c.body = temp.getBody();
    c.apiKey = apiKey == null ? "" : apiKey.trim();
    c.username = username == null ? "" : username.trim();
    c.password = password == null ? "" : password;
    c.senderId = senderId == null ? "" : senderId.trim();
    c.basicAuth = temp.getBasicAuth();
    c.successPattern = temp.getSuccessPattern();
    c.timeoutMs = Math.max(1000, timeoutMs);
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
    if (p == preset && isMounted() && cx != Context.decoding) applyPreset(getPreset().getOrdinal());
  }

  void applyPreset(int ordinal)
  {
    switch (ordinal)
    {
      case BHttpPreset.TEXTBEE_CLOUD:
        // TextBee (open source MIT, textbee.dev): username = Device ID, apiKey = API Key
        setMethod("POST");
        setUrl("https://api.textbee.dev/api/v1/gateway/devices/{username}/send-sms");
        setContentType("application/json");
        setHeaders("x-api-key: {apiKey}");
        setBody("{\"recipients\":[\"{phone}\"],\"message\":\"{message}\"}");
        setBasicAuth(false);
        setSuccessPattern("");
        break;
      case BHttpPreset.ANDROID_SMS_GATEWAY_CLOUD:
        // SMS Gateway for Android (open source Apache-2.0) Internet Cloud server mode
        setMethod("POST");
        setUrl("https://api.sms-gate.app/3rdparty/v1/message");
        setContentType("application/json");
        setHeaders("");
        setBody("{\"textMessage\":{\"text\":\"{message}\"},\"phoneNumbers\":[\"{phone}\"]}");
        setBasicAuth(true);
        setSuccessPattern("");
        break;
      case BHttpPreset.ANDROID_SMS_GATEWAY:
        // SMS Gateway for Android (open source Apache-2.0), local network server mode
        setMethod("POST");
        setUrl("http://<phone-ip>:8080/message");
        setContentType("application/json");
        setHeaders("");
        setBody("{\"textMessage\":{\"text\":\"{message}\"},\"phoneNumbers\":[\"{phone}\"]}");
        setBasicAuth(true);
        setSuccessPattern("");
        break;
      case BHttpPreset.TRACCAR_SMS:
        // Traccar SMS Gateway (open source Apache-2.0) cloud mode; apiKey = Cloud API Token
        setMethod("POST");
        setUrl("https://www.traccar.org/sms/");
        setContentType("application/json");
        setHeaders("Authorization: {apiKey}");
        setBody("{\"to\":\"{phone}\",\"message\":\"{message}\"}");
        setBasicAuth(false);
        setSuccessPattern("");
        break;
      case BHttpPreset.NTFY_SH:
        // ntfy.sh (open source Apache-2.0/GPLv2) internet push/alert service; username = topic name
        setMethod("POST");
        setUrl("https://ntfy.sh/{username}");
        setContentType("text/plain");
        setHeaders("Title: Niagara SMS Alert ({phone})\nPriority: high");
        setBody("{message}");
        setBasicAuth(false);
        setSuccessPattern("");
        break;
      case BHttpPreset.JASMIN_HTTP:
        // Jasmin SMS Gateway (open source Apache-2.0) HTTP client connector
        setMethod("GET");
        setUrl("http://<jasmin-host>:1401/send?username={username}&password={apiKey}&to={phone}&content={message}");
        setContentType("");
        setHeaders("");
        setBody("");
        setBasicAuth(false);
        setSuccessPattern("Success");
        break;
      case BHttpPreset.PLAY_SMS:
        // playSMS (open source) webservice; apiKey = webservice token
        setMethod("GET");
        setUrl("http://<server>/playsms/index.php?app=ws&op=pv&u={username}&h={apiKey}&to={phone}&msg={message}");
        setContentType("");
        setHeaders("");
        setBody("");
        setBasicAuth(false);
        setSuccessPattern("\"status\"\\s*:\\s*\"OK\"|\"error\"\\s*:\\s*\"0\"");
        break;
      case BHttpPreset.KANNEL:
        // Kannel (open source) sendsms interface
        setMethod("GET");
        setUrl("http://<kannel-host>:13013/cgi-bin/sendsms?username={username}&password={apiKey}&to={phone}&text={message}&charset=UTF-8");
        setContentType("");
        setHeaders("");
        setBody("");
        setBasicAuth(false);
        setSuccessPattern("Accepted|Queued|Sent");
        break;
      case BHttpPreset.TELTONIKA_ROUTER:
        // Teltonika RUT/TRB series, legacy sms_send CGI
        setMethod("GET");
        setUrl("http://<router-ip>/cgi-bin/sms_send?username={username}&password={apiKey}&number={phone}&text={message}");
        setContentType("");
        setHeaders("");
        setBody("");
        setBasicAuth(false);
        setSuccessPattern("OK");
        break;
      case BHttpPreset.TWILIO:
        // Twilio SMS subscription: username = Account SID, apiKey = Auth Token, senderId = From phone number
        setMethod("POST");
        setUrl("https://api.twilio.com/2010-04-01/Accounts/{username}/Messages.json");
        setContentType("application/x-www-form-urlencoded");
        setHeaders("");
        setBody("To={phone}&From={sender}&Body={message}");
        setBasicAuth(true);
        setSuccessPattern("\"sid\"");
        break;
      case BHttpPreset.CLICKSEND:
        // ClickSend SMS subscription: username = ClickSend Username, apiKey = API Key
        setMethod("POST");
        setUrl("https://rest.clicksend.com/v3/sms/send");
        setContentType("application/json");
        setHeaders("");
        setBody("{\"messages\":[{\"source\":\"niagara\",\"from\":\"{sender}\",\"body\":\"{message}\",\"to\":\"{phone}\"}]}");
        setBasicAuth(true);
        setSuccessPattern("\"SUCCESS\"");
        break;
      case BHttpPreset.VONAGE:
        // Vonage / Nexmo SMS subscription: username = API Key, apiKey = API Secret, senderId = From
        setMethod("POST");
        setUrl("https://rest.nexmo.com/sms/json");
        setContentType("application/x-www-form-urlencoded");
        setHeaders("");
        setBody("api_key={username}&api_secret={apiKey}&to={phoneDigits}&from={sender}&text={message}");
        setBasicAuth(false);
        setSuccessPattern("\"status\"\\s*:\\s*\"0\"");
        break;
      case BHttpPreset.TEXTBELT:
        // Textbelt SMS API subscription: apiKey = Textbelt API Key
        setMethod("POST");
        setUrl("https://textbelt.com/text");
        setContentType("application/x-www-form-urlencoded");
        setHeaders("");
        setBody("phone={phone}&message={message}&key={apiKey}");
        setBasicAuth(false);
        setSuccessPattern("\"success\"\\s*:\\s*true");
        break;
      case BHttpPreset.MESSAGEBIRD:
        // MessageBird SMS subscription: apiKey = Live Access Key, senderId = Originator
        setMethod("POST");
        setUrl("https://rest.messagebird.com/messages");
        setContentType("application/json");
        setHeaders("Authorization: AccessKey {apiKey}");
        setBody("{\"originator\":\"{sender}\",\"recipients\":[\"{phoneDigits}\"],\"body\":\"{message}\"}");
        setBasicAuth(false);
        setSuccessPattern("\"id\"");
        break;
      default:
        break;
    }
  }

  @Override
  public BIcon getIcon() { return ICON; }
  private static final BIcon ICON = BIcon.std("cloud.png");
}
