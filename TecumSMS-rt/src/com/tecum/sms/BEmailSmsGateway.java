/*
 * Copyright (c) Tecum. All Rights Reserved.
 */
package com.tecum.sms;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Map;

import javax.baja.naming.BOrd;
import javax.baja.security.BPassword;
import javax.baja.sys.BBoolean;
import javax.baja.sys.BFacets;
import javax.baja.sys.BIcon;
import javax.baja.sys.BInteger;
import javax.baja.sys.BRelTime;
import javax.baja.sys.Flags;
import javax.baja.sys.Property;
import javax.baja.sys.Sys;
import javax.baja.sys.Type;

import com.tecum.sms.core.SmtpSmsClient;
import com.tecum.sms.core.Templates;

/**
 * Email-to-SMS Gateway: sends SMS messages using Niagara's built-in EmailService
 * (station:|slot:/Services/EmailService or any OutgoingAccount) OR via a direct
 * SMTP/SMTPS server to any Email-to-SMS gateway or carrier domain
 * (e.g. {phoneDigits}@sms.clicksend.com, {phoneDigits}@textmagic.com,
 * {phoneDigits}@vtext.com, {phoneDigits}@txt.att.net, or a fixed gateway address
 * with {phone} and {apiKey} in the Subject/Body).
 */
public class BEmailSmsGateway extends BSmsGateway
{
  /** True = route through Niagara's EmailService / OutgoingAccount; False = direct SMTP connection. */
  public static final Property useNiagaraEmailService = newProperty(Flags.SUMMARY, true, null);
  public boolean getUseNiagaraEmailService() { return getBoolean(useNiagaraEmailService); }
  public void setUseNiagaraEmailService(boolean v) { setBoolean(useNiagaraEmailService, v, null); }

  /** ORD of Niagara EmailService or a specific BOutgoingAccount under EmailService. */
  public static final Property emailServiceOrd = newProperty(0,
    BOrd.make("station:|slot:/Services/EmailService"), null);
  public BOrd getEmailServiceOrd() { return (BOrd) get(emailServiceOrd); }
  public void setEmailServiceOrd(BOrd v) { set(emailServiceOrd, v, null); }

  /** Email-to-SMS provider or telecom carrier domain (e.g. sms.clicksend.com, textmagic.com, vtext.com). */
  public static final Property smsGatewayDomain = newProperty(Flags.SUMMARY, "sms.clicksend.com",
    BFacets.make(BFacets.FIELD_WIDTH, BInteger.make(40)));
  public String getSmsGatewayDomain() { return getString(smsGatewayDomain); }
  public void setSmsGatewayDomain(String v) { setString(smsGatewayDomain, v, null); }

  /** Recipient email format: {phoneDigits}@{domain}, {phone}@{domain}, or a fixed gateway email. */
  public static final Property toAddressFormat = newProperty(0, "{phoneDigits}@{domain}",
    BFacets.make(BFacets.FIELD_WIDTH, BInteger.make(45)));
  public String getToAddressFormat() { return getString(toAddressFormat); }
  public void setToAddressFormat(String v) { setString(toAddressFormat, v, null); }

  /** Subject line template. Placeholders: {phone} {phoneDigits} {apiKey} {username} {sender} {message}. */
  public static final Property subjectTemplate = newProperty(0, "SMS {phone}",
    BFacets.make(BFacets.FIELD_WIDTH, BInteger.make(45)));
  public String getSubjectTemplate() { return getString(subjectTemplate); }
  public void setSubjectTemplate(String v) { setString(subjectTemplate, v, null); }

  /** Email body template. Placeholders: {message} {phone} {phoneDigits} {apiKey} {sender}. */
  public static final Property bodyTemplate = newProperty(0, "{message}",
    BFacets.make(BFacets.MULTI_LINE, BBoolean.TRUE));
  public String getBodyTemplate() { return getString(bodyTemplate); }
  public void setBodyTemplate(String v) { setString(bodyTemplate, v, null); }

  /** Optional API Key / Token for Email-to-SMS providers that authenticate via {apiKey} in Subject/Body. */
  public static final Property apiKey = newProperty(Flags.SUMMARY, "",
    BFacets.make(BFacets.FIELD_WIDTH, BInteger.make(45)));
  public String getApiKey() { return getString(apiKey); }
  public void setApiKey(String v) { setString(apiKey, v, null); }

  /** Optional Sender ID / From number for {sender} placeholder. */
  public static final Property senderId = newProperty(0, "", null);
  public String getSenderId() { return getString(senderId); }
  public void setSenderId(String v) { setString(senderId, v, null); }

  // ------------------------------------------------------------------ Direct SMTP settings (when useNiagaraEmailService = false)

  public static final Property smtpHost = newProperty(0, "smtp.gmail.com", null);
  public String getSmtpHost() { return getString(smtpHost); }
  public void setSmtpHost(String v) { setString(smtpHost, v, null); }

