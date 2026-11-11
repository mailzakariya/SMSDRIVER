/*
 * Copyright (c) Tecum. All Rights Reserved.
 */
package com.tecum.sms;

import java.util.Collections;
import java.util.List;
import java.util.logging.Level;

import javax.baja.driver.BDevice;
import javax.baja.naming.SlotPath;
import javax.baja.sys.Action;
import javax.baja.sys.BAbsTime;
import javax.baja.sys.BComponent;
import javax.baja.sys.BFacets;
import javax.baja.sys.BIcon;
import javax.baja.sys.BInteger;
import javax.baja.sys.Context;
import javax.baja.sys.Flags;
import javax.baja.sys.Property;
import javax.baja.sys.Sys;
import javax.baja.sys.Type;
import javax.baja.util.IFuture;

import com.tecum.sms.core.AtModem;
import com.tecum.sms.core.PhoneNumbers;
import com.tecum.sms.core.SmsDispatcher;

/**
 * Base class for SMS gateways (devices of the SMS network). Gateways are
 * tried in priority order; if one fails the next one is used.
 */
public abstract class BSmsGateway extends BDevice implements SmsDispatcher.Transport
{
  /** 1 = tried first. Gateways with equal priority keep their tree order. */
  public static final Property priority = newProperty(Flags.SUMMARY, 1,
    BFacets.make(BFacets.MIN, BInteger.make(1), BFacets.MAX, BInteger.make(99)));
  public int getPriority() { return getInt(priority); }
  public void setPriority(int v) { setInt(priority, v, null); }

  public static final Property gatewayInfo = newProperty(Flags.READONLY | Flags.TRANSIENT | Flags.SUMMARY, "", null);
  public String getGatewayInfo() { return getString(gatewayInfo); }
  public void setGatewayInfo(String v) { setString(gatewayInfo, v, null); }

  public static final Property messagesSent = newProperty(Flags.READONLY | Flags.SUMMARY, 0, null);
  public int getMessagesSent() { return getInt(messagesSent); }
  public void setMessagesSent(int v) { setInt(messagesSent, v, null); }

  public static final Property messagesFailed = newProperty(Flags.READONLY, 0, null);
  public int getMessagesFailed() { return getInt(messagesFailed); }
  public void setMessagesFailed(int v) { setInt(messagesFailed, v, null); }

  public static final Property lastSuccess = newProperty(Flags.READONLY, BAbsTime.NULL, null);
  public BAbsTime getLastSuccess() { return (BAbsTime) get(lastSuccess); }
  public void setLastSuccess(BAbsTime v) { set(lastSuccess, v, null); }

  public static final Property lastGatewayError = newProperty(Flags.READONLY, "", null);
  public String getLastGatewayError() { return getString(lastGatewayError); }
  public void setLastGatewayError(String v) { setString(lastGatewayError, v, null); }

  /** Send through this gateway only (no failover), to verify its setup. */
  public static final Action sendTestSms = newAction(0, new BSmsTestMessage(), null);
  public void sendTestSms(BSmsTestMessage arg) { invoke(sendTestSms, arg, null); }

  @Override
  public Type getType() { return TYPE; }
  public static final Type TYPE = Sys.loadType(BSmsGateway.class);

  // ------------------------------------------------------------------ subclass API

  /** Deliver one message (all parts). Throw on failure. */
  protected abstract void doSend(String phone, String text, int ref) throws Exception;

  /** Check connectivity; returns a short status for gatewayInfo. */
  protected abstract String doTest() throws Exception;

  /** Release any open port or socket. */
  protected void doClose() {}

  @Override
  public boolean supportsInbound() { return false; }

  @Override
  public List<AtModem.Inbound> pollInbound() throws Exception { return Collections.emptyList(); }

  // ------------------------------------------------------------------ Transport

  @Override
  public String transportName() { return SlotPath.unescape(getName()); }

