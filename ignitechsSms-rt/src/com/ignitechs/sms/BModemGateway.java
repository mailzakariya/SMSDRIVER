/*
 * Copyright (c) Ignitechs. All Rights Reserved.
 */
package com.ignitechs.sms;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

import javax.baja.security.BPassword;
import javax.baja.sys.Flags;
import javax.baja.sys.Property;
import javax.baja.sys.Sys;
import javax.baja.sys.Type;

import com.ignitechs.sms.core.AtModem;

/**
 * GSM / 3G / 4G modem using AT commands in PDU mode (long messages and
 * Unicode supported). Subclasses supply the byte stream (serial or TCP).
 */
public abstract class BModemGateway extends BSmsGateway
{
  public static final Property simPin = newProperty(0, BPassword.DEFAULT, null);
  public BPassword getSimPin() { return (BPassword) get(simPin); }
  public void setSimPin(BPassword v) { set(simPin, v, null); }

  /** Optional SMS centre number; leave empty to use the one stored on the SIM. */
  public static final Property smscNumber = newProperty(0, "", null);
  public String getSmscNumber() { return getString(smscNumber); }
  public void setSmscNumber(String v) { setString(smscNumber, v, null); }

  /** Read incoming SMS for commands (ACK / STATUS) and delete them from the SIM. */
  public static final Property receiveSms = newProperty(0, true, null);
  public boolean getReceiveSms() { return getBoolean(receiveSms); }
  public void setReceiveSms(boolean v) { setBoolean(receiveSms, v, null); }

  /** 0-31 (higher is better, 10+ is fine), 99 = unknown, -1 = not read yet. */
  public static final Property signalQuality = newProperty(Flags.READONLY | Flags.TRANSIENT | Flags.SUMMARY, -1, null);
  public int getSignalQuality() { return getInt(signalQuality); }
  public void setSignalQuality(int v) { setInt(signalQuality, v, null); }

  @Override
  public Type getType() { return TYPE; }
  public static final Type TYPE = Sys.loadType(BModemGateway.class);

  private AtModem modem;
  private String model = "";

  /** Open the underlying connection; streams must support available(). */
  protected abstract Streams openStreams() throws Exception;

  /** Close the underlying connection. */
  protected abstract void closeStreams();

  protected static final class Streams
  {
    final InputStream in;
    final OutputStream out;
    public Streams(InputStream in, OutputStream out) { this.in = in; this.out = out; }
  }

  private AtModem session() throws Exception
  {
    if (modem != null) return modem;
    Streams s = openStreams();
    AtModem m = new AtModem(transportName(), s.in, s.out);
    try
    {
      String pin = getSimPin().getValue();
      model = m.initialize(pin, getSmscNumber());
    }
    catch (Exception e)
    {
      closeStreams();
      throw e;
    }
    modem = m;
    return m;
  }

  @Override
  protected void doSend(String phone, String text, int ref) throws Exception
  {
    session().sendSms(phone, text, ref);
  }

  @Override
  protected String doTest() throws Exception
  {
    AtModem m = session();
    int csq = m.signalQuality();
    setSignalQuality(csq);
    boolean reg = m.isRegistered();
    if (!reg) throw new IOException("Modem not registered on the mobile network (signal " + csq + ")");
    return model + ", signal " + csq + "/31";
  }

  @Override
  public boolean supportsInbound() { return getReceiveSms() && getEnabled(); }

  @Override
  public synchronized List<AtModem.Inbound> pollInbound() throws Exception
  {
    try
    {
      return session().readAndDeleteInbox();
    }
    catch (Exception e)
    {
      doClose();
      throw e;
    }
  }

  @Override
  protected void doClose()
  {
    modem = null;
    closeStreams();
  }
}