  public static final Property smtpPort = newProperty(0, 587,
    BFacets.make(BFacets.MIN, BInteger.make(1), BFacets.MAX, BInteger.make(65535)));
  public int getSmtpPort() { return getInt(smtpPort); }
  public void setSmtpPort(int v) { setInt(smtpPort, v, null); }

  public static final Property useSsl = newProperty(0, false, null);
  public boolean getUseSsl() { return getBoolean(useSsl); }
  public void setUseSsl(boolean v) { setBoolean(useSsl, v, null); }

  public static final Property useStartTls = newProperty(0, true, null);
  public boolean getUseStartTls() { return getBoolean(useStartTls); }
  public void setUseStartTls(boolean v) { setBoolean(useStartTls, v, null); }

  public static final Property smtpUsername = newProperty(0, "", null);
  public String getSmtpUsername() { return getString(smtpUsername); }
  public void setSmtpUsername(String v) { setString(smtpUsername, v, null); }

  public static final Property smtpPassword = newProperty(0, BPassword.DEFAULT, null);
  public BPassword getSmtpPassword() { return (BPassword) get(smtpPassword); }
  public void setSmtpPassword(BPassword v) { set(smtpPassword, v, null); }

  public static final Property fromAddress = newProperty(0, "niagara-sms@localhost", null);
  public String getFromAddress() { return getString(fromAddress); }
  public void setFromAddress(String v) { setString(fromAddress, v, null); }

  public static final Property timeout = newProperty(0, BRelTime.makeSeconds(15), null);
  public BRelTime getTimeout() { return (BRelTime) get(timeout); }
  public void setTimeout(BRelTime v) { set(timeout, v, null); }

  @Override
  public Type getType() { return TYPE; }
  public static final Type TYPE = Sys.loadType(BEmailSmsGateway.class);

  private String resolveEffectiveApiKey()
  {
    String k = getApiKey();
    if (k != null && !k.trim().isEmpty()) return k.trim();
    BSmsNetwork net = BSmsNetwork.findFor(this);
    if (net != null && !net.getSubscriptionApiKey().trim().isEmpty())
      return net.getSubscriptionApiKey().trim();
    return "";
  }

  private String resolveEffectiveSenderId()
  {
    String s = getSenderId();
    if (s != null && !s.trim().isEmpty()) return s.trim();
    BSmsNetwork net = BSmsNetwork.findFor(this);
    if (net != null && !net.getSubscriptionSenderId().trim().isEmpty())
      return net.getSubscriptionSenderId().trim();
    return "TecumSMS";
  }

  @Override
  protected void doSend(String phone, String text, int ref) throws Exception
  {
    String key = resolveEffectiveApiKey();
    String sender = resolveEffectiveSenderId();
    Map<String, String> vars = SmtpSmsClient.buildEmailVariables(
      phone, text, getSmsGatewayDomain(), key, getSmtpUsername(), getSmtpPassword().getValue(), sender);
    String toAddr = SmtpSmsClient.resolveToAddress(getToAddressFormat(), vars);
    String subj = Templates.render(getSubjectTemplate(), vars);
    String body = Templates.render(getBodyTemplate(), vars);

    if (getUseNiagaraEmailService())
    {
      NiagaraEmailHelper.sendViaNiagaraEmail(this, getEmailServiceOrd(), toAddr, subj, body);
    }
    else
    {
      SmtpSmsClient.Config c = new SmtpSmsClient.Config();
      c.smtpHost = getSmtpHost().trim();
      c.smtpPort = getSmtpPort();
      c.useSsl = getUseSsl();
      c.useStartTls = getUseStartTls();
      c.username = getSmtpUsername().trim();
      c.password = getSmtpPassword().getValue();
      c.apiKey = key;
      c.fromAddress = getFromAddress().trim();
      c.smsGatewayDomain = getSmsGatewayDomain().trim();
      c.toAddressFormat = getToAddressFormat().trim();
      c.subjectTemplate = getSubjectTemplate();
      c.bodyTemplate = getBodyTemplate();
      c.senderId = sender;
      c.timeoutMs = (int) Math.max(1000, getTimeout().getMillis());
      new SmtpSmsClient(c).send(phone, text);
    }
  }

  @Override
  protected String doTest() throws Exception
  {
    if (getUseNiagaraEmailService())
    {
      return NiagaraEmailHelper.checkNiagaraEmailTarget(this, getEmailServiceOrd());
    }
    try (Socket s = new Socket())
    {
      s.connect(new InetSocketAddress(getSmtpHost().trim(), getSmtpPort()),
        (int) Math.max(1000, getTimeout().getMillis()));
      return "SMTP server reachable at " + getSmtpHost().trim() + ":" + getSmtpPort();
    }
  }

  @Override
  public BIcon getIcon() { return ICON; }
  private static final BIcon ICON = BIcon.std("mail.png");
}