  @Override
  public synchronized void send(String phone, String text) throws Exception
  {
    if (!getEnabled()) throw new IllegalStateException("gateway disabled");
    BSmsNetwork net = smsNetwork();
    int ref = net != null && net.getDispatcher() != null ? net.getDispatcher().nextRef() : 0;
    try
    {
      doSend(PhoneNumbers.normalize(phone), text, ref);
      setMessagesSent(getMessagesSent() + 1);
      setLastSuccess(BAbsTime.make());
      if (getStatus().isDown()) pingOk();
    }
    catch (Exception e)
    {
      setMessagesFailed(getMessagesFailed() + 1);
      String msg = e.getMessage() == null ? e.toString() : e.getMessage();
      setLastGatewayError(msg);
      doCloseQuietly();
      pingFail(msg);
      throw e;
    }
  }

  // ------------------------------------------------------------------ driver framework

  @Override
  public Type getNetworkType() { return BSmsNetwork.TYPE; }

  @Override
  public boolean isParentLegal(BComponent parent)
  {
    return parent instanceof BSmsNetwork
        || parent instanceof BSmsGatewayFolder
        || parent instanceof javax.baja.util.BUnrestrictedFolder;
  }

  /** Runs the connectivity test on the dispatcher thread so a port is never shared. */
  @Override
  protected IFuture postPing()
  {
    BSmsNetwork net = smsNetwork();
    if (net != null && net.getDispatcher() != null)
    {
      net.getDispatcher().runExclusive(new Runnable() { public void run() { pingNow(); } });
      return null;
    }
    return null;
  }

  @Override
  public void doPing() throws Exception
  {
    pingNow();
  }

  synchronized void pingNow()
  {
    if (!getEnabled()) return;
    try
    {
      String info = doTest();
      setGatewayInfo(info == null ? "" : info);
      pingOk();
    }
    catch (Exception e)
    {
      String msg = e.getMessage() == null ? e.toString() : e.getMessage();
      setGatewayInfo("");
      setLastGatewayError(msg);
      doCloseQuietly();
      pingFail(msg);
      BSmsNetwork.LOG.log(Level.FINE, "Gateway test failed: " + transportName(), e);
    }
  }

  public void doSendTestSms(final BSmsTestMessage arg)
  {
    final BSmsNetwork net = smsNetwork();
    if (net == null || net.getDispatcher() == null) throw new IllegalStateException("SMS network is disabled or not running");
    if (!PhoneNumbers.isValid(arg.getPhoneNumber())) throw new IllegalArgumentException("Invalid phone number: " + arg.getPhoneNumber());
    final String phone = arg.getPhoneNumber();
    final String text = net.prepareText(net.renderStandard(arg.getMessage()));
    net.getDispatcher().runExclusive(new Runnable()
    {
      public void run()
      {
        try
        {
          send(phone, text);
          net.log("Test via " + transportName() + " to " + phone + " sent");
        }
        catch (Exception e)
        {
          net.log("Test via " + transportName() + " to " + phone + " FAILED: " + e.getMessage());
        }
      }
    });
  }

  void resetStatistics()
  {
    setMessagesSent(0);
    setMessagesFailed(0);
    setLastGatewayError("");
  }

  /** Close the connection from any thread; the next send reconnects. */
  void closeConnection()
  {
    BSmsNetwork net = smsNetwork();
    if (net != null && net.getDispatcher() != null)
      net.getDispatcher().runExclusive(new Runnable() { public void run() { doCloseQuietly(); } });
    else doCloseQuietly();
  }

  synchronized void doCloseQuietly()
  {
    try { doClose(); }
    catch (Throwable t) { BSmsNetwork.LOG.log(Level.FINE, "close", t); }
  }

  @Override
  public void stopped() throws Exception
  {
    doCloseQuietly();
    super.stopped();
  }

  /** Configuration changes take effect on the next message. */
  @Override
  public void changed(Property p, Context cx)
  {
    super.changed(p, cx);
    if (!isRunning()) return;
    if ((getFlags(p) & (Flags.READONLY | Flags.TRANSIENT)) != 0) return;
    if (p == priority) return;
    closeConnection();
  }

  BSmsNetwork smsNetwork()
  {
    return (BSmsNetwork) getNetwork();
  }

  @Override
  public BIcon getIcon() { return ICON; }
  private static final BIcon ICON = BIcon.std("device.png");
}
