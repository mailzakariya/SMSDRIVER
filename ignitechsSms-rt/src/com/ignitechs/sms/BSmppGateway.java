/*
 * Copyright (c) Ignitechs. All Rights Reserved.
 */
package com.ignitechs.sms;

import javax.baja.security.BPassword;
import javax.baja.sys.BFacets;
import javax.baja.sys.BIcon;
import javax.baja.sys.BInteger;
import javax.baja.sys.BRelTime;
import javax.baja.sys.Property;
import javax.baja.sys.Sys;
import javax.baja.sys.Type;

import com.ignitechs.sms.core.SmppClient;

/**
 * SMPP v3.4 client (transmitter). Use with a mobile operator / aggregator
 * SMPP account or an open-source SMSC such as Kannel or Jasmin.
 */
public class BSmppGateway extends BSmsGateway
{
  public static final Property address = newProperty(0, "", null);
  public String getAddress() { return getString(address); }
  public void setAddress(String v) { setString(address, v, null); }

  public static final Property port = newProperty(0, 2775, BFacets.make(BFacets.MIN, BInteger.make(1), BFacets.MAX, BInteger.make(65535)));
  public int getPort() { return getInt(port); }
  public void setPort(int v) { setInt(port, v, null); }

  public static final Property useTls = newProperty(0, false, null);
  public boolean getUseTls() { return getBoolean(useTls); }
  public void setUseTls(boolean v) { setBoolean(useTls, v, null); }

  public static final Property systemId = newProperty(0, "", null);
  public String getSystemId() { return getString(systemId); }
  public void setSystemId(String v) { setString(systemId, v, null); }

  public static final Property password = newProperty(0, BPassword.DEFAULT, null);
  public BPassword getPassword() { return (BPassword) get(password); }
  public void setPassword(BPassword v) { set(password, v, null); }

  public static final Property systemType = newProperty(0, "", null);
  public String getSystemType() { return getString(systemType); }
  public void setSystemType(String v) { setString(systemType, v, null); }

  /** Sender shown on the phone: alphanumeric (max 11 chars) or a number. */
  public static final Property senderId = newProperty(0, "BMS", null);
  public String getSenderId() { return getString(senderId); }
  public void setSenderId(String v) { setString(senderId, v, null); }

  public static final Property timeout = newProperty(0, BRelTime.makeSeconds(15), null);
  public BRelTime getTimeout() { return (BRelTime) get(timeout); }
  public void setTimeout(BRelTime v) { set(timeout, v, null); }

  @Override
  public Type getType() { return TYPE; }
  public static final Type TYPE = Sys.loadType(BSmppGateway.class);

  private SmppClient.Config config()
  {
    SmppClient.Config c = new SmppClient.Config();
    c.host = getAddress().trim();
    c.port = getPort();
    c.tls = getUseTls();
    c.systemId = getSystemId();
    c.password = getPassword().getValue();
    c.systemType = getSystemType();
    String sender = getSenderId().trim();
    c.sourceAddress = sender;
    boolean numeric = sender.matches("\\+?\\d+");
    c.sourceTon = numeric ? (sender.startsWith("+") ? 1 : 0) : 5;
    c.sourceNpi = numeric ? 1 : 0;
    if (numeric && sender.startsWith("+")) c.sourceAddress = sender.substring(1);
    c.timeoutMs = (int) Math.max(1000, getTimeout().getMillis());
    return c;
  }

  @Override
  protected void doSend(String phone, String text, int ref) throws Exception
  {
    if (getAddress().trim().isEmpty()) throw new IllegalStateException("SMPP host not configured");
    new SmppClient(config()).send(phone, text, ref);
  }

  @Override
  protected String doTest() throws Exception
  {
    if (getAddress().trim().isEmpty()) throw new IllegalStateException("SMPP host not configured");
    new SmppClient(config()).test();
    return "SMPP bind OK (" + getAddress() + ":" + getPort() + ")";
  }

  @Override
  public BIcon getIcon() { return ICON; }
  private static final BIcon ICON = BIcon.std("cloud.png");
}
